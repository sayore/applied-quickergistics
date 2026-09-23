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

/// Differential test of the write paths against a naive model of the same rules.
///
/// The mirror does not serve extract/insert today, but they are the obvious next step and they
/// carry AE2's priority and per-key-cap rules, which are easy to get subtly wrong. The model
/// mirrors the documented semantics directly: drain in ascending (priority, insertion) order,
/// fill in descending order, honour whitelists and per-key caps.
#[test]
fn extract_and_insert_match_a_naive_model_under_random_operations() {
    const KEY_CAPACITY: usize = 24;
    const CELLS: usize = 6;
    const KEYS: u32 = 8;

    let mut net = idx();
    let mut rnd = 0x5EED_1234u32;
    let mut next = move || {
        rnd ^= rnd << 13;
        rnd ^= rnd >> 17;
        rnd ^= rnd << 5;
        rnd
    };

    // (cell id, priority, insertion index, contents, per-key cap)
    #[derive(Clone)]
    struct Model {
        priority: i32,
        order: usize,
        amounts: Vec<i64>,
        cap: i64,
    }
    let mut model: Vec<Model> = Vec::new();
    let mut cell_ids: Vec<u32> = Vec::new();

    // An explicit per-key cap on both sides: the native default is a cell property, and a model
    // that guesses it differently would compare two different systems.
    const CAP: i64 = 256;
    for i in 0..CELLS {
        let priority = (i as i32 % 3) - 1;
        let cell = net.add_cell(KEY_CAPACITY, priority);
        net.set_cell_max_per_key(cell, CAP);
        cell_ids.push(cell);
        model.push(Model {
            priority,
            order: i,
            amounts: vec![0; KEYS as usize],
            cap: CAP,
        });
    }

    // Naive extract: ascending priority, then insertion order.
    let naive_extract = |model: &mut Vec<Model>, id: u32, amount: i64| -> i64 {
        let mut order: Vec<usize> = (0..model.len()).collect();
        order.sort_by_key(|&i| (model[i].priority, model[i].order));
        let mut remaining = amount;
        for i in order {
            if remaining <= 0 {
                break;
            }
            let have = model[i].amounts[id as usize];
            let take = have.min(remaining).max(0);
            model[i].amounts[id as usize] -= take;
            remaining -= take;
        }
        amount - remaining
    };

    // Naive insert: descending priority, then insertion order.
    let naive_insert = |model: &mut Vec<Model>, id: u32, amount: i64| -> i64 {
        let mut order: Vec<usize> = (0..model.len()).collect();
        order.sort_by_key(|&i| (-model[i].priority, model[i].order));
        let mut remaining = amount;
        for i in order {
            if remaining <= 0 {
                break;
            }
            let have = model[i].amounts[id as usize];
            let room = model[i].cap.saturating_sub(have).max(0);
            let put = room.min(remaining);
            model[i].amounts[id as usize] += put;
            remaining -= put;
        }
        amount - remaining
    };

    for step in 0..600 {
        let id = next() % KEYS;
        let amount = 1 + (next() % 500) as i64;
        match next() % 8 {
            0 | 1 | 2 => {
                let expected = naive_insert(&mut model, id, amount);
                assert_eq!(
                    net.insert(id, amount, false),
                    expected,
                    "step {step}: insert {amount} of key {id}"
                );
            }
            3 | 4 | 5 => {
                let expected = naive_extract(&mut model, id, amount);
                assert_eq!(
                    net.extract(id, amount, false),
                    expected,
                    "step {step}: extract {amount} of key {id}"
                );
            }
            6 => {
                // Simulate must not change anything, on either side.
                let expected_insert = naive_insert(&mut model.clone(), id, amount);
                assert_eq!(
                    net.insert(id, amount, true),
                    expected_insert,
                    "step {step}: simulated insert"
                );
                let expected_extract = naive_extract(&mut model.clone(), id, amount);
                assert_eq!(
                    net.extract(id, amount, true),
                    expected_extract,
                    "step {step}: simulated extract"
                );
            }
            _ => {
                // Reprioritise a cell, which permutes the native cell array.
                let which = next() as usize % CELLS;
                let priority = (next() % 5) as i32 - 2;
                model[which].priority = priority;
                net.set_cell_priority(cell_ids[which], priority);
            }
        }

        // Independent invariants after every step.
        for id in 0..KEYS {
            let modelled: i64 = model.iter().map(|m| m.amounts[id as usize]).sum();
            assert_eq!(
                net.total_of(id),
                modelled,
                "step {step}: total mismatch for key {id}"
            );
        }
        assert_eq!(
            net.total_amount(),
            model.iter().flat_map(|m| m.amounts.iter()).sum::<i64>(),
            "step {step}: network total mismatch"
        );
        for i in 0..CELLS {
            let slot = net.slot(cell_ids[i]).expect("cell must stay alive");
            assert_eq!(
                net.cells[slot].priority, model[i].priority,
                "step {step}: priority of cell {i}"
            );
        }
    }
}

/// A cell id must never resolve to another cell's slot, and removing a cell must not leave the
/// network's totals describing it.
#[test]
fn cell_ids_stay_stable_across_reuse() {
    let mut net = idx();
    let a = net.add_cell(8, 0);
    net.push_cell(a, 8, &[(1, 10)]);

    // Removing and re-adding must not make the old id resolve to the new cell.
    net.remove_cell(a);
    assert_eq!(net.total_of(1), 0, "a removed cell must leave the totals");

    let b = net.add_cell(8, 0);
    net.push_cell(b, 8, &[(2, 7)]);
    assert_eq!(net.total_of(2), 7);
    assert_eq!(
        net.total_of(1),
        0,
        "the new cell must not inherit the old one's keys"
    );
    assert_eq!(net.total_amount(), 7);
    assert_eq!(net.cell_count(), 1);

    // The old id must be gone, not pointing at the reused slot.
    assert!(
        net.slot(a).is_none(),
        "removed cell id {a} must not resolve to a slot"
    );

    // And a third cell must not collide with either.
    let c = net.add_cell(8, 0);
    net.push_cell(c, 8, &[(3, 5)]);
    assert_eq!(net.total_amount(), 12);
    assert_eq!(net.cell_count(), 2);
    assert_ne!(net.slot(b), net.slot(c));
}

/// Reordering permutes the cell array, so a removed id must not be left pointing at whichever cell
/// ends up in its old slot. That happened whenever a dead cell's slot index was reused by a live
/// cell, which made the mirror read a drive that no longer exists.
#[test]
fn removed_ids_stay_retired_across_reorders() {
    let mut net = idx();
    let a = net.add_cell(8, 0);
    net.push_cell(a, 8, &[(1, 10)]);
    let b = net.add_cell(8, 5);
    net.push_cell(b, 8, &[(2, 20)]);

    // Remove a, then mount enough new cells to exhaust the free slots so a reorder has to
    // renumber them, and finally force a reorder by changing a priority.
    net.remove_cell(a);
    let c = net.add_cell(8, 5);
    net.push_cell(c, 8, &[(3, 30)]);
    let d = net.add_cell(8, -5);
    net.push_cell(d, 8, &[(4, 40)]);
    net.set_cell_priority(d, -2);
    net.set_cell_priority(c, 1);

    assert!(net.slot(a).is_none(), "removed id {a} must stay retired");
    assert_eq!(net.total_of(1), 0, "the removed cell's key must be gone");
    assert_eq!(net.total_of(2), 20);
    assert_eq!(net.total_of(3), 30);
    assert_eq!(net.total_amount(), 90);
    assert_eq!(net.total_of(4), 40);

    // The live cells are still reachable and correct.
    assert!(net.slot(b).is_some());
    assert!(net.slot(c).is_some());
    assert_ne!(net.slot(b), net.slot(c));

    // Removing a cell and then reordering must retire it as well.
    net.remove_cell(b);
    net.set_cell_priority(c, 2);
    assert!(net.slot(b).is_none(), "removed id {b} must stay retired");
    assert!(net.slot(a).is_none());
    assert_eq!(net.total_amount(), 70);
    assert_eq!(net.cell_count(), 2);

    net.remove_cell(d);
    net.set_cell_priority(c, 3);
    assert!(net.slot(d).is_none());
    assert_eq!(net.total_amount(), 30);
    assert_eq!(net.cell_count(), 1);
}

/// A filtered query takes its ids from the caller. An id outside the index's key space must be
/// ignored, not sized up to: the accumulator used to resize to the id, so a single junk id of
/// 2^32-1 asked for tens of gigabytes and took the JVM down.
#[test]
fn filtered_query_ignores_ids_outside_the_key_space() {
    let mut net = idx();
    let a = net.add_cell(8, 0);
    net.push_cell(a, 8, &[(1, 10), (2, 20)]);

    let avail = net.available(Some(&[1, u32::MAX]));
    assert_eq!(avail.ids, vec![1]);
    assert_eq!(avail.amounts, vec![10]);

    // The same for a filter that is entirely outside the key space.
    let none = net.available(Some(&[u32::MAX, u32::MAX - 1]));
    assert!(none.ids.is_empty());
    assert!(none.amounts.is_empty());

    // And the caller's ids are deduplicated, so a repeated key is not counted twice.
    let repeated = net.available(Some(&[2, 2, 2]));
    assert_eq!(repeated.ids, vec![2]);
    assert_eq!(repeated.amounts, vec![20]);
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

mod delta_tests;
mod posting_tests;
mod repro_tests;
