#!/usr/bin/env bash
#
# Build the LSPosed Magisk modules (Riru + Zygisk) from a clean checkout.
#
# LSPosed needs two libxposed artifacts in the local Maven repository before it
# can compile. Neither is published to a public repo at the version LSPosed HEAD
# wants, so this script materialises them itself: it clones each dependency at a
# pinned commit, applies the small patches needed to build against our toolchain,
# and publishes them to mavenLocal. Then it builds both flavors.
#
# Usage:
#   scripts/build.sh                  # both flavors, debug
#   scripts/build.sh release          # both flavors, release (needs signing config)
#   scripts/build.sh debug riru       # single flavor
#   FORCE_DEPS=1 scripts/build.sh     # re-clone dependencies even if cached
#
set -euo pipefail

BUILD_TYPE="${1:-debug}"
FLAVOR="${2:-all}"

case "$BUILD_TYPE" in
  debug | release) ;;
  *)
    echo "error: build type must be 'debug' or 'release', got '$BUILD_TYPE'" >&2
    exit 2
    ;;
esac

case "$FLAVOR" in
  all | riru | zygisk) ;;
  *)
    echo "error: flavor must be 'all', 'riru' or 'zygisk', got '$FLAVOR'" >&2
    exit 2
    ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_DIR="$ROOT/.deps"
cd "$ROOT"

# Pinned dependency commits.
#
# Both libxposed repos have kept their published version at "100" while
# continuing to evolve, and their `100` tags sit on very early commits. So the
# tag alone does not give you the API shape LSPosed HEAD compiles against — the
# commit has to be chosen by API shape, not by version number.
#
#   api @5458273    - later commits add invokeOrigin/invokeSpecial Constructor
#                     overloads that LSPosedContext does not implement.
#   service @tag100 - buildable as-is (Gradle 7.6), but its AIDL predates the
#     + ee4c516 aidl  interface LSPModuleService implements, so we take just the
#                     newer AIDL file. ee4c516 and not the later e58452c, which
#                     adds getRunningTargets() that LSPModuleService lacks.
#
# Both URLs can be overridden to build without hitting the network, e.g. against
# local mirrors:
#   LIBXPOSED_API_REPO=/path/to/api LIBXPOSED_SERVICE_REPO=/path/to/service \
#     scripts/build.sh
# A mirror must contain the pinned commits below.
API_REPO="${LIBXPOSED_API_REPO:-https://github.com/libxposed/api.git}"
API_COMMIT="5458273"
SERVICE_REPO="${LIBXPOSED_SERVICE_REPO:-https://github.com/libxposed/service.git}"
SERVICE_TAG="100"
SERVICE_AIDL_COMMIT="ee4c516"
SERVICE_AIDL_PATH="interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "error: '$1' not found in PATH" >&2
    exit 1
  }
}

require git

# Network fetches fail intermittently; a single blip should not abort a build that
# has already spent minutes publishing the other dependency.
retry() {
  local attempts=3 delay=5 n=1
  until "$@"; do
    if [ "$n" -ge "$attempts" ]; then
      echo "error: '$1 $2' failed after $attempts attempts" >&2
      return 1
    fi
    echo "  attempt $n failed, retrying in ${delay}s..." >&2
    sleep "$delay"
    n=$((n + 1))
    delay=$((delay * 2))
  done
}

# The cloned dependencies are separate Gradle builds and cannot see this
# project's local.properties, so give each one its own. Without this they fail
# with "SDK location not found" on any machine that relies on local.properties
# rather than ANDROID_HOME.
#
# Must run after `git clean`, which would otherwise delete the file we write.
seed_sdk_location() {
  local dir="$1"
  if [ -f "$ROOT/local.properties" ] && grep -q '^sdk\.dir=' "$ROOT/local.properties"; then
    grep '^sdk\.dir=' "$ROOT/local.properties" > "$dir/local.properties"
  elif [ -n "${ANDROID_HOME:-}" ]; then
    # Gradle reads local.properties as a Java properties file, where '\' is an
    # escape character; use forward slashes so Windows paths survive.
    echo "sdk.dir=${ANDROID_HOME//\\//}" > "$dir/local.properties"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    echo "sdk.dir=${ANDROID_SDK_ROOT//\\//}" > "$dir/local.properties"
  fi
}

# Pick the right Gradle launcher. Under Git Bash / MSYS the POSIX `gradlew`
# script runs but hands Gradle MSYS-style paths, so prefer gradlew.bat there.
gradle_in() {
  local dir="$1"
  shift
  case "${OSTYPE:-}" in
    msys | cygwin | win32) (cd "$dir" && ./gradlew.bat "$@") ;;
    *) (cd "$dir" && ./gradlew "$@") ;;
  esac
}

# --- Preflight -------------------------------------------------------------

if [ ! -f "$ROOT/local.properties" ] && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  cat >&2 <<'EOF'
error: no Android SDK configured.

Either set ANDROID_HOME / ANDROID_SDK_ROOT, or create local.properties with:

    sdk.dir=C:/Android/Sdk        # forward slashes, even on Windows

Required components: Platform 34, Build-Tools 34.0.0,
NDK 26.1.10909125, CMake 3.22.1, and JDK 17.
EOF
  exit 1
fi

# The native build reads submodules directly; an empty external/ fails deep
# inside CMake with an unhelpful error, so check up front.
if [ ! -f "$ROOT/external/lsplant/README.md" ]; then
  log "Fetching git submodules"
  git -C "$ROOT" submodule update --init --recursive
fi

# --- libxposed dependencies ------------------------------------------------

# Marker records which commits the cached artifacts were built from, so bumping
# a pin above re-publishes instead of silently reusing a stale artifact.
DEPS_MARKER="$DEPS_DIR/.published"
DEPS_STAMP="api=$API_COMMIT service=$SERVICE_TAG+$SERVICE_AIDL_COMMIT"

# The published artifacts live in the Maven local repository, which is outside the tree the
# marker travels in. A .deps/ copied from another machine therefore arrives with a marker that
# claims work that was never done here, and the build then fails deep inside dependency
# resolution. Require the artifacts themselves, not just the marker.
MAVEN_LOCAL="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
DEPS_ARTIFACTS="
$MAVEN_LOCAL/io/github/libxposed/api/100/api-100.aar
$MAVEN_LOCAL/io/github/libxposed/api/100/api-100.pom
$MAVEN_LOCAL/io/github/libxposed/interface/100/interface-100.aar
$MAVEN_LOCAL/io/github/libxposed/interface/100/interface-100.pom
"

# Reads the marker as bytes the other build script might have written: build.ps1 runs on
# Windows PowerShell 5.1, whose Set-Content -Encoding utf8 emits a UTF-8 BOM and CRLF. Without
# stripping both, a marker written by build.ps1 never compares equal here and every bash build
# re-published the dependencies from scratch.
read_marker() {
  sed -e '1s/^ï»¿//' -e 's/$//' "$1" | head -n 1
}

deps_artifacts_present() {
  local f
  for f in $DEPS_ARTIFACTS; do
    [ -f "$f" ] || { log "missing published artifact: $f"; return 1; }
  done
  return 0
}

if [ "${FORCE_DEPS:-0}" = "1" ]; then
  log "FORCE_DEPS set, discarding cached dependencies"
  rm -rf "$DEPS_DIR"
fi

if [ -f "$DEPS_MARKER" ] && [ "$(read_marker "$DEPS_MARKER")" = "$DEPS_STAMP" ]    && deps_artifacts_present; then
  log "libxposed dependencies already published ($DEPS_STAMP)"
else
  mkdir -p "$DEPS_DIR"

  log "Preparing io.github.libxposed:api:100 @$API_COMMIT"
  if [ ! -d "$DEPS_DIR/api/.git" ]; then
    rm -rf "$DEPS_DIR/api"
    retry git clone "$API_REPO" "$DEPS_DIR/api"
  fi
  retry git -C "$DEPS_DIR/api" fetch --all --tags --quiet
  git -C "$DEPS_DIR/api" checkout --quiet --force "$API_COMMIT"
  git -C "$DEPS_DIR/api" clean -xfdq -e build -e .gradle

  # Upstream targets JDK 21; we build on 17. api and its checks subproject must
  # agree or Gradle fails with "Inconsistent JVM-target compatibility".
  # These are interface/lint-only libraries, so the downgrade is safe.
  sed -i 's/JavaVersion.VERSION_21/JavaVersion.VERSION_17/g' \
    "$DEPS_DIR/api/api/build.gradle.kts" \
    "$DEPS_DIR/api/checks/build.gradle.kts"

  seed_sdk_location "$DEPS_DIR/api"
  gradle_in "$DEPS_DIR/api" :api:publishToMavenLocal

  log "Preparing io.github.libxposed:interface:100 @$SERVICE_TAG + $SERVICE_AIDL_COMMIT AIDL"
  if [ ! -d "$DEPS_DIR/service/.git" ]; then
    rm -rf "$DEPS_DIR/service"
    retry git clone "$SERVICE_REPO" "$DEPS_DIR/service"
  fi
  retry git -C "$DEPS_DIR/service" fetch --all --tags --quiet
  git -C "$DEPS_DIR/service" checkout --quiet --force "$SERVICE_TAG"
  git -C "$DEPS_DIR/service" clean -xfdq -e build -e .gradle
  # Take only the AIDL from the newer commit; the rest of tag 100 builds cleanly.
  git -C "$DEPS_DIR/service" checkout "$SERVICE_AIDL_COMMIT" -- "$SERVICE_AIDL_PATH"

  # Tag 100 pins compileSdk 33 / Build-Tools 33.0.1, which we may not have
  # installed. 34 is what the rest of this build already requires.
  sed -i -e 's/compileSdk = 33/compileSdk = 34/' \
    -e 's/buildToolsVersion = "33.0.1"/buildToolsVersion = "34.0.0"/' \
    "$DEPS_DIR/service/interface/build.gradle.kts" \
    "$DEPS_DIR/service/service/build.gradle.kts"

  seed_sdk_location "$DEPS_DIR/service"
  gradle_in "$DEPS_DIR/service" :interface:publishToMavenLocal

  # printf, not echo: no trailing newline keeps this byte-identical to what build.ps1 writes,
  # so the two scripts can share a .deps/ directory.
  printf '%s' "$DEPS_STAMP" > "$DEPS_MARKER"
fi

# --- LSPosed ---------------------------------------------------------------

suffix() { # debug -> Debug
  printf '%s' "$(printf '%s' "${1:0:1}" | tr '[:lower:]' '[:upper:]')${1:1}"
}
BT="$(suffix "$BUILD_TYPE")"

TASKS=()
case "$FLAVOR" in
  all) TASKS=(":magisk-loader:zipRiru$BT" ":magisk-loader:zipZygisk$BT") ;;
  riru) TASKS=(":magisk-loader:zipRiru$BT") ;;
  zygisk) TASKS=(":magisk-loader:zipZygisk$BT") ;;
esac

# Both flavors are built in one invocation on purpose. app/ and daemon/ are not
# flavor-specific, so building them together guarantees the two zips ship an
# identical manager.apk — building one flavor now and the other after an edit is
# how the flavors drift apart.
log "Building LSPosed ($BUILD_TYPE, $FLAVOR)"
gradle_in "$ROOT" "${TASKS[@]}"

log "Artifacts in magisk-loader/release/"
ls -1sh "$ROOT/magisk-loader/release/"*.zip 2>/dev/null || true

if [ "$FLAVOR" = "all" ] && [ -x "$ROOT/scripts/verify-parity.sh" ]; then
  log "Verifying Riru/Zygisk parity"
  "$ROOT/scripts/verify-parity.sh" "$BUILD_TYPE"
fi

# Record the build's identity while the tree is still in the state that produced it. Reconstructing
# this afterwards is guesswork: the zips carry a version but not a commit, and the working tree moves
# on. Non-fatal, because a missing manifest does not make the artifacts wrong.
if [ -x "$ROOT/scripts/release-manifest.sh" ]; then
  log "Recording release manifest"
  "$ROOT/scripts/release-manifest.sh" "$BUILD_TYPE" || log "manifest generation failed (artifacts are unaffected)"
fi
