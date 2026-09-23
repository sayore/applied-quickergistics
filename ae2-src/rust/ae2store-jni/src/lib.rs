//! JNI surface for [`ae2store::NetworkIndex`].
//!
//! Design rules that keep the FFI boundary cheap:
//!
//! * every method is a *batch* method: one call moves a whole cell, a whole query result, or a
//!   whole key batch across the boundary,
//! * results are returned as a single flat `long[]` with an interleaved `id, amount` layout
//!   instead of object arrays,
//! * the handle is a `jlong` pointing at a boxed `NetworkIndex`; callers must `close()` it.
//!
//! The class/method signatures here must stay in sync with
//! `appeng/storage/nativebridge/NativeBindings.java`.

use ae2store::NetworkIndex;
use jni::objects::{JClass, JLongArray};
use jni::sys::{jboolean, jint, jlong, jlongArray};
use jni::JNIEnv;

/// Boxed index behind the `jlong` handle.
struct Handle {
    index: NetworkIndex,
}

/// # Safety
/// `handle` must either be 0 or a pointer previously produced by
/// [`Java_appeng_storage_nativebridge_NativeBindings_create`] and not yet destroyed.
#[inline]
unsafe fn handle_ref<'a>(handle: jlong) -> Option<&'a mut Handle> {
    if handle == 0 {
        None
    } else {
        Some(&mut *(handle as *mut Handle))
    }
}

/// Allocates a Java `long[]`, falling back to an empty array on OOM instead of aborting.
fn long_array(env: &mut JNIEnv, values: &[i64]) -> jlongArray {
    match env.new_long_array(values.len() as i32) {
        Ok(arr) => {
            if !values.is_empty() && env.set_long_array_region(&arr, 0, values).is_err() {
                env.exception_clear().ok();
                return env
                    .new_long_array(0)
                    .map(|a| a.into_raw())
                    .unwrap_or(std::ptr::null_mut());
            }
            arr.into_raw()
        }
        Err(_) => {
            env.exception_clear().ok();
            std::ptr::null_mut()
        }
    }
}

/// Reads a Java `long[]` into a `Vec<i64>`.
fn read_longs(env: &mut JNIEnv, array: &JLongArray) -> Option<Vec<i64>> {
    if array.is_null() {
        return None;
    }
    let len = env.get_array_length(array).ok()?;
    let mut buf = vec![0i64; len as usize];
    env.get_long_array_region(array, 0, &mut buf).ok()?;
    Some(buf)
}

/// Reads the `(id, amount)` pairs of a cell, dropping non-positive amounts and sorting by id.
fn read_entries(
    env: &mut JNIEnv,
    ids: &JLongArray,
    amounts: &JLongArray,
) -> Result<Vec<(u32, i64)>, String> {
    let id_buf = read_longs(env, ids).ok_or_else(|| "ids array could not be read".to_string())?;
    let amount_buf =
        read_longs(env, amounts).ok_or_else(|| "amounts array could not be read".to_string())?;
    if id_buf.len() != amount_buf.len() {
        return Err(format!(
            "ids and amounts must have the same length (got {} and {})",
            id_buf.len(),
            amount_buf.len()
        ));
    }
    let mut entries = Vec::with_capacity(id_buf.len());
    for i in 0..id_buf.len() {
        let amount = amount_buf[i];
        if amount > 0 {
            entries.push((id_buf[i] as u32, amount));
        }
    }
    entries.sort_unstable_by_key(|&(id, _)| id);
    Ok(entries)
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_create(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let handle = Box::new(Handle {
        index: NetworkIndex::new(),
    });
    Box::into_raw(handle) as jlong
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_destroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Handle));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_ensureKeyCapacity(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_capacity: jint,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.ensure_capacity(key_capacity.max(0) as usize);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_addCell(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_capacity: jint,
    priority: jint,
) -> jint {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.add_cell(key_capacity.max(0) as usize, priority) as jint,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_removeCell(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.remove_cell(cell_id as u32);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_setCellPriority(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    priority: jint,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.set_cell_priority(cell_id as u32, priority);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_setCellMaxPerKey(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    max_per_key: jlong,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.set_cell_max_per_key(cell_id as u32, max_per_key);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_setCellWhitelist(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    whitelist: JLongArray,
) {
    let Some(h) = (unsafe { handle_ref(handle) }) else {
        return;
    };
    match read_longs(&mut env, &whitelist) {
        Some(ids) => {
            let ids: Vec<u32> = ids.into_iter().map(|v| v as u32).collect();
            h.index.set_cell_whitelist(cell_id as u32, Some(&ids));
        }
        None => h.index.set_cell_whitelist(cell_id as u32, None),
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_pushCell(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    key_capacity: jint,
    ids: JLongArray,
    amounts: JLongArray,
) {
    let Some(h) = (unsafe { handle_ref(handle) }) else {
        return;
    };
    match read_entries(&mut env, &ids, &amounts) {
        Ok(entries) => h
            .index
            .push_cell(cell_id as u32, key_capacity.max(0) as usize, &entries),
        Err(msg) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_applyCellDelta(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    cell_id: jint,
    key_id: jint,
    delta: jlong,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index
            .apply_cell_delta(cell_id as u32, key_id as u32, delta);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_available(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    filter: JLongArray,
) -> jlongArray {
    let Some(h) = (unsafe { handle_ref(handle) }) else {
        return long_array(&mut env, &[]);
    };

    let filter_ids: Option<Vec<u32>> = read_longs(&mut env, &filter).map(|buf| {
        let mut ids: Vec<u32> = buf.into_iter().map(|v| v as u32).collect();
        ids.sort_unstable();
        ids.dedup();
        ids
    });

    let available = h.index.available(filter_ids.as_deref());
    long_array(&mut env, &available.to_interleaved())
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_totalOf(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_id: jint,
) -> jlong {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.total_of(key_id as u32),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_extract(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_id: jint,
    amount: jlong,
    simulate: jboolean,
) -> jlong {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.extract(key_id as u32, amount, simulate != 0),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_insert(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_id: jint,
    amount: jlong,
    simulate: jboolean,
) -> jlong {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.insert(key_id as u32, amount, simulate != 0),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_isPreferred(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key_id: jint,
) -> jboolean {
    match unsafe { handle_ref(handle) } {
        Some(h) => u8::from(h.index.is_preferred(key_id as u32)),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_compact(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.compact();
    }
}

/// Changes to network totals since `since_revision`.
///
/// Layout, all in one `long[]`:
/// `[status, currentRevision, keyId0, oldTotal0, newTotal0, cellId0, keyId1, ...]`
/// with `status` 1 when the range was replayable and 0 when the caller must recompute the
/// aggregate. Returning one flat array keeps this to a single JNI crossing per tick.
#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_deltasSince(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    since_revision: jlong,
) -> jlongArray {
    let Some(h) = (unsafe { handle_ref(handle) }) else {
        return long_array(&mut env, &[0, 0]);
    };
    match h.index.deltas_since(since_revision.max(0) as u64) {
        Some((revision, changes)) => {
            let mut flat = Vec::with_capacity(2 + changes.len() * 4);
            flat.push(1);
            flat.push(revision as i64);
            for change in changes {
                flat.push(change.id as i64);
                flat.push(change.old_total);
                flat.push(change.new_total);
                flat.push(change.cell_id as i64);
            }
            long_array(&mut env, &flat)
        }
        None => long_array(&mut env, &[0, h.index.delta_revision() as i64]),
    }
}

/// The revision the network totals are currently at.
#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_pendingDeltaCount(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.pending_delta_count() as jlong,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_setRetainChanges(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    retain: jboolean,
) {
    if let Some(h) = unsafe { handle_ref(handle) } {
        h.index.set_retain_changes(retain != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_deltaRevision(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    match unsafe { handle_ref(handle) } {
        Some(h) => h.index.delta_revision() as jlong,
        None => 0,
    }
}

/// Diagnostic: `[realPushes, totalPushes]`.
#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_pushStats(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let result: Vec<i64> = match unsafe { handle_ref(handle) } {
        Some(h) => {
            let (real, total) = h.index.push_stats();
            vec![real as i64, total as i64]
        }
        None => vec![0; 2],
    };
    long_array(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_appeng_storage_nativebridge_NativeBindings_stats(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let result: Vec<i64> = match unsafe { handle_ref(handle) } {
        Some(h) => vec![
            h.index.cell_count() as i64,
            h.index.key_capacity() as i64,
            h.index.distinct_keys() as i64,
            h.index.total_amount(),
            h.index.revision() as i64,
        ],
        None => vec![0; 5],
    };
    long_array(&mut env, &result)
}
