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

/// Sentinel used for "no key".
pub const NO_KEY: u32 = u32::MAX;

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
#[derive(Debug, Default)]
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
#[derive(Debug, Default)]
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
            self.add_total(id, -amount);
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
        if self.totals.len() < key_capacity {
            self.totals.resize(key_capacity, 0);
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
        if self.cells[slot].priority == priority {
            return;
        }
        let cell = &mut self.cells[slot];
        cell.priority = priority;
        cell.version = cell.version.wrapping_add(1);
        self.order_dirty = true;
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
        for (id, amount) in old {
            self.add_total(id, -amount);
        }
        self.cells[slot].set_contents(entries);
        let new: Vec<(u32, i64)> = {
            let cell = &self.cells[slot];
            (0..cell.ids.len())
                .map(|i| (cell.ids[i], cell.amounts[i]))
                .collect()
        };
        for (id, amount) in new {
            self.add_total(id, amount);
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
        let cell = &mut self.cells[slot];
        let actual = if delta > 0 {
            cell.add(id, delta, false)
        } else {
            -cell.sub(id, -delta)
        };
        self.add_total(id, actual);
        self.invalidate();
    }

    #[inline]
    fn invalidate(&mut self) {
        self.cached_valid = false;
        self.revision = self.revision.wrapping_add(1);
    }

    #[inline]
    fn add_total(&mut self, id: u32, delta: i64) {
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
                // Filtered queries ask about few keys but must cover every cell. Driving the loop
                // from the (short) filter keeps the work proportional to cells * filter size, and
                // the per-cell bitset turns the common "cell does not hold this key" case into a
                // single bit test instead of a binary search.
                for cell in self.cells.iter().filter(|c| c.alive) {
                    for &id in ids {
                        if cell.holds(id) && cell.allows(id) {
                            acc.accumulate(id, cell.get(id));
                        }
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
        self.reorder();
        let mut remaining = amount;
        // `NetworkStorage#extract` walks the priority map in ascending order, i.e. the lowest
        // priority inventory is drained first, so the physical (ascending) order is used directly.
        for slot in 0..self.cells.len() {
            if remaining <= 0 {
                break;
            }
            let cell = &mut self.cells[slot];
            if !cell.alive || !cell.allows(id) {
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

        let extracted = amount - remaining;
        if !simulate {
            self.add_total(id, -extracted);
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
                cell.add(id, remaining, true)
            };
            remaining -= accepted;
        }

        let inserted = amount - remaining;
        if !simulate {
            self.add_total(id, inserted);
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

        // AE2 extracts from the lowest priority inventory first, so `low` is drained before `high`.
        assert_eq!(net.extract(1, 2, false), 2);
        assert_eq!(net.cell_get(low, 1), 0);
        assert_eq!(net.cell_get(high, 1), 1);
        assert_eq!(net.cell_total(low), 4);
        assert_eq!(net.cell_total(high), 1);
        assert_eq!(net.total_of(1), 1);
        assert_eq!(net.total_of(2), 4);

        // Reprioritising swaps the two cells in the physical array.
        net.set_cell_priority(low, 100);
        assert_eq!(net.cell_total(low), 4);
        assert_eq!(net.cell_total(high), 1);
        assert_eq!(net.cell_key_count(low), 1);
        assert_eq!(net.cell_key_count(high), 1);
        assert_eq!(net.total_of(1), 1);
        assert_eq!(net.total_of(2), 4);

        // Extract now drains the highest priority cell first, which is `low` again.
        assert_eq!(net.extract(2, 3, false), 3);
        assert_eq!(net.cell_get(low, 2), 1);
        assert_eq!(net.total_of(2), 1);

        // Insert fills the highest priority cell first, which is `low` (priority 100).
        assert_eq!(net.insert(1, 5, false), 5);
        assert_eq!(net.cell_get(low, 1), 5);
        assert_eq!(net.cell_get(high, 1), 1);
        assert_eq!(net.total_of(1), 6);
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
        assert_eq!(net.cell_total(a), entries_a.iter().map(|e| e.1).sum::<i64>());
        assert_eq!(net.cell_total(b), entries_b.iter().map(|e| e.1).sum::<i64>());
        assert_eq!(net.cell_total(c), entries_c.iter().map(|e| e.1).sum::<i64>());

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
