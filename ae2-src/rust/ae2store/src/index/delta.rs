use super::*;

impl NetworkIndex {
    /// Applies a change to the network total of `id` and records it in the change log.
    #[inline]
    pub(super) fn add_total_for_cell(&mut self, id: u32, delta: i64, cell_id: u32) {
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
        if !self.retain_changes {
            return;
        }
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
    pub fn deltas_since(&mut self, since_revision: u64) -> Option<(u64, Vec<TotalChange>)> {
        if since_revision > self.delta_revision {
            // A consumer cannot have seen a future revision.
            return None;
        }
        if since_revision == self.delta_revision {
            // The consumer is already up to date, so it is a consumer: keep recording for it.
            self.retain_changes = true;
            return Some((self.delta_revision, Vec::new()));
        }
        // There is a real gap. If it was not retained, this consumer cannot be served and recording for
        // it would only accumulate entries nothing will read. Returning None tells it to recompute.
        if !self.retain_changes {
            return None;
        }
        // Entries are appended in revision order, so the first one a consumer still needs can be
        // found by binary search instead of scanning the whole retained log. Without this, every
        // call walked every entry ever retained, which made the consumer's cost grow with the log
        // rather than with the change.
        let start = self
            .delta_log
            .partition_point(|change| change.revision <= since_revision);
        // The range is complete only if it starts at the very next revision. When it is not, the
        // consumer cannot be served, so stop accumulating entries for it.
        match self.delta_log.get(start) {
            Some(first) if first.revision == since_revision + 1 => Some((
                self.delta_revision,
                self.delta_log.iter().skip(start).copied().collect(),
            )),
            _ => {
                self.retain_changes = false;
                None
            }
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
}
