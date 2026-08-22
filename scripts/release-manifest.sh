#!/usr/bin/env bash
#
# Record what a release actually is: which source state produced it, and which bytes came out.
#
# The version metadata inside the artifacts says which release a zip claims to be. It cannot say
# which tree it was built from, and it cannot prove that the file someone is holding is the file
# that was built. Both questions come up constantly for a fork that is flashed by hand -- "which of
# my builds is on that watch" and "is this zip still the one I made" -- and neither is answerable
# from a zip alone once it has been copied around. So this writes a manifest next to the zips.
#
# Reads build outputs and git metadata only. It never opens the signing keystore: the certificate
# digest recorded below is read out of the already-signed apk, which is public information.
#
# Usage:
#   scripts/release-manifest.sh [debug|release]
#
# Exit codes: 0 = manifest written, 2 = usage or missing artifacts.
#
set -uo pipefail

BUILD_TYPE="${1:-release}"
case "$BUILD_TYPE" in
debug | release) ;;
*)
  echo "error: build type must be 'debug' or 'release', got '$BUILD_TYPE'" >&2
  exit 2
  ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_DIR="$ROOT/magisk-loader/release"

shopt -s nullglob
RIRU_ZIPS=("$RELEASE_DIR"/LSPosed-v*-riru-"$BUILD_TYPE".zip)
ZYGISK_ZIPS=("$RELEASE_DIR"/LSPosed-v*-zygisk-"$BUILD_TYPE".zip)
shopt -u nullglob

if [ ${#RIRU_ZIPS[@]} -eq 0 ] && [ ${#ZYGISK_ZIPS[@]} -eq 0 ]; then
  echo "error: no $BUILD_TYPE zips in magisk-loader/release/" >&2
  echo "hint: scripts/build.sh $BUILD_TYPE" >&2
  exit 2
fi
# Two zips of the same flavor mean a stale build is still lying around, and a manifest cannot say
# which of them it describes.
if [ ${#RIRU_ZIPS[@]} -gt 1 ] || [ ${#ZYGISK_ZIPS[@]} -gt 1 ]; then
  echo "error: multiple $BUILD_TYPE zips of the same flavor present; remove stale versions first" >&2
  printf '  %s\n' "${RIRU_ZIPS[@]}" "${ZYGISK_ZIPS[@]}" >&2
  exit 2
fi

ZIPS=("${RIRU_ZIPS[@]}" "${ZYGISK_ZIPS[@]}")
FLAVORS=""
[ ${#RIRU_ZIPS[@]} -eq 1 ] && FLAVORS="riru"
[ ${#ZYGISK_ZIPS[@]} -eq 1 ] && FLAVORS="${FLAVORS:+$FLAVORS, }zygisk"

command -v sha256sum >/dev/null 2>&1 || { echo "error: 'sha256sum' not found in PATH" >&2; exit 2; }

# --- Version, from the zip filename ----------------------------------------
#
# LSPosed-v<verName>-<verCode>-<flavor>-<buildType>.zip. verify-parity.sh already asserts that this
# agrees with module.prop and with both apks, so reading it from the name here is not a shortcut
# around a check -- it is reading the value that check pins down.
zip_ver() {
  local b="${1##*/}"
  b="${b#LSPosed-v}"
  b="${b%-*-*.zip}"
  case "$b" in
  *-*) printf '%s' "$b" ;;
  esac
}
VER="$(zip_ver "${ZIPS[0]}")"
if [ -z "$VER" ]; then
  echo "error: cannot parse a version out of $(basename "${ZIPS[0]}")" >&2
  exit 2
fi
VER_NAME="${VER%-*}"
VER_CODE="${VER##*-}"

STEM="LSPosed-v$VER_NAME-$VER_CODE-$BUILD_TYPE"
MANIFEST="$RELEASE_DIR/$STEM.manifest.txt"
CHECKSUMS="$RELEASE_DIR/$STEM.sha256"

# --- Build inputs ----------------------------------------------------------

# Pulls `val <name> by extra(<value>)` out of the root build script, so the manifest quotes the real
# build inputs rather than a hand-maintained copy of them that can go stale.
gradle_extra() { # <name>
  local line
  line="$(grep -m1 "val $1 by extra(" "$ROOT/build.gradle.kts" 2>/dev/null)"
  line="${line#*extra(}"
  line="${line%%)*}"
  line="${line#'"'}"
  line="${line%'"'}"
  printf '%s' "${line:-unknown}"
}

toml_version() { # <key>
  local line
  line="$(grep -m1 "^$1 = " "$ROOT/gradle/libs.versions.toml" 2>/dev/null)"
  line="${line#*= }"
  line="${line#'"'}"
  line="${line%'"'}"
  printf '%s' "${line:-unknown}"
}

gradle_version() {
  local line
  line="$(grep -m1 '^distributionUrl' "$ROOT/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null)"
  line="${line##*/gradle-}"
  line="${line%%-*}"
  printf '%s' "${line:-unknown}"
}

git_field() { # <fallback> <git-args...>
  local out fallback="$1"
  shift
  if out="$(git -C "$ROOT" "$@" 2>/dev/null)" && [ -n "$out" ]; then
    printf '%s' "$out"
  else
    printf '%s' "$fallback"
  fi
}

COMMIT="$(git_field "unknown (not a git checkout)" rev-parse HEAD)"
COMMIT_DATE="$(git_field unknown log -1 --format=%cI)"
BRANCH="$(git_field unknown rev-parse --abbrev-ref HEAD)"
DESCRIBE="$(git_field "" describe --tags --always --dirty)"
DIRTY_LIST="$(git -C "$ROOT" status --porcelain 2>/dev/null)"
DIRTY_COUNT=0
[ -n "$DIRTY_LIST" ] && DIRTY_COUNT="$(printf '%s\n' "$DIRTY_LIST" | grep -c .)"

# --- Payload hashes --------------------------------------------------------
#
# The zip hash identifies the file; these identify what is inside it. That distinction matters
# because the two flavors are meant to ship an identical manager.apk and daemon.apk, so recording
# the payload hashes lets a later build be compared against this one without unpacking anything.
WORK=""
cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

PAYLOAD_LINES=""
CERT_LINES=""
if command -v unzip >/dev/null 2>&1; then
  WORK="$(mktemp -d)"
  if unzip -qo "${ZIPS[0]}" -d "$WORK" 2>/dev/null; then
    for f in manager.apk daemon.apk lspd-cli framework/lspd.dex; do
      [ -f "$WORK/$f" ] || continue
      h="$(sha256sum "$WORK/$f" | cut -d' ' -f1)"
      PAYLOAD_LINES="$PAYLOAD_LINES  $h  $f
"
    done

    # apksigner reads the certificate out of the apk. It never needs, and is never given, the
    # keystore: the point is to confirm an artifact was signed by the expected key without touching
    # the key itself.
    APKSIGNER=""
    for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null | tr -d '\r')"; do
      [ -n "$base" ] || continue
      cand="$(find "$base/build-tools" -maxdepth 2 -name 'apksigner*' -type f 2>/dev/null | sort -r | head -1)"
      if [ -n "$cand" ]; then APKSIGNER="$cand"; break; fi
    done
    if [ -n "$APKSIGNER" ] && [ -f "$WORK/manager.apk" ]; then
      CERT_LINES="$("$APKSIGNER" verify --print-certs "$WORK/manager.apk" 2>/dev/null |
        grep -E 'certificate (DN|SHA-256 digest)' | sed 's/^/  /')"
    fi
  fi
fi

# --- Write -----------------------------------------------------------------

{
  echo "LSPosed fork release manifest"
  echo "============================="
  echo
  printf '  version        v%s (%s)\n' "$VER_NAME" "$VER_CODE"
  printf '  build type     %s\n' "$BUILD_TYPE"
  printf '  flavors        %s\n' "$FLAVORS"
  printf '  generated      %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "Source"
  echo "------"
  printf '  commit         %s\n' "$COMMIT"
  printf '  committed      %s\n' "$COMMIT_DATE"
  printf '  branch         %s\n' "$BRANCH"
  [ -n "$DESCRIBE" ] && printf '  describe       %s\n' "$DESCRIBE"
  if [ "$DIRTY_COUNT" -eq 0 ]; then
    printf '  tree state     clean\n'
  else
    # A dirty tree means the commit above does not identify this build, so say so loudly rather than
    # recording a commit that cannot reproduce these bytes.
    printf '  tree state     DIRTY -- %s path(s) below are NOT in the commit above,\n' "$DIRTY_COUNT"
    printf '                 so that commit alone does not reproduce these artifacts\n'
    printf '%s\n' "$DIRTY_LIST" | head -40 | sed 's/^/                 /'
    [ "$DIRTY_COUNT" -gt 40 ] && printf '                 ... and %s more\n' "$((DIRTY_COUNT - 40))"
  fi
  echo
  echo "Toolchain"
  echo "---------"
  printf '  gradle         %s\n' "$(gradle_version)"
  printf '  agp            %s\n' "$(toml_version agp)"
  printf '  compileSdk     %s\n' "$(gradle_extra androidCompileSdkVersion)"
  printf '  targetSdk      %s\n' "$(gradle_extra androidTargetSdkVersion)"
  printf '  minSdk         %s\n' "$(gradle_extra androidMinSdkVersion)"
  printf '  buildTools     %s\n' "$(gradle_extra androidBuildToolsVersion)"
  printf '  ndk            %s\n' "$(gradle_extra androidCompileNdkVersion)"
  printf '  java (on PATH) %s\n' "$(java -version 2>&1 | head -1)"
  echo
  echo "Artifacts (sha256)"
  echo "------------------"
  for z in "${ZIPS[@]}"; do
    printf '  %s  %s\n' "$(sha256sum "$z" | cut -d' ' -f1)" "$(basename "$z")"
  done
  if [ -n "$PAYLOAD_LINES" ]; then
    echo
    printf 'Shared payload (sha256, read from %s)\n' "$(basename "${ZIPS[0]}")"
    echo "-------------------------------------------------------------------"
    printf '%s' "$PAYLOAD_LINES"
    echo "  (both flavors must carry identical copies of these; scripts/verify-parity.sh asserts it)"
  fi
  if [ -n "$CERT_LINES" ]; then
    echo
    echo "Signing certificate of manager.apk (read from the apk, never from a keystore)"
    echo "----------------------------------------------------------------------------"
    printf '%s\n' "$CERT_LINES"
  fi
  echo
  echo "Verify the artifacts with:"
  printf '  sha256sum -c %s\n' "$STEM.sha256"
} > "$MANIFEST"

# Companion file in sha256sum's own format: the manifest above is for reading, this one is for
# `sha256sum -c`, which refuses to parse prose.
: > "$CHECKSUMS"
for z in "${ZIPS[@]}"; do
  (cd "$RELEASE_DIR" && sha256sum "$(basename "$z")") >> "$CHECKSUMS"
done

echo "wrote $MANIFEST"
echo "wrote $CHECKSUMS"
