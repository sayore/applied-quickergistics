//! Dense, cache-friendly item-storage index for Applied Energistics 2 networks.
//!
//! # Why this exists
//!
//! AE2's network-wide storage queries (`NetworkStorage#getAvailableStacks`, extract/insert fan-out)
//! walk every mounted cell and merge its contents into a `KeyCounter`. Each of those merges goes
//! through `Object2LongOpenHashMap` lookups plus allocation-heavy iteration, and the same aggregate
//! is rebuilt over and over (storage service cache, crafting simulations, terminal refreshes,
//! automation). AE2 issue #8539 and PRs #8965/#8966/#8967 are all about this same loop.
//!
//! This crate keeps the *aggregate* in columnar form:
//!
//! * every distinct `AEKey` is assigned a dense `u32` id by the Java-side interner,
//! * every cell stores its contents as a sorted `Vec<u32>` of ids plus a parallel `Vec<i64>` of
//!   amounts,
//! * each cell additionally owns a bitset over the global id space recording which ids it holds,
//!   so the hot "does this cell matter for this query" test is a single bit test,
//! * the network-wide totals live in one flat `Vec<i64>` maintained incrementally, which makes
//!   `total_of` O(1) and a full rebuild a single sequential pass,
//! * the unfiltered aggregate is cached and invalidated by mutations, so repeated queries within a
//!   tick (the common case in AE2) cost a copy instead of a rebuild.
//!
//! Cells are stored in a dense array in ascending priority order, which is exactly the order AE2's
//! `NetworkStorage` walks for extract (descending for insert). That removes the per-operation
//! sort/collect allocation from the mutating hot paths.
//!
//! # Correctness contract
//!
//! Amounts are `i64` and are never allowed to go negative. A cell that reaches 0 for a key drops the
//! key from its slice, so `ids().len()` always equals `amounts().len()`. All mutating operations
//! return the exact amount that was moved, mirroring `MEStorage#insert`/`#extract` semantics
//! including `Actionable.SIMULATE`.

#![forbid(unsafe_code)]

use std::collections::VecDeque;

/// Sentinel used for "no key".
pub const NO_KEY: u32 = u32::MAX;

/// Upper bound on the retained change log.
///
/// The buffer only has to hold the changes a consumer has not asked for yet, and the consumer asks
/// once per server tick. It is sized generously because the cost of being wrong is asymmetric:
/// dropping a change forces a full aggregate recomputation, which is much more expensive than
/// holding a few megabytes of ring buffer. A consumer that stops asking entirely falls out of the
/// window and is told to recompute, which is correct.
const DELTA_LOG_CAPACITY: usize = 262_144;

/// A change to one key's network-wide total.
///
/// Recording `(old, new)` rather than a magnitude lets a consumer detect that its baseline is wrong
/// instead of silently drifting.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TotalChange {
    pub id: u32,
    pub old_total: i64,
    pub new_total: i64,
    /// Cell that caused the change.
    pub cell_id: u32,
    /// Revision this change belongs to. Retained revisions are dense.
    pub revision: u64,
}

/// One mounted ME storage inside the network.
#[derive(Debug, Clone, Default)]
pub struct Cell {
    /// Sorted, strictly ascending key ids present in this cell. Always the same length as `amounts`.
    ids: Vec<u32>,
    /// Amount for `ids[i]`. Never 0 for ids that are present.
    amounts: Vec<i64>,
    /// Bitset over the global key id space marking keys present in this cell.
    present: Vec<u64>,
    /// Optional whitelist bitset for preformatted/filtered cells (empty = no filter).
    whitelist: Vec<u64>,
    /// Sum of `amounts`, maintained incrementally.
    total: i64,
    /// Maximum amount per key (equal-distribution card). `i64::MAX` when unbounded.
    max_per_key: i64,
    /// Cell priority. Higher priority is used first by insert and last by extract, matching
    /// `NetworkStorage`.
    priority: i32,
    /// Caller-facing cell id. Stays attached to the cell across slot reordering.
    identity: u32,
    /// False for slots that have been removed and can be reused.
    alive: bool,
    /// Index of this cell's entry inside the posting list of the key currently being inserted, so
    /// `add` can keep that list consistent without searching it.
    posting_pos: usize,
    /// Bumped on every mutation so Java can decide whether to re-push the cell.
    version: u64,
}

impl Cell {
    fn new(priority: i32, identity: u32) -> Self {
        Cell {
            max_per_key: i64::MAX,
            priority,
            identity,
            alive: true,
            ..Default::default()
        }
    }

    #[inline]
    pub fn priority(&self) -> i32 {
        self.priority
    }

    #[inline]
    pub fn identity(&self) -> u32 {
        self.identity
    }

    #[inline]
    pub fn is_alive(&self) -> bool {
        self.alive
    }

    #[inline]
    pub fn ids(&self) -> &[u32] {
        &self.ids
    }

    #[inline]
    pub fn amounts(&self) -> &[i64] {
        &self.amounts
    }

    /// Total amount stored in this cell.
    #[inline]
    pub fn total(&self) -> i64 {
        self.total
    }

    /// Number of distinct keys stored in this cell.
    #[inline]
    pub fn len(&self) -> usize {
        self.ids.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.ids.is_empty()
    }

    pub fn set_max_per_key(&mut self, max_per_key: i64) {
        let max_per_key = max_per_key.max(0);
        if self.max_per_key != max_per_key {
            self.max_per_key = max_per_key;
            self.version = self.version.wrapping_add(1);
        }
    }

    #[inline]
    pub fn max_per_key(&self) -> i64 {
        self.max_per_key
    }

    #[inline]
    fn get(&self, id: u32) -> i64 {
        match self.ids.binary_search(&id) {
            Ok(slot) => self.amounts[slot],
            Err(_) => 0,
        }
    }

    #[inline]
    fn bit(buf: &[u64], id: u32) -> bool {
        let word = (id / 64) as usize;
        match buf.get(word) {
            Some(w) => (w >> (id % 64)) & 1 != 0,
            None => false,
        }
    }

    fn set_bit(buf: &mut Vec<u64>, id: u32) {
        let word = (id / 64) as usize;
        if buf.len() <= word {
            buf.resize(word + 1, 0);
        }
        buf[word] |= 1u64 << (id % 64);
    }

    fn clear_bit(buf: &mut [u64], id: u32) {
        let word = (id / 64) as usize;
        if let Some(w) = buf.get_mut(word) {
            *w &= !(1u64 << (id % 64));
        }
    }

    /// True if `id` is not blocked by a whitelist filter.
    #[inline]
    pub fn allows(&self, id: u32) -> bool {
        self.whitelist.is_empty() || Self::bit(&self.whitelist, id)
    }

    /// True if the given key is present in this cell.
    #[inline]
    pub fn holds(&self, id: u32) -> bool {
        Self::bit(&self.present, id)
    }

    /// Installs a whitelist of allowed key ids. Passing `None` clears any filter.
    pub fn set_whitelist(&mut self, whitelist: Option<&[u32]>) {
        match whitelist {
            None => {
                if !self.whitelist.is_empty() {
                    self.whitelist.clear();
                    self.version = self.version.wrapping_add(1);
                }
            }
            Some(ids) => {
                let mut buf = Vec::new();
                for &id in ids {
                    Self::set_bit(&mut buf, id);
                }
                if self.whitelist != buf {
                    self.whitelist = buf;
                    self.version = self.version.wrapping_add(1);
                }
            }
        }
    }

    /// Replaces the cell contents. `entries` must be sorted by id; zero amounts are dropped.
    pub fn set_contents(&mut self, entries: &[(u32, i64)]) {
        self.ids.clear();
        self.amounts.clear();
        self.ids.reserve(entries.len());
        self.amounts.reserve(entries.len());
        let mut total = 0i64;
        for &(id, amount) in entries {
            if amount > 0 {
                self.ids.push(id);
                self.amounts.push(amount);
                total += amount;
            }
        }
        self.total = total;
        self.rebuild_present();
        self.version = self.version.wrapping_add(1);
    }

    fn rebuild_present(&mut self) {
        self.present.iter_mut().for_each(|w| *w = 0);
        if let Some(last) = self.ids.last() {
            let words = (*last as usize / 64) + 1;
            if self.present.len() < words {
                self.present.resize(words, 0);
            }
        }
        for i in 0..self.ids.len() {
            let id = self.ids[i];
            let word = (id / 64) as usize;
            self.present[word] |= 1u64 << (id % 64);
        }
    }

    /// Drops entries whose amount became 0, keeping the sorted invariant.
    fn compact(&mut self) {
        if self.amounts.iter().all(|&a| a != 0) {
            return;
        }
        let mut write = 0;
        for read in 0..self.ids.len() {
            if self.amounts[read] != 0 {
                self.ids[write] = self.ids[read];
                self.amounts[write] = self.amounts[read];
                write += 1;
            }
        }
        self.ids.truncate(write);
        self.amounts.truncate(write);
        self.rebuild_present();
        self.version = self.version.wrapping_add(1);
    }

    /// Adds `amount` for `id`, clamped to `max_per_key`. Returns the amount actually added.
    fn add(&mut self, id: u32, amount: i64, respect_limit: bool) -> i64 {
        if amount <= 0 {
            return 0;
        }
        let current = self.get(id);
        let mut added = amount;
        if respect_limit {
            added = added.min(self.max_per_key.saturating_sub(current));
        }
        if added <= 0 {
            return 0;
        }
        match self.ids.binary_search(&id) {
            Ok(slot) => self.amounts[slot] += added,
            Err(slot) => {
                self.ids.insert(slot, id);
                self.amounts.insert(slot, added);
                Self::set_bit(&mut self.present, id);
            }
        }
        self.total += added;
        self.version = self.version.wrapping_add(1);
        added
    }

    /// Removes up to `amount` for `id`. Returns the amount actually removed.
    fn sub(&mut self, id: u32, amount: i64) -> i64 {
        if amount <= 0 {
            return 0;
        }
        let slot = match self.ids.binary_search(&id) {
            Ok(slot) => slot,
            Err(_) => return 0,
        };
        let current = self.amounts[slot];
        let removed = current.min(amount);
        if removed >= current {
            // Mark 0 and compact so a single extraction does not leave holes behind.
            self.amounts[slot] = 0;
            Self::clear_bit(&mut self.present, id);
            self.compact();
        } else {
            self.amounts[slot] = current - removed;
        }
        self.total -= removed;
        self.version = self.version.wrapping_add(1);
        removed
    }
}

/// Result of an aggregate query over the whole network.
#[derive(Debug, Default, Clone)]
pub struct Available {
    /// Dense key ids, sorted ascending.
    pub ids: Vec<u32>,
    /// Amount for `ids[i]`, always > 0.
    pub amounts: Vec<i64>,
}

impl Available {
    #[inline]
    pub fn len(&self) -> usize {
        self.ids.len()
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.ids.is_empty()
    }

    /// Flat interleaved representation `[id0, amount0, id1, amount1, ...]`, which is the layout
    /// handed to Java via JNI (one flat array instead of two object arrays).
    pub fn to_interleaved(&self) -> Vec<i64> {
        let mut out = Vec::with_capacity(self.ids.len() * 2);
        for i in 0..self.ids.len() {
            out.push(self.ids[i] as i64);
            out.push(self.amounts[i]);
        }
        out
    }
}

/// Reusable dense accumulator for aggregate queries.
///
/// `seen` holds the ids that currently have a non-zero value, which is precisely what a query
/// result consists of, so resetting the buffer after a query is proportional to the result size
/// instead of to the size of the key space.
#[derive(Debug, Default, Clone)]
struct DenseAccumulator {
    values: Vec<i64>,
    seen: Vec<u32>,
}

impl DenseAccumulator {
    fn reset(&mut self, key_capacity: usize) {
        if self.values.len() < key_capacity {
            self.values.resize(key_capacity, 0);
        }
        for &id in &self.seen {
            self.values[id as usize] = 0;
        }
        self.seen.clear();
    }

    /// Accumulates `amount` for `id`, zeroing the accumulator if the capacity grew past it.
    fn accumulate(&mut self, id: u32, amount: i64) {
        if amount <= 0 {
            return;
        }
        let idx = id as usize;
        if self.values.len() <= idx {
            self.values.resize(idx + 1, 0);
        }
        if self.values[idx] == 0 {
            self.seen.push(id);
        }
        self.values[idx] += amount;
    }

    fn get(&self, id: u32) -> i64 {
        self.values.get(id as usize).copied().unwrap_or(0)
    }

    fn finish(&mut self) -> Available {
        self.seen.sort_unstable();
        let mut ids = Vec::with_capacity(self.seen.len());
        let mut amounts = Vec::with_capacity(self.seen.len());
        for &id in &self.seen {
            let amount = self.values[id as usize];
            if amount > 0 {
                ids.push(id);
                amounts.push(amount);
            }
        }
        Available { ids, amounts }
    }
}

/// The network-wide storage index.
///
/// Cells are referenced by a `u32` cell id chosen by the caller (Java keeps its own mapping from
/// `MEStorage` instances). Cell ids are stable: removing a cell keeps its slot reserved until the
/// slot is reused, and priority order is maintained over the physical slots.
#[derive(Debug, Default, Clone)]
pub struct NetworkIndex {
    /// Cells in ascending priority order.
    cells: Vec<Cell>,
    /// Maps a caller-facing cell id to its slot in `cells`.
    slot_of: Vec<u32>,
    /// Per-key network totals, indexed by dense key id.
    totals: Vec<i64>,
    /// Number of keys with a non-zero network total.
    non_zero_keys: usize,
    /// Number of currently registered cells.
    cell_count: usize,
    /// Monotonic counter bumped on any mutation; lets callers cheaply detect staleness.
    revision: u64,
    /// Cached unfiltered aggregate, invalidated by every mutation.
    cached: DenseAccumulator,
    /// Whether `cached` reflects the current state.
    cached_valid: bool,
    /// Scratch buffer for filtered queries, reused between calls.
    filter_scratch: DenseAccumulator,
    /// Whether the priority order has to be restored before the next ordered operation.
    order_dirty: bool,
    /// Ring buffer of changes to network totals that a consumer has not acknowledged yet.
    delta_log: VecDeque<TotalChange>,
    /// Revision the network totals are at. Every retained change occupies exactly one revision.
    delta_revision: u64,
    /// Diagnostic: `push_cell` calls that actually changed something, and total calls.
    push_real: u64,
    push_total: u64,
    /// Reverse index: for every key id, the ascending-priority list of cell slots that hold it.
    ///
    /// Key-centric operations are otherwise O(cells) because only the cell knows about the key
    /// (via its `present` bitset). The list is the fix: it turns "find the cells holding this key"
    /// into a direct lookup, and it makes the common "nobody holds this key" case O(1) instead of a
    /// full scan. Slots are stored, not cell ids, and the entries are kept sorted by ascending
    /// priority by rewriting the lists whenever cells are reordered.
    postings: Vec<Vec<u32>>,
}

/// Amount a sorted (id, amount) list holds for `id`, or 0.
#[inline]
fn amount_of(entries: &[(u32, i64)], id: u32) -> i64 {
    match entries.binary_search_by_key(&id, |e| e.0) {
        Ok(i) => entries[i].1,
        Err(_) => 0,
    }
}

impl NetworkIndex {
    pub fn new() -> Self {
        Self {
            order_dirty: true,
            ..Default::default()
        }
    }

    #[inline]
    pub fn revision(&self) -> u64 {
        self.revision
    }

    #[inline]
    pub fn cell_count(&self) -> usize {
        self.cell_count
    }

    /// Number of keys the index can currently address.
    #[inline]
    pub fn key_capacity(&self) -> usize {
        self.totals.len()
    }

    /// Registers a new cell, returning its id.
    pub fn add_cell(&mut self, key_capacity: usize, priority: i32) -> u32 {
        self.ensure_capacity(key_capacity);
        self.ensure_postings(key_capacity);
        let id = self.slot_of.len() as u32;
        if let Some(reused) = self.cells.iter().position(|c| !c.alive) {
            self.cells[reused] = Cell::new(priority, id);
            self.slot_of.push(reused as u32);
        } else {
            self.slot_of.push(self.cells.len() as u32);
            self.cells.push(Cell::new(priority, id));
        }
        self.cell_count += 1;
        self.order_dirty = true;
        self.invalidate();
        id
    }

    /// Removes a cell, subtracting its contents from the network totals.
    pub fn remove_cell(&mut self, cell_id: u32) {
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        // Collect the contents first so the network totals can be updated without aliasing `self`.
        let contents: Vec<(u32, i64)> = {
            let cell = &self.cells[slot];
            (0..cell.ids.len())
                .map(|i| (cell.ids[i], cell.amounts[i]))
                .collect()
        };
        for (id, amount) in contents {
            self.add_total_for_cell(id, -amount, cell_id);
            self.posting_remove(id, slot as u32);
        }
        let cell = &mut self.cells[slot];
        cell.alive = false;
        cell.ids.clear();
        cell.amounts.clear();
        cell.present.clear();
        cell.whitelist.clear();
        cell.total = 0;
        self.cell_count -= 1;
        self.order_dirty = true;
        self.invalidate();
    }

    /// Grows internal storage so that key ids `< key_capacity` are addressable.
    pub fn ensure_capacity(&mut self, key_capacity: usize) {
        self.ensure_postings(key_capacity);
        if self.totals.len() < key_capacity {
            self.totals.resize(key_capacity, 0);
        }
    }

    /// Grows the posting list array to cover `key_capacity` key ids.
    pub fn ensure_postings(&mut self, key_capacity: usize) {
        if self.postings.len() < key_capacity {
            self.postings.resize_with(key_capacity, Vec::new);
        }
    }

    #[inline]
    fn slot(&self, cell_id: u32) -> Option<usize> {
        let slot = *self.slot_of.get(cell_id as usize)? as usize;
        if self.cells.get(slot).is_some_and(|c| c.alive) {
            Some(slot)
        } else {
            None
        }
    }

    #[inline]
    fn cell(&self, cell_id: u32) -> Option<&Cell> {
        self.slot(cell_id).map(|slot| &self.cells[slot])
    }

    pub fn cell_priority(&self, cell_id: u32) -> i32 {
        self.cell(cell_id).map(|c| c.priority()).unwrap_or(0)
    }

    pub fn cell_total(&self, cell_id: u32) -> i64 {
        self.cell(cell_id).map(|c| c.total()).unwrap_or(0)
    }

    pub fn cell_key_count(&self, cell_id: u32) -> usize {
        self.cell(cell_id).map(|c| c.len()).unwrap_or(0)
    }

    pub fn set_cell_priority(&mut self, cell_id: u32, priority: i32) {
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        // No early return when the priority is unchanged: keeping this branch-free means the
        // posting lists can never end up ordered by slot instead of by priority.
        let cell = &mut self.cells[slot];
        cell.priority = priority;
        cell.version = cell.version.wrapping_add(1);
        self.order_dirty = true;
        // Permute the cell array immediately so that slot order equals priority order again, and
        // rebuild the posting lists from it. Posting lists are slot-sorted, which is what keeps the
        // binary searches in `posting_add`/`posting_remove` valid; a list ordered by priority but
        // not by slot would silently break them.
        self.reorder();
        self.invalidate();
    }

    pub fn set_cell_max_per_key(&mut self, cell_id: u32, max_per_key: i64) {
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        self.cells[slot].set_max_per_key(max_per_key);
    }

    pub fn set_cell_whitelist(&mut self, cell_id: u32, whitelist: Option<&[u32]>) {
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        let before = self.cells[slot].version;
        self.cells[slot].set_whitelist(whitelist);
        if self.cells[slot].version != before {
            self.invalidate();
        }
    }

    /// Replaces a cell's contents, updating network totals.
    ///
    /// This is the incremental sync point: Java only calls it for cells whose contents changed.
    pub fn push_cell(&mut self, cell_id: u32, key_capacity: usize, entries: &[(u32, i64)]) {
        self.push_total += 1;
        // A push whose content matches what is already stored must cost nothing. This happens in
        // practice: the caller re-reads a cell whose version changed but whose contents the caller
        // then reports unchanged, and the mirror would otherwise subtract and re-add every key,
        // recording a change for each.
        if let Some(slot) = self.slot(cell_id) {
            let cell = &self.cells[slot];
            let identical = cell.ids.len() == entries.len()
                && cell
                    .ids
                    .iter()
                    .zip(entries.iter())
                    .all(|(&id, &(new_id, amount))| id == new_id && cell.get(id) == amount);
            if identical {
                return;
            }
        }
        self.push_real += 1;
        self.ensure_capacity(key_capacity);
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        let old: Vec<(u32, i64)> = {
            let cell = &self.cells[slot];
            (0..cell.ids.len())
                .map(|i| (cell.ids[i], cell.amounts[i]))
                .collect()
        };
        // Only the totals that actually move are touched. Subtracting and re-adding the whole cell
        // would record a change for every key in it, and a consumer applying that log pays for the
        // entire cell instead of for the one key that changed - which is what made the incremental
        // refresh slower than a full rebuild and kept the cell that changed as expensive as the whole
        // network.
        self.cells[slot].set_contents(entries);
        let new: Vec<(u32, i64)> = {
            let cell = &self.cells[slot];
            (0..cell.ids.len())
                .map(|i| (cell.ids[i], cell.amounts[i]))
                .collect()
        };
        // A total moves by the difference of the amounts the cell holds before and after. Walking the
        // union of both key lists instead of subtracting the whole cell and adding it back is what
        // keeps the change log proportional to what actually changed: one key per mutation instead of
        // every key in the cell, which is what made the incremental refresh lose to a full rebuild.
        //
        // The walk reads both lists through binary search rather than merging them, because the cost
        // is dominated by the recorded changes, not by the ~63 lookups a cell does.
        let mut touched: Vec<u32> = old
            .iter()
            .map(|e| e.0)
            .chain(new.iter().map(|e| e.0))
            .collect();
        touched.sort_unstable();
        touched.dedup();
        for id in touched {
            let before = amount_of(&old, id);
            let after = amount_of(&new, id);
            if before != after {
                self.add_total_for_cell(id, after - before, cell_id);
            }
        }
        // Both lists are sorted by id, so the posting lists only need the symmetric difference.
        let slot_id = slot as u32;
        let (mut o, mut n) = (0usize, 0usize);
        while o < old.len() || n < new.len() {
            let old_id = old.get(o).map(|e| e.0);
            let new_id = new.get(n).map(|e| e.0);
            match (old_id, new_id) {
                (Some(a), Some(b)) if a == b => {
                    o += 1;
                    n += 1;
                }
                (Some(a), Some(b)) if a < b => {
                    self.posting_remove(a, slot_id);
                    o += 1;
                }
                (Some(_), Some(b)) => {
                    self.posting_add(b, slot_id);
                    n += 1;
                }
                (Some(a), None) => {
                    self.posting_remove(a, slot_id);
                    o += 1;
                }
                (None, Some(b)) => {
                    self.posting_add(b, slot_id);
                    n += 1;
                }
                (None, None) => break,
            }
        }
        debug_assert!(
            {
                let cell = &self.cells[slot];
                cell.ids.windows(2).all(|w| w[0] < w[1])
                    && cell.amounts.iter().sum::<i64>() == cell.total
            },
            "cell {cell_id} invariants violated after push"
        );
        self.invalidate();
    }

    /// Applies a single-key delta to a cell and the network totals.
    pub fn apply_cell_delta(&mut self, cell_id: u32, id: u32, delta: i64) {
        if delta == 0 {
            return;
        }
        let Some(slot) = self.slot(cell_id) else {
            return;
        };
        let actual = {
            let cell = &mut self.cells[slot];
            if delta > 0 {
                cell.add(id, delta, false)
            } else {
                -cell.sub(id, -delta)
            }
        };
        self.add_total_for_cell(id, actual, cell_id);
        // The posting list tracks presence, not the amount, so a partial removal leaves it alone.
        if self.cells[slot].holds(id) {
            self.posting_add(id, slot as u32);
        } else {
            self.posting_remove(id, slot as u32);
        }
        self.invalidate();
    }

    #[inline]
    fn invalidate(&mut self) {
        self.cached_valid = false;
        self.revision = self.revision.wrapping_add(1);
    }

    /// Adds `slot` to the posting list of `id`, keeping the list in ascending priority order.
    ///
    /// Ordering is by (priority, slot) rather than by slot alone, because a cell can be
    /// reprioritised without the cell array being permuted yet, and the posting list has to stay a
    /// faithful description of the current state at all times. Idempotent.
    fn posting_add(&mut self, id: u32, slot: u32) {
        let idx = id as usize;
        if self.postings.len() <= idx {
            self.postings.resize_with(idx + 1, Vec::new);
        }
        let list = &mut self.postings[idx];
        match list.binary_search(&slot) {
            Ok(position) => self.cells[slot as usize].posting_pos = position,
            Err(position) => {
                list.insert(position, slot);
                for i in position..list.len() {
                    let shifted = list[i];
                    self.cells[shifted as usize].posting_pos = i;
                }
            }
        }
    }

    /// Removes `slot` from the posting list of `id`. Idempotent.
    fn posting_remove(&mut self, id: u32, slot: u32) {
        let Some(list) = self.postings.get_mut(id as usize) else {
            return;
        };
        if let Ok(position) = list.binary_search(&slot) {
            list.remove(position);
        }
    }

    /// Posting entries holding `id`, in ascending priority order. Empty when nobody holds it.
    #[inline]
    pub fn cells_holding(&self, id: u32) -> &[u32] {
        self.postings
            .get(id as usize)
            .map(|list| list.as_slice())
            .unwrap_or(&[])
    }

    /// Number of entries across all posting lists, for diagnostics and tests.
    pub fn posting_entry_count(&self) -> usize {
        self.postings.iter().map(|list| list.len()).sum()
    }

    /// The amount `cell` holds for `id`.
    ///
    /// The caller knows the cell holds the key, so the branch is predictable, and the cell's id
    /// slice is sorted: binary search costs ~log2(63) comparisons against the ~20 a linear walk
    /// needs for a typically filled cell. Positions are deliberately not cached in the posting
    /// entries, because inserting into a cell's id array would invalidate every position after the
    /// insertion point.
    #[inline]
    fn cell_amount(cell: &Cell, id: u32) -> i64 {
        match cell.ids.binary_search(&id) {
            Ok(position) => cell.amounts[position],
            Err(_) => 0,
        }
    }

    /// Re-derives every posting list from scratch.
    ///
    /// Used after the cell array was physically permuted, where incremental maintenance would be
    /// more error-prone than a rebuild. O(total entries) and only runs when the cell set changed.
    fn rebuild_postings(&mut self) {
        let key_capacity = self.postings.len().max(self.totals.len());
        self.postings.clear();
        self.postings.resize_with(key_capacity, Vec::new);
        for slot in 0..self.cells.len() {
            if !self.cells[slot].alive {
                continue;
            }
            for i in 0..self.cells[slot].ids.len() {
                let id = self.cells[slot].ids[i] as usize;
                if self.postings.len() <= id {
                    self.postings.resize_with(id + 1, Vec::new);
                }
                self.postings[id].push(slot as u32);
            }
        }
        // Slots are visited in ascending order, so every list is already sorted by ascending
        // priority. Record each entry's position so later incremental updates stay correct.
        for list in self.postings.iter() {
            for (position, &slot) in list.iter().enumerate() {
                self.cells[slot as usize].posting_pos = position;
            }
        }
    }

    /// Applies a change to the network total of `id` and records it in the change log.
    #[inline]
    fn add_total_for_cell(&mut self, id: u32, delta: i64, cell_id: u32) {
        if delta == 0 {
            return;
        }
        let idx = id as usize;
        if self.totals.len() <= idx {
            self.totals.resize(idx + 1, 0);
        }
        let was = self.totals[idx];
        let next = (was + delta).max(0);
        if was == 0 && next != 0 {
            self.non_zero_keys += 1;
        } else if was != 0 && next == 0 {
            self.non_zero_keys -= 1;
        }
        self.totals[idx] = next;
        if was != next {
            self.record_change(id, was, next, cell_id);
        }
    }

    /// Records a change to a network total.
    ///
    /// Entries are not merged across revisions, and every change gets its own revision even when it
    /// exactly undoes the one before it. Both properties are load-bearing: the log is dense, so the
    /// first entry a consumer needs always sits at `since_revision + 1`, and each entry's
    /// `old_total` matches the baseline of every consumer that replays it.
    ///
    /// Eliding a change that cancels its predecessor looks safe because nothing observable changed,
    /// but it is not: a consumer that was one revision behind the cancelled entry replays the next
    /// entry against a baseline it never reached. Concretely, a cell that goes 0 -> 2 -> 0 -> 7
    /// leaves a consumer at the 0 -> 2 revision with a log starting at `0 -> 7`, which it then
    /// applies to a baseline of 2 and reports 7. That is exactly how a storage bus shadowing a drive
    /// made the mirror report a stale total, so the change is recorded instead.
    fn record_change(&mut self, id: u32, old_total: i64, new_total: i64, cell_id: u32) {
        self.delta_revision += 1;
        if self.delta_log.len() >= DELTA_LOG_CAPACITY {
            self.delta_log.pop_front();
        }
        self.delta_log.push_back(TotalChange {
            id,
            old_total,
            new_total,
            cell_id,
            revision: self.delta_revision,
        });
    }

    /// Changes to network totals since `since_revision`, and the revision they bring a consumer up to.
    ///
    /// Pass the revision returned by the previous call, or [`Self::delta_revision`] to start from the
    /// current state. Returns `None` when that range is no longer retained, in which case the caller
    /// recomputes the aggregate instead of applying changes. Calling this never discards anything a
    /// slower consumer might still need.
    pub fn deltas_since(&self, since_revision: u64) -> Option<(u64, Vec<TotalChange>)> {
        if since_revision > self.delta_revision {
            // A consumer cannot have seen a future revision.
            return None;
        }
        if since_revision == self.delta_revision {
            return Some((self.delta_revision, Vec::new()));
        }
        // Entries are appended in revision order, so the first one a consumer still needs can be
        // found by binary search instead of scanning the whole retained log. Without this, every
        // call walked every entry ever retained, which made the consumer's cost grow with the log
        // rather than with the change.
        let start = self
            .delta_log
            .partition_point(|change| change.revision <= since_revision);
        // The range is complete only if it starts at the very next revision.
        match self.delta_log.get(start) {
            Some(first) if first.revision == since_revision + 1 => Some((
                self.delta_revision,
                self.delta_log.iter().skip(start).copied().collect(),
            )),
            _ => None,
        }
    }

    /// The revision the network totals are currently at.
    #[inline]
    pub fn delta_revision(&self) -> u64 {
        self.delta_revision
    }

    /// Number of retained changes, for tests and diagnostics.
    #[inline]
    pub fn pending_delta_count(&self) -> usize {
        self.delta_log.len()
    }

    /// Diagnostic: `(real pushes, total pushes)`.
    #[inline]
    pub fn push_stats(&self) -> (u64, u64) {
        (self.push_real, self.push_total)
    }

    /// O(1) network-wide amount for a key.
    #[inline]
    pub fn total_of(&self, id: u32) -> i64 {
        self.totals.get(id as usize).copied().unwrap_or(0)
    }

    /// Sums all network totals in one sequential pass.
    pub fn total_amount(&self) -> i64 {
        self.totals.iter().sum()
    }

    /// Number of keys with a non-zero network total.
    #[inline]
    pub fn distinct_keys(&self) -> usize {
        self.non_zero_keys
    }

    /// Restores the invariant that `cells` is sorted by ascending priority.
    ///
    /// Runs only after cells were added, removed or reprioritised. It reassigns the cell array from
    /// a sorted temporary, which keeps the bookkeeping in one place: the slot mapping is rebuilt from
    /// the `identity` each cell carries.
    fn reorder(&mut self) {
        if !self.order_dirty {
            return;
        }
        let all = std::mem::take(&mut self.cells);
        let mut promoted: Vec<Cell> = all.into_iter().filter(|cell| cell.alive).collect();
        promoted.sort_by_key(|cell| cell.priority);
        for (slot, cell) in promoted.iter().enumerate() {
            self.slot_of[cell.identity as usize] = slot as u32;
        }
        self.cells = promoted;
        // Slots were permuted, so every recorded slot index is stale. Rebuild rather than trying to
        // remap incrementally: this runs only when the cell set or a priority actually changed.
        self.rebuild_postings();
        self.order_dirty = false;
    }

    fn accumulate(&mut self) {
        self.cached.reset(self.totals.len());
        // Disjoint field borrows: the cell array is read while the accumulator is written.
        let cells = &self.cells;
        let cached = &mut self.cached;
        for cell in cells.iter().filter(|c| c.alive) {
            for i in 0..cell.ids.len() {
                let id = cell.ids[i];
                if cell.allows(id) {
                    cached.accumulate(id, cell.amounts[i]);
                }
            }
        }
    }

    /// The hot path: aggregate every cell into one dense, sorted result.
    ///
    /// The unfiltered result is cached until the next mutation, which matches how AE2 consumes it:
    /// the storage service cache, crafting simulations, pattern providers and terminals all request
    /// the same aggregate repeatedly within a tick.
    ///
    /// `filter` optionally restricts the result to the given key ids; those queries are answered
    /// directly (bitset test plus binary search per cell) and do not disturb the cached full result.
    pub fn available(&mut self, filter: Option<&[u32]>) -> Available {
        match filter {
            Some(ids) => {
                let mut acc = std::mem::take(&mut self.filter_scratch);
                acc.reset(self.totals.len());
                // A caller may repeat a key, and walking the same posting list once per repetition
                // would scale with the repetition count instead of with the distinct key count.
                let mut unique: Vec<u32> = ids.to_vec();
                unique.sort_unstable();
                unique.dedup();

                // The per-key walk costs one step per holder, while a plain scan of the cached
                // aggregate costs one step per cell and applies to every requested key at once. Pick
                // whichever is smaller: for a targeted search on a network where keys live in few
                // cells that is the posting list, but for keys spread over most cells the scan wins.
                let mut holder_cost = unique.len();
                for &id in &unique {
                    holder_cost += self.cells_holding(id).len();
                }
                let scan_cost = self.cells.len() * 2;

                if holder_cost <= scan_cost {
                    for &id in &unique {
                        let holders = self.cells_holding(id).to_vec();
                        for slot in holders {
                            let cell = &self.cells[slot as usize];
                            if cell.alive && cell.allows(id) {
                                acc.accumulate(id, Self::cell_amount(cell, id));
                            }
                        }
                    }
                } else {
                    if !self.cached_valid {
                        self.accumulate();
                        self.cached_valid = true;
                    }
                    for &id in &unique {
                        acc.accumulate(id, self.cached.get(id));
                    }
                }
                let result = acc.finish();
                self.filter_scratch = acc;
                result
            }
            None => {
                if !self.cached_valid {
                    self.accumulate();
                    self.cached_valid = true;
                }
                self.cached.finish()
            }
        }
    }

    /// Forces a recomputation even if a cached aggregate is still valid, and returns it.
    pub fn available_uncached(&mut self) -> Available {
        self.cached_valid = false;
        self.available(None)
    }

    /// Network-wide extract with AE2 semantics.
    ///
    /// Cells are drained in ascending priority order, matching `NetworkStorage#extract` (which walks
    /// the priority map in ascending order and returns early once satisfied). Because each cell's
    /// slice is sorted, the per-cell lookup is O(log n) instead of a hash lookup.
    pub fn extract(&mut self, id: u32, amount: i64, simulate: bool) -> i64 {
        if amount <= 0 {
            return 0;
        }
        // The posting list already gives the cells holding this key in the order AE2 drains them
        // (ascending priority), so the loop touches only those cells. When nobody holds the key the
        // list is empty and this is O(1) instead of a full scan.
        let holders: Vec<u32> = self.cells_holding(id).to_vec();
        let mut remaining = amount;
        let mut emptied: Vec<u32> = Vec::new();
        // (cell id, amount taken) so each contribution is recorded against the cell that made it.
        let mut taken_from: Vec<(u32, i64)> = Vec::new();
        for slot in holders {
            if remaining <= 0 {
                break;
            }
            let cell = &mut self.cells[slot as usize];
            if !cell.alive || !cell.allows(id) {
                continue;
            }
            let available = Self::cell_amount(cell, id);
            if available <= 0 {
                continue;
            }
            let take = available.min(remaining);
            if !simulate {
                let identity = cell.identity;
                cell.sub(id, take);
                if !cell.holds(id) {
                    emptied.push(slot);
                }
                taken_from.push((identity, take));
            }
            remaining -= take;
        }

        let extracted = amount - remaining;
        if !simulate {
            for slot in emptied {
                self.posting_remove(id, slot);
            }
            for (cell_id, take) in taken_from {
                self.add_total_for_cell(id, -take, cell_id);
            }
            self.invalidate();
        }
        extracted
    }

    /// Network-wide insert with AE2 semantics.
    ///
    /// Cells are filled in descending priority order, matching `NetworkStorage#insert` (which walks
    /// the priority map in descending order). Cells whose whitelist does not allow the key are
    /// skipped, and `max_per_key` (equal-distribution card) is honoured.
    pub fn insert(&mut self, id: u32, amount: i64, simulate: bool) -> i64 {
        if amount <= 0 {
            return 0;
        }
        self.reorder();
        let mut remaining = amount;
        let mut added_to: Vec<(u32, i64)> = Vec::new();
        for slot in (0..self.cells.len()).rev() {
            if remaining <= 0 {
                break;
            }
            let cell = &mut self.cells[slot];
            if !cell.alive || !cell.allows(id) {
                continue;
            }
            let accepted = if simulate {
                cell.max_per_key.saturating_sub(cell.get(id)).min(remaining)
            } else {
                let identity = cell.identity;
                let added = cell.add(id, remaining, true);
                if added > 0 {
                    added_to.push((identity, added));
                }
                added
            };
            remaining -= accepted;
        }

        let inserted = amount - remaining;
        if !simulate {
            for (cell_id, added) in added_to {
                self.add_total_for_cell(id, added, cell_id);
            }
            self.invalidate();
        }
        inserted
    }

    /// True if any cell already stores the key, mirroring
    /// `MEStorage#isPreferredStorageFor`'s "inventory already contains some" rule.
    pub fn is_preferred(&self, id: u32) -> bool {
        self.cells
            .iter()
            .any(|cell| cell.alive && cell.allows(id) && cell.holds(id))
    }

    /// Drops zero-amount entries from all cells (they are kept transiently to avoid O(n) shifts on
    /// every extract).
    pub fn compact(&mut self) {
        for cell in self.cells.iter_mut().filter(|c| c.alive) {
            cell.compact();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn idx() -> NetworkIndex {
        NetworkIndex::new()
    }

    #[test]
    fn available_sums_cells() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        let b = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 10), (3, 5)]);
        net.push_cell(b, 8, &[(1, 2), (2, 7)]);

        let avail = net.available(None);
        assert_eq!(avail.ids, vec![1, 2, 3]);
        assert_eq!(avail.amounts, vec![12, 7, 5]);
        assert_eq!(net.total_of(1), 12);
        assert_eq!(net.total_amount(), 24);
        assert_eq!(net.distinct_keys(), 3);
    }

    #[test]
    fn push_cell_is_incremental() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 10)]);
        net.push_cell(a, 8, &[(1, 4), (2, 6)]);
        assert_eq!(net.total_of(1), 4);
        assert_eq!(net.total_of(2), 6);
        assert_eq!(net.total_amount(), 10);
    }

    #[test]
    fn remove_cell_removes_totals() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        let b = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 10)]);
        net.push_cell(b, 8, &[(1, 5)]);
        net.remove_cell(b);
        assert_eq!(net.total_of(1), 10);
        assert_eq!(net.cell_count(), 1);
        assert!(net.available(None).ids == vec![1]);
    }

    #[test]
    fn extract_respects_priority_and_simulate() {
        let mut net = idx();
        let low = net.add_cell(8, 0);
        let high = net.add_cell(8, 5);
        net.push_cell(low, 8, &[(1, 3)]);
        net.push_cell(high, 8, &[(1, 4)]);

        // Simulate must not change anything, and low priority is drained first.
        assert_eq!(net.extract(1, 5, true), 5);
        assert_eq!(net.total_of(1), 7);
        assert_eq!(net.extract(1, 5, false), 5);
        assert_eq!(net.total_of(1), 2);
        // Exhausted the low-priority cell (3) and took 2 from the high-priority cell (4 -> 2).
        let avail = net.available(None);
        assert_eq!(avail.amounts, vec![2]);
    }

    #[test]
    fn extract_clamps_to_available() {
        let mut net = idx();
        let a = net.add_cell(4, 0);
        net.push_cell(a, 4, &[(2, 3)]);
        assert_eq!(net.extract(2, 100, false), 3);
        assert_eq!(net.total_of(2), 0);
        assert!(net.available(None).is_empty());
    }

    #[test]
    fn insert_respects_priority_and_max_per_key() {
        let mut net = idx();
        let low = net.add_cell(8, 0);
        let high = net.add_cell(8, 10);
        net.push_cell(low, 8, &[]);
        net.push_cell(high, 8, &[]);
        net.set_cell_max_per_key(high, 4);

        assert_eq!(net.insert(1, 10, false), 10);
        assert_eq!(net.cell_get(high, 1), 4);
        assert_eq!(net.cell_get(low, 1), 6);
        assert_eq!(net.total_of(1), 10);
    }

    #[test]
    fn whitelist_blocks_disallowed_keys() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.set_cell_whitelist(a, Some(&[1]));
        net.push_cell(a, 8, &[]);
        assert_eq!(net.insert(2, 10, false), 0);
        assert_eq!(net.insert(1, 10, false), 10);
        assert_eq!(net.available(None).amounts, vec![10]);
    }

    #[test]
    fn available_with_filter() {
        let mut net = idx();
        let a = net.add_cell(64, 0);
        net.push_cell(a, 64, &[(1, 1), (2, 2), (3, 3)]);
        let filtered = net.available(Some(&[3, 1]));
        assert_eq!(filtered.ids, vec![1, 3]);
        assert_eq!(filtered.amounts, vec![1, 3]);
    }

    #[test]
    fn filtered_query_does_not_destroy_cached_full_result() {
        let mut net = idx();
        let a = net.add_cell(64, 0);
        net.push_cell(a, 64, &[(1, 1), (2, 2)]);
        assert_eq!(net.available(None).ids, vec![1, 2]);
        assert_eq!(net.available(Some(&[1])).ids, vec![1]);
        assert_eq!(net.available(None).ids, vec![1, 2]);
    }

    #[test]
    fn simulate_variants_match_real_ones() {
        let mut net = idx();
        let a = net.add_cell(16, 0);
        net.push_cell(a, 16, &[(1, 7)]);
        let sim = net.extract(1, 3, true);
        let real = net.extract(1, 3, false);
        assert_eq!(sim, real);

        let mut net2 = idx();
        let b = net2.add_cell(16, 0);
        net2.push_cell(b, 16, &[]);
        assert_eq!(net2.insert(1, 5, true), net2.insert(1, 5, false));
    }

    #[test]
    fn interleaved_layout() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 10), (2, 20)]);
        assert_eq!(net.available(None).to_interleaved(), vec![1, 10, 2, 20]);
    }

    #[test]
    fn apply_cell_delta_keeps_totals_consistent() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 5)]);
        net.apply_cell_delta(a, 1, 7);
        assert_eq!(net.total_of(1), 12);
        net.apply_cell_delta(a, 1, -12);
        assert_eq!(net.total_of(1), 0);
        assert!(net.available(None).is_empty());
    }

    #[test]
    fn cell_ids_survive_reprioritisation() {
        // Regression test: reprioritising a cell permutes the physical slot array, so every
        // caller-facing cell id has to keep resolving to the same cell afterwards.
        let mut net = idx();
        let high = net.add_cell(8, 10);
        let low = net.add_cell(8, 0);
        net.push_cell(high, 8, &[(1, 1)]);
        net.push_cell(low, 8, &[(1, 2), (2, 4)]);

        // `NetworkStorage#extract` walks the priority map in ascending order, so `high` (priority
        // 10) is drained before `low` (priority 0): 1 from `high` is gone and `low` loses 1 more.
        assert_eq!(net.extract(1, 2, false), 2);
        assert_eq!(net.cell_get(high, 1), 0);
        assert_eq!(net.cell_get(low, 1), 1);
        assert_eq!(net.cell_total(high), 0);
        assert_eq!(net.cell_total(low), 5);
        assert_eq!(net.total_of(1), 1);
        assert_eq!(net.total_of(2), 4);

        // Reprioritising swaps the two cells in the physical array.
        net.set_cell_priority(low, 100);
        assert_eq!(net.cell_total(low), 5);
        assert_eq!(net.cell_total(high), 0);
        assert_eq!(net.cell_key_count(low), 2);
        assert_eq!(net.cell_key_count(high), 0);
        assert_eq!(net.total_of(1), 1);
        assert_eq!(net.total_of(2), 4);

        // `low` now has the highest priority, so it is drained first.
        assert_eq!(net.extract(1, 1, false), 1);
        assert_eq!(net.cell_get(low, 1), 0);
        assert_eq!(net.total_of(1), 0);

        assert_eq!(net.extract(2, 3, false), 3);
        assert_eq!(net.cell_get(low, 2), 1);
        assert_eq!(net.total_of(2), 1);

        // Insert fills the highest priority cell first, which is `low` (priority 100).
        assert_eq!(net.insert(1, 5, false), 5);
        assert_eq!(net.cell_get(low, 1), 5);
        assert_eq!(net.cell_get(high, 1), 0);
        assert_eq!(net.total_of(1), 5);
    }

    #[test]
    fn removed_slots_are_reused() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        let b = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 5)]);
        net.remove_cell(a);
        let c = net.add_cell(8, 0);
        net.push_cell(c, 8, &[(2, 7)]);

        assert_eq!(net.cell_count(), 2);
        assert_eq!(net.total_of(1), 0);
        assert_eq!(net.total_of(2), 7);
        assert_eq!(net.cell_total(b), 0);
        assert_eq!(net.cell_total(c), 7);
    }

    #[test]
    fn cached_aggregate_is_invalidated_by_mutation() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 5)]);
        assert_eq!(net.available(None).amounts, vec![5]);
        // A cached result must not hide a later mutation.
        net.apply_cell_delta(a, 1, 3);
        assert_eq!(net.available(None).amounts, vec![8]);
        net.extract(1, 2, false);
        assert_eq!(net.available(None).amounts, vec![6]);
    }

    #[test]
    fn distinct_keys_tracks_removals() {
        let mut net = idx();
        let a = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 5), (2, 5)]);
        assert_eq!(net.distinct_keys(), 2);
        net.extract(1, 5, false);
        assert_eq!(net.distinct_keys(), 1);
        net.remove_cell(a);
        assert_eq!(net.distinct_keys(), 0);
    }

    impl NetworkIndex {
        fn cell_get(&self, cell_id: u32, id: u32) -> i64 {
            self.cell(cell_id).map(|c| c.get(id)).unwrap_or(0)
        }
    }
}

#[cfg(test)]
mod repro_tests {
    use super::*;

    /// Mirrors the Java harness scenario: three cells with overlapping key sets.
    #[test]
    fn three_overlapping_cells_match_manual_sum() {
        let key_count = 60u32;
        let mut net = NetworkIndex::new();
        let a = net.add_cell(key_count as usize, 0);
        let b = net.add_cell(key_count as usize, 5);
        let c = net.add_cell(key_count as usize, -3);

        let mut entries_a = Vec::new();
        let mut entries_b = Vec::new();
        let mut entries_c = Vec::new();
        for i in 0..key_count {
            if i % 3 != 1 {
                entries_a.push((i, 10 + i as i64));
            }
            if i % 2 == 0 {
                entries_b.push((i, 1000 + i as i64));
            }
            if i % 5 == 0 {
                entries_c.push((i, 7));
            }
        }
        net.push_cell(a, key_count as usize, &entries_a);
        net.push_cell(b, key_count as usize, &entries_b);
        net.push_cell(c, key_count as usize, &entries_c);

        let expected_total: i64 = entries_a.iter().map(|e| e.1).sum::<i64>()
            + entries_b.iter().map(|e| e.1).sum::<i64>()
            + entries_c.iter().map(|e| e.1).sum::<i64>();
        assert_eq!(net.total_amount(), expected_total, "network total mismatch");
        assert_eq!(
            net.cell_total(a),
            entries_a.iter().map(|e| e.1).sum::<i64>()
        );
        assert_eq!(
            net.cell_total(b),
            entries_b.iter().map(|e| e.1).sum::<i64>()
        );
        assert_eq!(
            net.cell_total(c),
            entries_c.iter().map(|e| e.1).sum::<i64>()
        );

        // Per-key expectation.
        for i in 0..key_count {
            let mut expected = 0i64;
            if i % 3 != 1 {
                expected += 10 + i as i64;
            }
            if i % 2 == 0 {
                expected += 1000 + i as i64;
            }
            if i % 5 == 0 {
                expected += 7;
            }
            assert_eq!(net.total_of(i), expected, "total for key {i}");
        }

        // And the aggregate query must agree.
        let available = net.available(None);
        let mut seen = 0;
        for idx in 0..available.ids.len() {
            let id = available.ids[idx];
            let amount = available.amounts[idx];
            let mut expected = 0i64;
            if id % 3 != 1 {
                expected += 10 + id as i64;
            }
            if id % 2 == 0 {
                expected += 1000 + id as i64;
            }
            if id % 5 == 0 {
                expected += 7;
            }
            assert_eq!(amount, expected, "aggregate for key {id}");
            seen += 1;
        }
        assert_eq!(seen, 52, "distinct key count");
    }
}

#[cfg(test)]
impl NetworkIndex {
    /// Reference implementation used by the property tests: a literal scan over every cell in
    /// ascending priority order. Deliberately not optimised.
    fn extract_naive(&mut self, id: u32, amount: i64, simulate: bool) -> i64 {
        let mut order: Vec<usize> = (0..self.cells.len())
            .filter(|&s| self.cells[s].alive)
            .collect();
        order.sort_by_key(|&s| self.cells[s].priority);
        let mut remaining = amount;
        for slot in order {
            if remaining <= 0 {
                break;
            }
            let cell = &mut self.cells[slot];
            if !cell.allows(id) {
                continue;
            }
            let available = cell.get(id);
            if available <= 0 {
                continue;
            }
            let take = available.min(remaining);
            if !simulate {
                cell.sub(id, take);
            }
            remaining -= take;
        }
        amount - remaining
    }
}

#[cfg(test)]
mod posting_tests {
    use super::*;

    /// The naive definition the posting list has to match: for a key, the slots that hold it, in
    /// ascending priority order.
    fn naive_holders(net: &NetworkIndex, id: u32) -> Vec<u32> {
        let mut order: Vec<usize> = (0..net.cells.len())
            .filter(|&s| net.cells[s].alive)
            .collect();
        order.sort_by_key(|&s| net.cells[s].priority);
        order
            .into_iter()
            .filter(|&s| net.cells[s].ids.binary_search(&id).is_ok())
            .map(|s| s as u32)
            .collect()
    }

    /// Naive per-key totals, derived by summing the cells directly.
    fn naive_totals(net: &NetworkIndex, key_capacity: usize) -> Vec<i64> {
        let mut totals = vec![0i64; key_capacity];
        for cell in net.cells.iter().filter(|c| c.alive) {
            for i in 0..cell.ids.len() {
                totals[cell.ids[i] as usize] += cell.amounts[i];
            }
        }
        totals
    }

    #[test]
    fn postings_match_naive_under_random_operations() {
        const KEY_CAPACITY: usize = 40;
        const CELLS: u32 = 12;
        let mut net = NetworkIndex::new();
        let mut rnd = 0x1234_5678u32;
        let mut next = move || {
            // xorshift keeps the test dependency-free and deterministic
            rnd ^= rnd << 13;
            rnd ^= rnd >> 17;
            rnd ^= rnd << 5;
            rnd
        };

        let mut cell_ids = Vec::new();
        for i in 0..CELLS {
            cell_ids.push(net.add_cell(KEY_CAPACITY, (i as i32 % 4) - 1));
        }

        for step in 0..600 {
            let cell = cell_ids[(next() % CELLS) as usize];
            match next() % 8 {
                // Push a fresh, sorted content snapshot.
                0 | 1 | 2 => {
                    let mut entries: Vec<(u32, i64)> = Vec::new();
                    for id in 0..KEY_CAPACITY as u32 {
                        if next() % 5 == 0 {
                            entries.push((id, 1 + (next() % 1000) as i64));
                        }
                    }
                    entries.sort_unstable_by_key(|e| e.0);
                    entries.dedup_by_key(|e| e.0);
                    net.push_cell(cell, KEY_CAPACITY, &entries);
                }
                // Single-key delta, both directions.
                3 | 4 => {
                    let id = next() % KEY_CAPACITY as u32;
                    let delta = 1 + (next() % 500) as i64;
                    net.apply_cell_delta(cell, id, delta);
                }
                5 => {
                    let id = next() % KEY_CAPACITY as u32;
                    let delta = -(1 + (next() % 500) as i64);
                    net.apply_cell_delta(cell, id, delta);
                }
                // Reprioritise, which permutes the physical cell array.
                6 => {
                    let priority = (next() % 7) as i32 - 3;
                    net.set_cell_priority(cell, priority);
                }
                // Extract, which must drop emptied cells from the posting lists.
                _ => {
                    let id = next() % KEY_CAPACITY as u32;
                    net.extract(id, 1 + (next() % 2000) as i64, false);
                }
            }

            // Invariant: posting lists describe exactly the cells holding each key.
            for id in 0..KEY_CAPACITY as u32 {
                let expected = naive_holders(&net, id);
                assert_eq!(
                    net.cells_holding(id),
                    expected.as_slice(),
                    "step {step}: posting list mismatch for key {id}"
                );
            }

            // Invariant: the accelerated extract agrees with a naive scan.
            let id = next() % KEY_CAPACITY as u32;
            let amount = 1 + (next() % 3000) as i64;
            let mut reference = net.clone();
            let expected = reference.extract_naive(id, amount, true);
            assert_eq!(
                expected,
                net.extract(id, amount, true),
                "step {step}: simulated extract mismatch for key {id}"
            );

            // Invariant: network totals equal the sum over cells.
            let totals = naive_totals(&net, KEY_CAPACITY);
            for (id, expected) in totals.iter().enumerate() {
                assert_eq!(
                    net.total_of(id as u32),
                    *expected,
                    "step {step}: total mismatch for key {id}"
                );
            }
        }
    }

    #[test]
    fn postings_track_removal_and_reuse() {
        let mut net = NetworkIndex::new();
        let a = net.add_cell(8, 0);
        let b = net.add_cell(8, 5);
        net.push_cell(a, 8, &[(1, 10), (2, 20)]);
        net.push_cell(b, 8, &[(2, 30)]);
        assert_eq!(net.cells_holding(1).len(), 1);
        assert_eq!(net.cells_holding(2).len(), 2);
        assert_eq!(net.posting_entry_count(), 3);

        // Removing a cell must remove its posting entries.
        net.remove_cell(a);
        assert!(net.cells_holding(1).is_empty());
        assert_eq!(net.cells_holding(2).len(), 1);
        assert_eq!(net.posting_entry_count(), 1);

        // Extracting the last amount of a key must do the same.
        net.extract(2, 30, false);
        assert!(net.cells_holding(2).is_empty());
        assert_eq!(net.posting_entry_count(), 0);
    }

    #[test]
    fn filtered_query_matches_full_query() {
        let mut net = NetworkIndex::new();
        let a = net.add_cell(16, 0);
        let b = net.add_cell(16, 3);
        net.push_cell(a, 16, &[(1, 5), (2, 7), (4, 9)]);
        net.push_cell(b, 16, &[(2, 3), (3, 11)]);

        let full = net.available(None);
        let filter = [1u32, 2, 3];
        let filtered = net.available(Some(&filter));

        for idx in 0..filtered.ids.len() {
            let id = filtered.ids[idx];
            let full_idx = full
                .ids
                .binary_search(&id)
                .expect("key missing from full query");
            assert_eq!(filtered.amounts[idx], full.amounts[full_idx], "key {id}");
        }
        // Keys outside the filter must not appear.
        assert!(filtered.ids.iter().all(|id| filter.contains(id)));
    }
}

#[cfg(test)]
mod delta_tests {
    use super::*;

    /// A mutation touches one key, so it must also record one change. Recording the whole cell is
    /// what made the incremental refresh more expensive than rebuilding the aggregate, and it is the
    /// reason the delta stream used to be off by default.
    #[test]
    fn one_mutation_records_proportionally_few_changes() {
        const KEY_CAPACITY: usize = 64;
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(KEY_CAPACITY, 0);

        // A full cell: 64 keys, which is what AE2's largest cells hold.
        let full: Vec<(u32, i64)> = (1..=63).map(|id| (id, 10)).collect();
        net.push_cell(cell, KEY_CAPACITY, &full);

        // One item inserted into the cell.
        let mut changed = full.clone();
        changed[7].1 += 1;
        let revision = net.delta_revision();
        net.push_cell(cell, KEY_CAPACITY, &changed);
        let (_, changes) = net
            .deltas_since(revision)
            .expect("the range must be replayable");
        assert_eq!(
            changes.len(),
            1,
            "one item changed, so one change must be recorded: {changes:?}"
        );
        assert_eq!(changes[0].id, 8);
        assert_eq!(changes[0].old_total, 10);
        assert_eq!(changes[0].new_total, 11);

        // A new key appearing records one change as well, not a removal and an addition per key.
        let mut grown = changed.clone();
        grown.push((0, 5));
        grown.sort_unstable_by_key(|e| e.0);
        let revision = net.delta_revision();
        net.push_cell(cell, KEY_CAPACITY, &grown);
        let (_, changes) = net
            .deltas_since(revision)
            .expect("the range must be replayable");
        assert_eq!(changes.len(), 1, "one added key: {changes:?}");
        assert_eq!(changes[0].id, 0);
    }

    /// Replaying the change log from a recorded revision must reproduce the aggregate exactly.
    ///
    /// This is the contract the Java tick refresh relies on to update its cached inventory without
    /// rebuilding it from every cell.
    #[test]
    fn deltas_reproduce_the_aggregate() {
        const KEY_CAPACITY: usize = 32;
        let mut net = NetworkIndex::new();
        let mut rnd = 0xDEAD_BEEFu32;
        let mut next = move || {
            rnd ^= rnd << 13;
            rnd ^= rnd >> 17;
            rnd ^= rnd << 5;
            rnd
        };

        let cells: Vec<u32> = (0..8).map(|i| net.add_cell(KEY_CAPACITY, i % 3)).collect();
        // A consumer reads the totals and is now up to date at this revision.
        let mut revision = net.delta_revision();
        let mut baseline: Vec<i64> = (0..KEY_CAPACITY as u32)
            .map(|id| net.total_of(id))
            .collect();

        for round in 0..400 {
            match next() % 6 {
                0 | 1 => {
                    let cell = cells[(next() % cells.len() as u32) as usize];
                    let mut entries: Vec<(u32, i64)> = Vec::new();
                    for id in 0..KEY_CAPACITY as u32 {
                        if next() % 4 == 0 {
                            entries.push((id, 1 + (next() % 500) as i64));
                        }
                    }
                    entries.sort_unstable_by_key(|e| e.0);
                    entries.dedup_by_key(|e| e.0);
                    net.push_cell(cell, KEY_CAPACITY, &entries);
                }
                2 => {
                    let cell = cells[(next() % cells.len() as u32) as usize];
                    let id = next() % KEY_CAPACITY as u32;
                    net.apply_cell_delta(cell, id, 1 + (next() % 200) as i64);
                }
                3 => {
                    let cell = cells[(next() % cells.len() as u32) as usize];
                    let id = next() % KEY_CAPACITY as u32;
                    net.apply_cell_delta(cell, id, -(1 + (next() % 200) as i64));
                }
                4 => {
                    let id = next() % KEY_CAPACITY as u32;
                    net.extract(id, 1 + (next() % 1000) as i64, false);
                }
                _ => {
                    let id = next() % KEY_CAPACITY as u32;
                    net.insert(id, 1 + (next() % 1000) as i64, false);
                }
            }

            let (new_revision, changes) = net
                .deltas_since(revision)
                .expect("the retained window must cover one round");
            for change in changes {
                assert_eq!(
                    baseline[change.id as usize], change.old_total,
                    "round {round}: key {} claims old total {} but the consumer has {}",
                    change.id, change.old_total, baseline[change.id as usize]
                );
                baseline[change.id as usize] = change.new_total;
            }
            revision = new_revision;

            for id in 0..KEY_CAPACITY as u32 {
                assert_eq!(
                    baseline[id as usize],
                    net.total_of(id),
                    "round {round}: reconstructed total mismatch for key {id}"
                );
            }
        }
    }

    /// Reads must not produce changes, and an idle tick must report nothing.
    #[test]
    fn reads_and_idle_ticks_report_nothing() {
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(8, 0);
        net.push_cell(cell, 8, &[(1, 10)]);
        // Consume the change the push itself produced, then confirm that reads add nothing.
        let revision = net.delta_revision();
        let (_, pushed) = net.deltas_since(0).expect("push must be replayable");
        assert!(!pushed.is_empty(), "the push itself is a change");
        assert_eq!(net.pending_delta_count(), pushed.len());

        net.available(None);
        net.available(Some(&[1]));
        net.extract(1, 1, true);
        net.insert(1, 1, true);
        net.is_preferred(1);

        let (new_revision, changes) = net.deltas_since(revision).expect("nothing happened");
        assert!(changes.is_empty(), "reads must not be reported as changes");
        assert_eq!(new_revision, revision);
    }

    /// A change that exactly undoes the previous one is still recorded. Eliding it would leave a
    /// consumer that was one revision behind with a log whose entries do not match its baseline.
    #[test]
    fn a_cancelled_change_is_still_recorded() {
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(8, 0);
        net.push_cell(cell, 8, &[]);
        let revision = net.delta_revision();

        net.apply_cell_delta(cell, 1, 5);
        net.apply_cell_delta(cell, 1, -5);
        assert_eq!(net.total_of(1), 0);

        let (_, changes) = net
            .deltas_since(revision)
            .expect("current revision is replayable");
        assert_eq!(
            changes.len(),
            2,
            "both changes stay replayable: {changes:?}"
        );
        let mut replayed = 0;
        for change in &changes {
            assert_eq!(change.old_total, replayed, "revisions must replay densely");
            replayed = change.new_total;
        }
        assert_eq!(replayed, net.total_of(1));
    }

    /// A consumer that lags one revision behind must still reach the right total, which is what the
    /// elided cancellation above used to break.
    #[test]
    fn a_lagging_consumer_survives_a_cancelled_change() {
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(8, 0);
        net.push_cell(cell, 8, &[]);

        net.apply_cell_delta(cell, 7, 2);
        // A consumer reads here, i.e. it has seen the total of 2 but not what follows.
        let consumer = net.delta_revision();
        net.apply_cell_delta(cell, 7, -2);
        net.apply_cell_delta(cell, 7, 5);

        let (_, changes) = net
            .deltas_since(consumer)
            .expect("the range must be replayable");
        let mut replayed = 2;
        for change in &changes {
            assert_eq!(
                change.old_total, replayed,
                "the log must line up with the consumer"
            );
            replayed = change.new_total;
        }
        assert_eq!(replayed, net.total_of(7));
        assert_eq!(replayed, 5);
    }

    /// Each revision is retained, so a consumer that misses several still replays correctly.
    #[test]
    fn every_revision_is_retained() {
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(8, 0);
        net.push_cell(cell, 8, &[]);
        let revision = net.delta_revision();

        for _ in 0..50 {
            net.apply_cell_delta(cell, 1, 1);
        }
        let (_, changes) = net.deltas_since(revision).expect("range must be retained");
        assert_eq!(changes.len(), 50, "each increment is its own revision");
        let mut replayed = 0;
        for change in &changes {
            assert_eq!(change.old_total, replayed, "revisions must replay densely");
            replayed = change.new_total;
        }
        assert_eq!(replayed, net.total_of(1));
    }

    /// Removing a cell reports what it took away, and the log replays to the truth.
    #[test]
    fn cell_removal_reports_its_totals() {
        let mut net = NetworkIndex::new();
        let a = net.add_cell(8, 0);
        let b = net.add_cell(8, 0);
        net.push_cell(a, 8, &[(1, 10), (2, 5)]);
        net.push_cell(b, 8, &[(1, 3), (3, 7)]);

        let mut revision = net.delta_revision();
        let mut baseline: Vec<i64> = (0..4u32).map(|id| net.total_of(id)).collect();

        net.remove_cell(b);
        let (next, changes) = net
            .deltas_since(revision)
            .expect("removal must be replayable");
        assert_eq!(changes.len(), 2, "both keys of the removed cell changed");
        for change in &changes {
            assert_eq!(baseline[change.id as usize], change.old_total);
            baseline[change.id as usize] = change.new_total;
            assert_eq!(change.cell_id, b);
        }
        revision = next;
        for id in 0..4u32 {
            assert_eq!(
                baseline[id as usize],
                net.total_of(id),
                "key {id} after removal"
            );
        }

        // Removing an already removed cell must be a no-op.
        net.remove_cell(b);
        let (next, changes) = net
            .deltas_since(revision)
            .expect("empty range is replayable");
        assert!(
            changes.is_empty(),
            "a vacuous removal must not report changes"
        );
        assert_eq!(next, revision);

        // Removing the last cell brings every key it held to zero.
        net.remove_cell(a);
        let (_, changes) = net
            .deltas_since(revision)
            .expect("second removal must be replayable");
        for change in &changes {
            baseline[change.id as usize] = change.new_total;
        }
        for id in 0..4u32 {
            assert_eq!(
                baseline[id as usize],
                net.total_of(id),
                "key {id} after second removal"
            );
        }
        assert_eq!(net.total_amount(), 0);
    }

    /// A consumer that falls behind the retained window has to be told to recompute.
    #[test]
    fn falling_behind_is_reported() {
        let mut net = NetworkIndex::new();
        let cell = net.add_cell(8, 0);
        net.push_cell(cell, 8, &[]);
        let revision = net.delta_revision();

        // Overflow the window with distinct keys so the oldest entries are dropped.
        for i in 0..(DELTA_LOG_CAPACITY as u32 + 100) {
            net.apply_cell_delta(cell, i, 1);
        }
        assert_eq!(
            net.deltas_since(revision),
            None,
            "an unreplayable range must be reported rather than silently truncated"
        );
        // The current revision is always replayable and means "nothing to do".
        let now = net.delta_revision();
        let (_, changes) = net
            .deltas_since(now)
            .expect("current revision is replayable");
        assert!(changes.is_empty());
    }
}
