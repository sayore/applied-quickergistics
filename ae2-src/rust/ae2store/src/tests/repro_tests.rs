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
