use super::*;

impl NetworkIndex {
    /// Adds `slot` to the posting list of `id`, keeping the list in ascending priority order.
    ///
    /// Ordering is by (priority, slot) rather than by slot alone, because a cell can be
    /// reprioritised without the cell array being permuted yet, and the posting list has to stay a
    /// faithful description of the current state at all times. Idempotent.
    pub(super) fn posting_add(&mut self, id: u32, slot: u32) {
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
    pub(super) fn posting_remove(&mut self, id: u32, slot: u32) {
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
    pub(super) fn cell_amount(cell: &Cell, id: u32) -> i64 {
        match cell.ids.binary_search(&id) {
            Ok(position) => cell.amounts[position],
            Err(_) => 0,
        }
    }

    /// Re-derives every posting list from scratch.
    ///
    /// Used after the cell array was physically permuted, where incremental maintenance would be
    /// more error-prone than a rebuild. O(total entries) and only runs when the cell set changed.
    pub(super) fn rebuild_postings(&mut self) {
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
}
