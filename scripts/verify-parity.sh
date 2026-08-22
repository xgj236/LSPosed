#!/usr/bin/env bash
#
# Verify that the Riru and Zygisk zips are the same module, differing only where
# the loader API forces them to.
#
# The two flavors share everything above the injection layer: the same manager
# app, the same daemon, the same lspd-cli, the same install scripts. Only the
# native loader and a few flavor tokens legitimately differ. It is easy to break
# that by rebuilding one flavor after an edit and shipping the other from an
# older build, which is exactly the drift this script catches.
#
# Usage:
#   scripts/verify-parity.sh [debug|release]
#
# Exit codes: 0 = in parity, 1 = drift found, 2 = usage/missing artifacts.
#
set -uo pipefail

BUILD_TYPE="${1:-debug}"
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

if [ ${#RIRU_ZIPS[@]} -eq 0 ] || [ ${#ZYGISK_ZIPS[@]} -eq 0 ]; then
  echo "error: need both riru and zygisk $BUILD_TYPE zips in magisk-loader/release/" >&2
  echo "hint: scripts/build.sh $BUILD_TYPE" >&2
  exit 2
fi
if [ ${#RIRU_ZIPS[@]} -gt 1 ] || [ ${#ZYGISK_ZIPS[@]} -gt 1 ]; then
  echo "error: multiple $BUILD_TYPE zips present; remove stale versions first" >&2
  printf '  %s\n' "${RIRU_ZIPS[@]}" "${ZYGISK_ZIPS[@]}" >&2
  exit 2
fi

RIRU_ZIP="${RIRU_ZIPS[0]}"
ZYGISK_ZIP="${ZYGISK_ZIPS[0]}"

command -v unzip >/dev/null 2>&1 || { echo "error: 'unzip' not found in PATH" >&2; exit 2; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -qo "$RIRU_ZIP" -d "$WORK/riru" || { echo "error: cannot read $RIRU_ZIP" >&2; exit 2; }
unzip -qo "$ZYGISK_ZIP" -d "$WORK/zygisk" || { echo "error: cannot read $ZYGISK_ZIP" >&2; exit 2; }

FAILED=0
pass() { printf '  \033[32mok\033[0m    %s\n' "$*"; }
fail() {
  printf '  \033[31mFAIL\033[0m  %s\n' "$*"
  FAILED=1
}
section() { printf '\n\033[1m%s\033[0m\n' "$*"; }

echo "Riru:   $(basename "$RIRU_ZIP")"
echo "Zygisk: $(basename "$ZYGISK_ZIP")"

# --- File inventory --------------------------------------------------------
#
# riru.sh is the installer's Riru version gate and has no Zygisk counterpart;
# every other entry must exist in both.

section "File inventory"
list_of() { (cd "$WORK/$1" && find . -type f ! -name '*.sha256' | sort); }
ONLY_RIRU="$(comm -23 <(list_of riru) <(list_of zygisk))"
ONLY_ZYGISK="$(comm -13 <(list_of riru) <(list_of zygisk))"

if [ "$ONLY_RIRU" = "./riru.sh" ]; then
  pass "riru-only files are exactly ./riru.sh"
elif [ -z "$ONLY_RIRU" ]; then
  fail "riru.sh missing from the Riru zip"
else
  fail "unexpected riru-only files:"
  printf '        %s\n' $ONLY_RIRU
fi

if [ -z "$ONLY_ZYGISK" ]; then
  pass "no zygisk-only files"
else
  fail "unexpected zygisk-only files:"
  printf '        %s\n' $ONLY_ZYGISK
fi

# --- Shared payload --------------------------------------------------------
#
# These must be byte-identical. manager.apk and daemon.apk come from the
# flavor-agnostic :app and :daemon modules, so any difference means the two zips
# were built from different source states — the drift that shipped a watch
# layout in one zip and not the other.

section "Shared payload is byte-identical"
for f in manager.apk daemon.apk lspd-cli sepolicy.rule post-fs-data.sh \
  service.sh uninstall.sh util_functions.sh verify.sh system.prop \
  META-INF/com/google/android/update-binary \
  META-INF/com/google/android/updater-script; do
  if [ ! -f "$WORK/riru/$f" ] && [ ! -f "$WORK/zygisk/$f" ]; then
    fail "$f absent from both zips"
  elif cmp -s "$WORK/riru/$f" "$WORK/zygisk/$f"; then
    pass "$f"
  else
    fail "$f differs between flavors"
  fi
done

# --- Feature markers -------------------------------------------------------
#
# Checked independently of the byte comparison so a failure names the missing
# feature rather than just reporting an apk hash mismatch.

section "Feature markers present in both flavors"
check_marker() { # <label> <file> <grep-args...>
  local label="$1" file="$2"
  shift 2
  local missing=""
  for f in riru zygisk; do
    if [ ! -f "$WORK/$f/$file" ] || ! grep -qa "$@" "$WORK/$f/$file" 2>/dev/null; then
      missing="$missing $f"
    fi
  done
  if [ -z "$missing" ]; then
    pass "$label"
  else
    fail "$label missing in:$missing"
  fi
}

check_marker "lspd-cli launcher invokes CliMain" lspd-cli "org.lsposed.lspd.cli.CliMain"
check_marker "customize.sh extracts lspd-cli" customize.sh "lspd-cli"
check_marker "sepolicy allows the control socket" sepolicy.rule "unix_stream_socket connectto"

# Look inside the apks too: the byte comparison above proves the two flavors
# agree, but not that either one actually contains the features. A build from a
# source tree missing the CLI would pass an identity check and fail here.
# Locate aapt2 for the resource-table check below. Release builds run
# `aapt2 optimize --collapse-resource-names`, which rewrites res/ paths to short
# names like res/sk.xml, so the watch layout cannot be found by filename — only
# the resource table still records its `watch` qualifier.
AAPT2=""
for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | tr -d '\r')"; do
  [ -n "$base" ] || continue
  cand="$(find "$base/build-tools" -maxdepth 2 -name 'aapt2*' -type f 2>/dev/null | sort -r | head -1)"
  if [ -n "$cand" ]; then AAPT2="$cand"; break; fi
done

missing_cli=""
missing_watch=""
for f in riru zygisk; do
  # Count matches rather than using `grep -q`: -q exits on the first hit, which
  # SIGPIPEs the feeding unzip, and under `set -o pipefail` that turns a
  # successful match into pipeline exit 141. grep -c drains the stream instead.
  n_cli="$(unzip -p "$WORK/$f/daemon.apk" 'classes*.dex' 2>/dev/null | grep -ac "lspd_ctl" || true)"
  [ "${n_cli:-0}" -gt 0 ] || missing_cli="$missing_cli $f"

  if [ -n "$AAPT2" ]; then
    # Count entries carrying the `watch` qualifier in the resource table.
    #
    # Release builds cannot be checked by name: aapt2 optimize
    # --collapse-resource-names rewrites res/ paths to short names AND replaces
    # every resource name with `0_resource_name_obfuscated`, so neither
    # "layout-watch" nor "activity_main" appears anywhere. The configuration
    # qualifier is the only thing that survives.
    #
    # What this proves is "packaged", NOT "selected". The `watch` qualifier is
    # matched against Configuration.uiMode == UI_MODE_TYPE_WATCH, which is not the
    # same thing as the android.hardware.type.watch feature: the reference device
    # declares the feature yet reports uiMode NORMAL, so it inflates the phone
    # layout and every -watch resource is dead code there. The watch adaptation
    # that actually runs is ThemeOverlay.Watch plus BaseActivity.isWatch()
    # branches -- see docs/WATCH_ADAPTATION.md. Keep the assertion (a build that
    # drops the qualified resources is still a build that drifted), but do not
    # read a pass here as evidence that anything watch-specific reached a screen.
    n_watch="$("$AAPT2" dump resources "$WORK/$f/manager.apk" 2>/dev/null |
      grep -c "^ *(watch)" || true)"
  else
    # No aapt2: fall back to the filename, which only holds for debug builds.
    n_watch="$(unzip -l "$WORK/$f/manager.apk" 2>/dev/null | grep -c "layout-watch.*/activity_main\.xml" || true)"
  fi
  [ "${n_watch:-0}" -gt 0 ] || missing_watch="$missing_watch $f"
done
[ -z "$missing_cli" ] && pass "daemon.apk carries the lspd_ctl control socket" \
  || fail "daemon.apk missing the lspd_ctl socket in:$missing_cli"
[ -z "$missing_watch" ] && pass "manager.apk carries watch-qualified resources" \
  || fail "manager.apk has no watch-qualified resources in:$missing_watch"

# --- Flavor-specific differences -------------------------------------------
#
# These files are *expected* to differ, and only in their flavor token. Asserting
# the expected value catches a mis-templated build (e.g. a Zygisk zip that
# installs itself as riru_lsposed).

section "Flavor tokens are correct"
expect_token() { # <file> <riru-expected> <zygisk-expected>
  local file="$1" want_riru="$2" want_zygisk="$3"
  local got_riru got_zygisk
  got_riru="$(grep -m1 -o "$want_riru" "$WORK/riru/$file" 2>/dev/null || true)"
  got_zygisk="$(grep -m1 -o "$want_zygisk" "$WORK/zygisk/$file" 2>/dev/null || true)"
  if [ "$got_riru" = "$want_riru" ] && [ "$got_zygisk" = "$want_zygisk" ]; then
    pass "$file: $want_riru / $want_zygisk"
  else
    fail "$file: expected '$want_riru' and '$want_zygisk', got '${got_riru:-none}' and '${got_zygisk:-none}'"
  fi
}

expect_token customize.sh "FLAVOR=riru" "FLAVOR=zygisk"
expect_token daemon "flavor=riru" "flavor=zygisk"
expect_token module.prop "id=riru_lsposed" "id=zygisk_lsposed"

# --- Version identity ------------------------------------------------------
#
# One root build.gradle.kts feeds verName/verCode to the manager apk, the daemon apk, the zip
# filename and module.prop, so the build has a single source of truth. Nothing proved it stayed
# single in the *artifacts*, though: a zip assembled over a stale intermediate can carry an apk
# from a different version, and this fork shipped six builds that all claimed 1.9.2 (7024) and
# were therefore indistinguishable to Android and to package tooling alike.

section "Version identity is consistent"

# LSPosed-v<verName>-<verCode>-<flavor>-<buildType>.zip, parsed without regex escapes.
zip_ver() {
  local b="${1##*/}"
  b="${b#LSPosed-v}"
  b="${b%-*-*.zip}"
  # Print nothing unless the -<code> tail actually survived, so callers can tell a failed parse
  # from a version rather than comparing against a half-stripped filename.
  case "$b" in
  *-*) printf '%s' "$b" ;;
  esac
}
RIRU_VER="$(zip_ver "$RIRU_ZIP")"
ZYGISK_VER="$(zip_ver "$ZYGISK_ZIP")"

if [ -z "$RIRU_VER" ] || [ -z "$ZYGISK_VER" ]; then
  fail "cannot parse a version out of the zip filenames"
elif [ "$RIRU_VER" = "$ZYGISK_VER" ]; then
  pass "zip filenames agree: v${RIRU_VER%-*} (${RIRU_VER##*-})"
else
  fail "zip filenames disagree: '$RIRU_VER' vs '$ZYGISK_VER'"
fi

# module.prop is templated per flavor, so the byte-identity section above does not cover it. Each
# flavor is checked against its *own* filename rather than against one shared expectation: a zip
# whose name and payload disagree is the defect, and blaming the other flavor's filename for it
# would point at the wrong artifact.
prop_of() { # <riru|zygisk> <key>
  local line
  line="$(grep -m1 "^$2=" "$WORK/$1/module.prop" 2>/dev/null | tr -d "\r")"
  printf '%s' "${line#*=}"
}
check_prop() { # <riru|zygisk> <verName-verCode>
  local f="$1" want_name="${2%-*}" want_code="${2##*-}"
  local got_code got_ver
  got_code="$(prop_of "$f" versionCode)"
  got_ver="$(prop_of "$f" version)"
  if [ "$got_code" = "$want_code" ] && [ "$got_ver" = "v$want_name ($want_code)" ]; then
    pass "$f module.prop matches its filename: $got_ver"
  else
    fail "$f module.prop reports '${got_ver:-none}' / '${got_code:-none}', filename says 'v$want_name ($want_code)'"
  fi
}
if [ -n "$RIRU_VER" ]; then check_prop riru "$RIRU_VER"; fi
if [ -n "$ZYGISK_VER" ]; then check_prop zygisk "$ZYGISK_VER"; fi

# Pull a field out of an aapt2 badging line by word-splitting, so no regex escaping is needed.
badging_field() { # <badging-line> <field-name>
  local tok
  for tok in $1; do
    case "$tok" in
    "$2="*)
      printf '%s' "${tok#*=}" | tr -d "'"
      return
      ;;
    esac
  done
}

if [ -z "$RIRU_VER" ]; then
  : # Nothing to compare the apks against.
elif [ -n "$AAPT2" ]; then
  # manager.apk and daemon.apk are byte-identical across flavors (asserted above), so one copy of
  # each is enough. This is the check that catches a zip repacked around a stale apk: the filename
  # and module.prop are both templated from the same Gradle run, so they cannot disagree on their
  # own -- the apk inside is the part that can come from a different build.
  want_name="${RIRU_VER%-*}"
  want_code="${RIRU_VER##*-}"
  for apk in manager.apk daemon.apk; do
    line="$("$AAPT2" dump badging "$WORK/riru/$apk" 2>/dev/null | head -1)"
    got_code="$(badging_field "$line" versionCode)"
    got_name="$(badging_field "$line" versionName)"
    if [ "$got_code" = "$want_code" ] && [ "$got_name" = "$want_name" ]; then
      pass "$apk: $got_name ($got_code)"
    else
      fail "$apk reports '${got_name:-none}' (${got_code:-none}), expected '$want_name' ($want_code)"
    fi
  done
else
  # Not a failure, but not silence either: an unrun check must not read like a passed one.
  printf '  note  aapt2 not found; apk version metadata not checked\n'
fi

# The native loader is built per-flavor against a different injection API, so it
# must differ; identical loaders would mean a flavor was built with the wrong API.
section "Native loader is flavor-specific"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  so="lib/$abi/liblspd.so"
  if [ ! -f "$WORK/riru/$so" ] || [ ! -f "$WORK/zygisk/$so" ]; then
    fail "$so missing from one or both zips"
  elif cmp -s "$WORK/riru/$so" "$WORK/zygisk/$so"; then
    fail "$so is identical across flavors (wrong -DAPI?)"
  else
    pass "$so differs as expected"
  fi
done

section "Result"
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32mRiru and Zygisk are in parity.\033[0m\n'
else
  printf '\033[31mParity check failed. Rebuild both flavors together:\033[0m\n'
  printf '  scripts/build.sh %s\n' "$BUILD_TYPE"
fi
exit "$FAILED"
