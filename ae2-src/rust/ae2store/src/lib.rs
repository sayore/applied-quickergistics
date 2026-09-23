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

mod cell;
pub use cell::Cell;

mod index;
pub use index::{Available, NetworkIndex};

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

/// Slot value that no cell can occupy, used to retire a removed cell's id.
const NO_SLOT: u32 = u32::MAX;

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
