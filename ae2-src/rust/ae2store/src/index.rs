use std::collections::VecDeque;

use crate::{Cell, TotalChange, DELTA_LOG_CAPACITY, NO_SLOT};

mod delta;
mod postings;
mod query;

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
#[derive(Debug, Clone)]
struct DenseAccumulator {
    values: Vec<i64>,
    seen: Vec<u32>,
    /// Highest id that may be accumulated. Anything above it is not a key of this index, so looking
    /// it up would be a caller error rather than a query.
    max_id: usize,
}

impl Default for DenseAccumulator {
    fn default() -> Self {
        Self {
            values: Vec::new(),
            seen: Vec::new(),
            max_id: 0,
        }
    }
}

impl DenseAccumulator {
    fn reset(&mut self, key_capacity: usize) {
        if self.values.len() < key_capacity {
            self.values.resize(key_capacity, 0);
        }
        self.max_id = key_capacity;
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
        // A filtered query takes its ids from the caller, so an id outside this index's key space has
        // to be ignored rather than sized up to. Resizing to it asked for an allocation proportional
        // to the id: one junk id of 2^32-1 is tens of gigabytes and takes the JVM down.
        if idx >= self.max_id {
            return;
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
    /// Revision the network totals are at. Every change occupies exactly one revision, whether or not
    /// it is retained in the log.
    delta_revision: u64,
    /// Whether changes are being retained for a consumer.
    ///
    /// Recording costs a log entry per mutated key, and nothing needs them until something asks for
    /// them. Retention starts on, so a consumer that connects does not have to catch a particular
    /// instant to be served, and switches off as soon as a consumer asks for a range the log cannot
    /// provide. That bounds the log for a consumer that can never be served - one behind an
    /// unmirrored mount is told to recompute every time - instead of letting it fill to capacity with
    /// entries nothing will read.
    retain_changes: bool,
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
            retain_changes: true,
            ..Default::default()
        }
    }

    #[inline]
    pub fn revision(&self) -> u64 {
        self.revision
    }

    /// Turns change recording on or off.
    ///
    /// A caller that knows it cannot use the change log - because a mounted storage cannot be mirrored,
    /// so its changes would be missing from the log - switches it off rather than letting the log fill
    /// to capacity with entries nothing will read.
    pub fn set_retain_changes(&mut self, retain: bool) {
        if self.retain_changes == retain {
            return;
        }
        self.retain_changes = retain;
        if !retain {
            self.delta_log.clear();
            self.delta_log.shrink_to_fit();
        }
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
            // The removed cell's id must stop resolving. It used to keep describing the reused slot,
            // so an unmounted storage kept reporting whatever took its place, and the mirror would
            // read a drive that no longer exists.
            let stale_id = self.cells[reused].identity;
            self.cells[reused] = Cell::new(priority, id);
            self.slot_of[stale_id as usize] = NO_SLOT;
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
        // Slots are about to be renumbered, so a removed id must not be left pointing at whatever cell
        // ends up in its old slot. Collect those ids before the cells are consumed.
        let retired: Vec<u32> = all
            .iter()
            .filter(|cell| !cell.alive)
            .map(|c| c.identity)
            .collect();
        let mut promoted: Vec<Cell> = all.into_iter().filter(|cell| cell.alive).collect();
        promoted.sort_by_key(|cell| cell.priority);
        for id in retired {
            self.slot_of[id as usize] = NO_SLOT;
        }
        for (slot, cell) in promoted.iter().enumerate() {
            self.slot_of[cell.identity as usize] = slot as u32;
        }
        self.cells = promoted;
        // Slots were permuted, so every recorded slot index is stale. Rebuild rather than trying to
        // remap incrementally: this runs only when the cell set or a priority actually changed.
        self.rebuild_postings();
        self.order_dirty = false;
    }
}

#[cfg(test)]
#[path = "tests/mod.rs"]
mod tests;
