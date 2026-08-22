#!/system/bin/sh
# Drives lspd-cli against the daemon to verify the BUG-006 transport fixes, for the manual
# checks in docs/TESTING.md.
#
# Runs *on the device* for two reasons: the scope lists reach 131 KB, which is past what is
# comfortable to pass through `adb shell`, and the nested quoting needed to do this as a
# one-line `adb shell su -c '...'` breaks on the $(...) inside -- the same reason
# scripts/uitest.sh lives on the device.
#
#   adb push scripts/clitest.sh /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/clitest.sh
#   adb shell su -c '/data/local/tmp/clitest.sh'
#
# The four scope lists are generated here rather than shipped, so the only input is this file.
# Their lengths are deliberate; see docs/TESTING.md:
#   over512_short   513 targets /  15902 B  legal size, illegal count -> daemon must say 513 > 512
#   legal_max_argv  498 targets / 130973 B  largest legal request that fits in one argv string
#   worst_case_noid 512 targets / 131071 B  exactly MAX_ARG_STRLEN-1
#   over_byte_limit 600 targets / 157799 B  over the daemon limit -- and over the argv ceiling
CLI=/data/adb/modules/riru_lsposed/lspd-cli
D=/data/local/tmp/clitest
BOGUS=org.lsposed.nonexistent.test
MOD=${MOD:-com.AllToolBox.wear}
BURST=${BURST:-8}

mkdir -p $D/out
rm -f $D/out/*

gen() {   # gen <file> <count> <name-length> <uid-suffix>
  [ -s "$D/$1" ] && return
  awk -v n="$2" -v len="$3" -v uid="$4" 'BEGIN {
    for (i = 0; i < n; i++) {
      head = sprintf("com.t.%05d.", i)
      s = head
      for (j = length(head); j < len; j++) s = s "a"
      printf "%s%s%s", (i ? "," : ""), s, uid
    }
  }' > "$D/$1"
}

one() {   # one <label> <listfile>
  f=$D/$2
  n=$(wc -c < $f)
  sc=$(cat $f)
  out=$($CLI scope $BOGUS --scope "$sc" 2>&1)
  rc=$?
  echo "[$1] arg=$n rc=$rc"
  echo "$out" | head -2 | sed 's/^/      /'
}

burst() { # burst <n> <listfile>
  n=$1
  f=$D/$2
  sc=$(cat $f)
  i=0
  while [ $i -lt $n ]; do
    ( $CLI scope $BOGUS --scope "$sc" >$D/out/c$i.out 2>&1; echo $? >$D/out/c$i.rc ) &
    i=$((i+1))
  done
  wait
  echo "[burst n=$n arg=$(wc -c < $f)]"
  i=0
  while [ $i -lt $n ]; do
    echo "      c$i rc=$(cat $D/out/c$i.rc) :: $(head -1 $D/out/c$i.out)"
    i=$((i+1))
  done
  # -l alone: count the files that matched. (-l with -c cancel each other out and count
  # every file instead, which reads as "all 8 clients were reset" -- exactly backwards.)
  nb=$(grep -l "busy" $D/out/c*.out 2>/dev/null | wc -l)
  nr=$(grep -lE "reset by peer|Broken pipe" $D/out/c*.out 2>/dev/null | wc -l)
  echo "      busy=$nb  reset-or-pipe=$nr  (busy>0 and reset-or-pipe=0 is the pass)"
}

gen over512_short.txt   513 30
gen legal_max_argv.txt  498 255 /100000
gen worst_case_noid.txt 512 255
gen over_byte_limit.txt 600 255 /100000

echo "===== 1. the request byte limit no longer contradicts the 512-target limit ====="
one over512_short   over512_short.txt
one legal_max_argv  legal_max_argv.txt
one worst_case_noid worst_case_noid.txt

echo
echo "===== 2. argv ceiling: 600 targets cannot even be exec'd ====="
sc=$(cat $D/over_byte_limit.txt)
$CLI scope $BOGUS --scope "$sc" 2>&1 | head -2 | sed 's/^/      /'

echo
echo "===== 3. the busy reply must survive the close (was lost to RST on 7031) ====="
burst $BURST legal_max_argv.txt

echo
echo "===== 4. happy path ====="
echo "      ping :: $($CLI ping 2>&1)"
echo "      list :: $($CLI list 2>&1 | tr '\n' ' ')"

echo
echo "===== 5. idempotent re-scope of $MOD (BUG-002, restores itself) ====="
before=$($CLI getscope $MOD 2>&1 | grep -v '^OK' | tr '\n' ',' | sed 's/,$//')
echo "      before :: $before"
if [ -n "$before" ]; then
  echo "      re-add :: $($CLI scope $MOD --scope "$before" 2>&1 | head -1)"
  echo "      after  :: $($CLI getscope $MOD 2>&1 | grep -v '^OK' | tr '\n' ',' | sed 's/,$//')"
else
  echo "      skipped: no existing scope to re-add"
fi
echo
echo "===== done ====="
