use super::*;

/// A caller that cannot use the change log must be able to stop it growing.
///
/// A mounted storage the mirror cannot see makes the log incomplete, and the Java side tells such a
/// consumer to recompute rather than serve it a range with holes. Without switching retention off,
/// the log would fill to its capacity with entries nothing will ever read.
#[test]
fn retention_can_be_switched_off_and_back_on() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[]);

    // On by default, so a consumer that connects immediately is served.
    net.apply_cell_delta(cell, 1, 1);
    assert_eq!(net.pending_delta_count(), 1);

    // A caller that cannot use the log switches it off; what was retained is dropped.
    net.set_retain_changes(false);
    assert_eq!(net.pending_delta_count(), 0);
    let revision = net.delta_revision();
    for _ in 0..1000 {
        net.apply_cell_delta(cell, 1, 1);
    }
    assert_eq!(
        net.pending_delta_count(),
        0,
        "nothing must accumulate while it is off"
    );
    assert_eq!(net.total_of(1), 1001);

    // Turning it back on resumes from the current revision, so a consumer that asks from there is
    // served correctly.
    net.set_retain_changes(true);
    let revision = net.delta_revision().max(revision);
    net.apply_cell_delta(cell, 1, 5);
    let (_, changes) = net
        .deltas_since(revision)
        .expect("a current consumer must be served");
    assert_eq!(changes.len(), 1);
    assert_eq!(changes[0].new_total, net.total_of(1));
}

/// A mutation touches one key, so it must also record one change.    /// A mutation touches one key, so it must also record one change. Recording the whole cell is
/// what made the incremental refresh more expensive than rebuilding the aggregate, and it is the
/// reason the delta stream used to be off by default.
#[test]
fn one_mutation_records_proportionally_few_changes() {
    const KEY_CAPACITY: usize = 64;
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(KEY_CAPACITY, 0);

    // A full cell: 64 keys, which is what AE2's largest cells hold.
    let full: Vec<(u32, i64)> = (1..=63).map(|id| (id, 10)).collect();
    net.push_cell(cell, KEY_CAPACITY, &full);

    // One item inserted into the cell.
    let mut changed = full.clone();
    changed[7].1 += 1;
    let revision = net.delta_revision();
    net.push_cell(cell, KEY_CAPACITY, &changed);
    let (_, changes) = net
        .deltas_since(revision)
        .expect("the range must be replayable");
    assert_eq!(
        changes.len(),
        1,
        "one item changed, so one change must be recorded: {changes:?}"
    );
    assert_eq!(changes[0].id, 8);
    assert_eq!(changes[0].old_total, 10);
    assert_eq!(changes[0].new_total, 11);

    // A new key appearing records one change as well, not a removal and an addition per key.
    let mut grown = changed.clone();
    grown.push((0, 5));
    grown.sort_unstable_by_key(|e| e.0);
    let revision = net.delta_revision();
    net.push_cell(cell, KEY_CAPACITY, &grown);
    let (_, changes) = net
        .deltas_since(revision)
        .expect("the range must be replayable");
    assert_eq!(changes.len(), 1, "one added key: {changes:?}");
    assert_eq!(changes[0].id, 0);
}

/// Replaying the change log from a recorded revision must reproduce the aggregate exactly.
///
/// This is the contract the Java tick refresh relies on to update its cached inventory without
/// rebuilding it from every cell.
#[test]
fn deltas_reproduce_the_aggregate() {
    const KEY_CAPACITY: usize = 32;
    let mut net = NetworkIndex::new();
    let mut rnd = 0xDEAD_BEEFu32;
    let mut next = move || {
        rnd ^= rnd << 13;
        rnd ^= rnd >> 17;
        rnd ^= rnd << 5;
        rnd
    };

    let cells: Vec<u32> = (0..8).map(|i| net.add_cell(KEY_CAPACITY, i % 3)).collect();
    // A consumer reads the totals and is now up to date at this revision.
    let mut revision = net.delta_revision();
    let mut baseline: Vec<i64> = (0..KEY_CAPACITY as u32)
        .map(|id| net.total_of(id))
        .collect();

    for round in 0..400 {
        match next() % 6 {
            0 | 1 => {
                let cell = cells[(next() % cells.len() as u32) as usize];
                let mut entries: Vec<(u32, i64)> = Vec::new();
                for id in 0..KEY_CAPACITY as u32 {
                    if next() % 4 == 0 {
                        entries.push((id, 1 + (next() % 500) as i64));
                    }
                }
                entries.sort_unstable_by_key(|e| e.0);
                entries.dedup_by_key(|e| e.0);
                net.push_cell(cell, KEY_CAPACITY, &entries);
            }
            2 => {
                let cell = cells[(next() % cells.len() as u32) as usize];
                let id = next() % KEY_CAPACITY as u32;
                net.apply_cell_delta(cell, id, 1 + (next() % 200) as i64);
            }
            3 => {
                let cell = cells[(next() % cells.len() as u32) as usize];
                let id = next() % KEY_CAPACITY as u32;
                net.apply_cell_delta(cell, id, -(1 + (next() % 200) as i64));
            }
            4 => {
                let id = next() % KEY_CAPACITY as u32;
                net.extract(id, 1 + (next() % 1000) as i64, false);
            }
            _ => {
                let id = next() % KEY_CAPACITY as u32;
                net.insert(id, 1 + (next() % 1000) as i64, false);
            }
        }

        let (new_revision, changes) = net
            .deltas_since(revision)
            .expect("the retained window must cover one round");
        for change in changes {
            assert_eq!(
                baseline[change.id as usize], change.old_total,
                "round {round}: key {} claims old total {} but the consumer has {}",
                change.id, change.old_total, baseline[change.id as usize]
            );
            baseline[change.id as usize] = change.new_total;
        }
        revision = new_revision;

        for id in 0..KEY_CAPACITY as u32 {
            assert_eq!(
                baseline[id as usize],
                net.total_of(id),
                "round {round}: reconstructed total mismatch for key {id}"
            );
        }
    }
}

/// Reads must not produce changes, and an idle tick must report nothing.
#[test]
fn reads_and_idle_ticks_report_nothing() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[(1, 10)]);
    // Consume the change the push itself produced, then confirm that reads add nothing.
    let revision = net.delta_revision();
    let (_, pushed) = net.deltas_since(0).expect("push must be replayable");
    assert!(!pushed.is_empty(), "the push itself is a change");
    assert_eq!(net.pending_delta_count(), pushed.len());

    net.available(None);
    net.available(Some(&[1]));
    net.extract(1, 1, true);
    net.insert(1, 1, true);
    net.is_preferred(1);

    let (new_revision, changes) = net.deltas_since(revision).expect("nothing happened");
    assert!(changes.is_empty(), "reads must not be reported as changes");
    assert_eq!(new_revision, revision);
}

/// A change that exactly undoes the previous one is still recorded. Eliding it would leave a
/// consumer that was one revision behind with a log whose entries do not match its baseline.
#[test]
fn a_cancelled_change_is_still_recorded() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[]);
    let revision = net.delta_revision();

    net.apply_cell_delta(cell, 1, 5);
    net.apply_cell_delta(cell, 1, -5);
    assert_eq!(net.total_of(1), 0);

    let (_, changes) = net
        .deltas_since(revision)
        .expect("current revision is replayable");
    assert_eq!(
        changes.len(),
        2,
        "both changes stay replayable: {changes:?}"
    );
    let mut replayed = 0;
    for change in &changes {
        assert_eq!(change.old_total, replayed, "revisions must replay densely");
        replayed = change.new_total;
    }
    assert_eq!(replayed, net.total_of(1));
}

/// A consumer that lags one revision behind must still reach the right total, which is what the
/// elided cancellation above used to break.
#[test]
fn a_lagging_consumer_survives_a_cancelled_change() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[]);

    net.apply_cell_delta(cell, 7, 2);
    // A consumer reads here, i.e. it has seen the total of 2 but not what follows.
    let consumer = net.delta_revision();
    net.apply_cell_delta(cell, 7, -2);
    net.apply_cell_delta(cell, 7, 5);

    let (_, changes) = net
        .deltas_since(consumer)
        .expect("the range must be replayable");
    let mut replayed = 2;
    for change in &changes {
        assert_eq!(
            change.old_total, replayed,
            "the log must line up with the consumer"
        );
        replayed = change.new_total;
    }
    assert_eq!(replayed, net.total_of(7));
    assert_eq!(replayed, 5);
}

/// Each revision is retained, so a consumer that misses several still replays correctly.
#[test]
fn every_revision_is_retained() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[]);
    let revision = net.delta_revision();

    for _ in 0..50 {
        net.apply_cell_delta(cell, 1, 1);
    }
    let (_, changes) = net.deltas_since(revision).expect("range must be retained");
    assert_eq!(changes.len(), 50, "each increment is its own revision");
    let mut replayed = 0;
    for change in &changes {
        assert_eq!(change.old_total, replayed, "revisions must replay densely");
        replayed = change.new_total;
    }
    assert_eq!(replayed, net.total_of(1));
}

/// Removing a cell reports what it took away, and the log replays to the truth.
#[test]
fn cell_removal_reports_its_totals() {
    let mut net = NetworkIndex::new();
    let a = net.add_cell(8, 0);
    let b = net.add_cell(8, 0);
    net.push_cell(a, 8, &[(1, 10), (2, 5)]);
    net.push_cell(b, 8, &[(1, 3), (3, 7)]);

    let mut revision = net.delta_revision();
    let mut baseline: Vec<i64> = (0..4u32).map(|id| net.total_of(id)).collect();

    net.remove_cell(b);
    let (next, changes) = net
        .deltas_since(revision)
        .expect("removal must be replayable");
    assert_eq!(changes.len(), 2, "both keys of the removed cell changed");
    for change in &changes {
        assert_eq!(baseline[change.id as usize], change.old_total);
        baseline[change.id as usize] = change.new_total;
        assert_eq!(change.cell_id, b);
    }
    revision = next;
    for id in 0..4u32 {
        assert_eq!(
            baseline[id as usize],
            net.total_of(id),
            "key {id} after removal"
        );
    }

    // Removing an already removed cell must be a no-op.
    net.remove_cell(b);
    let (next, changes) = net
        .deltas_since(revision)
        .expect("empty range is replayable");
    assert!(
        changes.is_empty(),
        "a vacuous removal must not report changes"
    );
    assert_eq!(next, revision);

    // Removing the last cell brings every key it held to zero.
    net.remove_cell(a);
    let (_, changes) = net
        .deltas_since(revision)
        .expect("second removal must be replayable");
    for change in &changes {
        baseline[change.id as usize] = change.new_total;
    }
    for id in 0..4u32 {
        assert_eq!(
            baseline[id as usize],
            net.total_of(id),
            "key {id} after second removal"
        );
    }
    assert_eq!(net.total_amount(), 0);
}

/// A consumer that falls behind the retained window has to be told to recompute.
#[test]
fn falling_behind_is_reported() {
    let mut net = NetworkIndex::new();
    let cell = net.add_cell(8, 0);
    net.push_cell(cell, 8, &[]);
    let revision = net.delta_revision();

    // Overflow the window with distinct keys so the oldest entries are dropped.
    for i in 0..(DELTA_LOG_CAPACITY as u32 + 100) {
        net.apply_cell_delta(cell, i, 1);
    }
    assert_eq!(
        net.deltas_since(revision),
        None,
        "an unreplayable range must be reported rather than silently truncated"
    );
    // The current revision is always replayable and means "nothing to do".
    let now = net.delta_revision();
    let (_, changes) = net
        .deltas_since(now)
        .expect("current revision is replayable");
    assert!(changes.is_empty());
}

/// Extended lifecycle check: one million mutations across repeated mount/unmount cycles.
/// Run explicitly with `cargo test --release -- --ignored` before a release.
#[test]
#[ignore = "long-running release validation"]
fn million_mutations_replay_across_mount_cycles() {
    let mut net = NetworkIndex::new();
    let mut baseline = [0i64; 64];
    let mut revision = net.delta_revision();

    for cycle in 0..1_000 {
        let cell = net.add_cell(64, cycle % 7);
        net.push_cell(cell, 64, &[]);
        for operation in 0..1_000 {
            net.apply_cell_delta(cell, (operation % 64) as u32, 1);
        }
        net.remove_cell(cell);

        let (next, changes) = net
            .deltas_since(revision)
            .expect("active consumer must be able to replay each mount cycle");
        for change in changes {
            let slot = change.id as usize;
            assert_eq!(baseline[slot], change.old_total);
            baseline[slot] = change.new_total;
        }
        revision = next;
        assert!(baseline.iter().all(|&amount| amount == 0));
        assert_eq!(net.total_amount(), 0);
        assert_eq!(net.cell_count(), 0);
        assert!(net.pending_delta_count() <= DELTA_LOG_CAPACITY);
    }
}
