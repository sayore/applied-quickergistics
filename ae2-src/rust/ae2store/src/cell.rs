/// One mounted ME storage inside the network.
#[derive(Debug, Clone, Default)]
pub struct Cell {
    /// Sorted, strictly ascending key ids present in this cell. Always the same length as `amounts`.
    pub(crate) ids: Vec<u32>,
    /// Amount for `ids[i]`. Never 0 for ids that are present.
    pub(crate) amounts: Vec<i64>,
    /// Bitset over the global key id space marking keys present in this cell.
    pub(crate) present: Vec<u64>,
    /// Optional whitelist bitset for preformatted/filtered cells (empty = no filter).
    pub(crate) whitelist: Vec<u64>,
    /// Sum of `amounts`, maintained incrementally.
    pub(crate) total: i64,
    /// Maximum amount per key (equal-distribution card). `i64::MAX` when unbounded.
    pub(crate) max_per_key: i64,
    /// Cell priority. Higher priority is used first by insert and last by extract, matching
    /// `NetworkStorage`.
    pub(crate) priority: i32,
    /// Caller-facing cell id. Stays attached to the cell across slot reordering.
    pub(crate) identity: u32,
    /// False for slots that have been removed and can be reused.
    pub(crate) alive: bool,
    /// Index of this cell's entry inside the posting list of the key currently being inserted, so
    /// `add` can keep that list consistent without searching it.
    pub(crate) posting_pos: usize,
    /// Bumped on every mutation so Java can decide whether to re-push the cell.
    pub(crate) version: u64,
}

impl Cell {
    pub(crate) fn new(priority: i32, identity: u32) -> Self {
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
    pub(crate) fn get(&self, id: u32) -> i64 {
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
    pub(crate) fn compact(&mut self) {
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
    pub(crate) fn add(&mut self, id: u32, amount: i64, respect_limit: bool) -> i64 {
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
    pub(crate) fn sub(&mut self, id: u32, amount: i64) -> i64 {
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
