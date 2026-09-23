use super::*;

impl NetworkIndex {
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
        // (cell identity, amount added, slot) so the totals and the posting list can both be updated.
        let mut added_to: Vec<(u32, i64, u32)> = Vec::new();
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
                    added_to.push((identity, added, slot as u32));
                }
                added
            };
            remaining -= accepted;
        }

        let inserted = amount - remaining;
        if !simulate {
            for (cell_id, added, slot) in added_to {
                self.add_total_for_cell(id, added, cell_id);
                // A cell that did not hold the key before has to enter its posting list, or the key
                // becomes invisible to `extract` and to the filtered query: both find their cells
                // through the postings rather than by scanning.
                self.posting_add(id, slot);
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
