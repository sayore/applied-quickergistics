#!/usr/bin/env bash
# Builds the native library and runs the benchmark plus the integration harness in a plain JVM.
#
# The Gradle test task is not used because NeoForge's FML test harness refuses to start with a
# classes directory as the mod source; these tools only need the classpath.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
ae2="$repo/ae2-src"
out="$here/out"

echo "== building native library =="
cargo build --manifest-path "$ae2/rust/Cargo.toml" --release

native="$ae2/rust/target/release/libae2store_jni.so"
# Keep the copies used for jar packaging in sync, so running the game uses the same build.
cp -f "$native" "$ae2/build/native/libae2store_jni.so" 2>/dev/null || true
cp -f "$native" "$ae2/build/resources/main/native/libae2store_jni.so" 2>/dev/null || true

echo "== compiling mod sources =="
JAVA_HOME="${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}" \
    "$ae2/gradlew" -p "$ae2" :compileJava -q

echo "== resolving classpath =="
cp_file="$(mktemp)"
# Gradle resolves -I relative to the project directory, so pass an absolute path.
JAVA_HOME="${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}" \
    "$ae2/gradlew" -p "$ae2" -I "$here/cp-init.gradle" printTestClasspath -q --no-configuration-cache \
    "-Dae2.classpath.out=$cp_file"
CP="$(cat "$cp_file")"
rm -f "$cp_file"

echo "== compiling tools =="
rm -rf "$out"
mkdir -p "$out"
javac -nowarn -d "$out" -cp "$CP" \
    "$ae2"/src/main/java/appeng/storage/nativebridge/*.java \
    "$here"/bench/*.java "$here"/src/harness/*.java

run_java() {
    java --enable-native-access=ALL-UNNAMED \
        -Dae2.native.library="$native" \
        -Dae2.native.index=true \
        -cp "$out:$CP" "$@"
}

echo
echo "=================== benchmark ==================="
run_java bench.Ae2StoreBenchmark "$@"

echo
echo "=============== integration harness ==============="
run_java harness.NativeIntegrationHarness
