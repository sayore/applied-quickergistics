# applied-quickergistics

A fork of [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) that adds an
optional **Rust-accelerated network item-storage index** (`ae2store`) in front of AE2's aggregate
storage queries.

* Upstream source: `ae2-src/` (branch `main`, commit `b7cf5822d`)
* Native library: `ae2-src/rust/ae2store` (core) and `ae2-src/rust/ae2store-jni` (JNI bindings)
* Java bridge: `ae2-src/src/main/java/appeng/storage/nativebridge/`
* AE2 integration: `ae2-src/src/main/java/appeng/me/storage/RustStorageIndex.java`
* Benchmark and correctness harness: `harness/`
* Feature documentation: [harness/docs/native-storage-acceleration.md](harness/docs/native-storage-acceleration.md)

## What it does

AE2 rebuilds the whole-network item tally by walking every mounted storage on each query. This fork
mirrors that tally into a dense columnar Rust index that is updated incrementally (only storages whose
version changed are re-read) and cached until something changes.

Measured on a 400-cell / 1500-item-type / 124502-entry network:

| Scenario | Java (AE2 path) | Rust mirror | Speedup |
| --- | ---: | ---: | ---: |
| Tick churn + `getAvailableStacks` (realistic) | 2925 µs | 223 µs | **~13×** |
| `getAvailableStacks`, repeated without changes | 3055 µs | 8 µs | **~370×** |
| `getAvailableStacks`, filtered to 32 keys | 238 µs | 122 µs | ~1.9× |

The mirror is read-only and best-effort: without the native library, or when a mounted storage cannot
report changes, AE2's original Java path runs unchanged. `-Dae2.native.index=false` forces the Java
path. See the [feature documentation](harness/docs/native-storage-acceleration.md) for the details,
including which numbers are honest end-to-end figures and which are cache effects.

## Quick start

```bash
# Build the mod (the native library is built by cargo as part of the Gradle build)
cd ae2-src
JAVA_HOME=/path/to/jdk-21 ./gradlew build

# Run the Rust unit tests
cd rust && cargo test --release

# Run the benchmark and the end-to-end integration harness
cd ../.. && harness/run.sh
```

Building without a Rust toolchain works and simply produces a mod that uses the Java implementation.

## Status

This is an experiment. The upstream research found no prior native/FFI attempt in AE2, so there is no
precedent for reviewer expectations or for how such a change would be received. The integration is
deliberately conservative (read path only, verifiable fallback, no changes to insert/extract semantics)
and comes with a measurement harness because upstream performance PRs have historically been rejected
for lacking measurements.
