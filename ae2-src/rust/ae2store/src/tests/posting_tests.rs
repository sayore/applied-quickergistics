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
