#!/system/bin/sh
# Drives the parasitic manager's UI over adb, for the manual checks in docs/TESTING.md.
#
# Runs *on the device*, not on the host: the nested quoting needed to do this as a one-line
# `adb shell su -c '...'` breaks on the $(...) inside, which is what made the UI pass
# unrepeatable before this file existed.
#
#   adb push scripts/uitest.sh /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/uitest.sh
#   adb shell su -c '/data/local/tmp/uitest.sh show home'      # front the manager, screenshot
#   adb shell su -c '/data/local/tmp/uitest.sh tap 208 300 detail'
#   adb shell su -c '/data/local/tmp/uitest.sh swipe 208 400 208 150 400 scrolled'
#   adb shell su -c '/data/local/tmp/uitest.sh dump'           # uiautomator XML
#   adb pull /data/local/tmp/home.png                          # never redirect screencap
#                                                              # through PowerShell; see
#                                                              # scripts/capture-screen.ps1
# Every subcommand re-unlocks and re-fronts the manager first, so a call is safe at any time.
#
# Two ROM quirks drive this script's shape:
#  1. KEYCODE_WAKEUP pulls com.xtc.i3launcher to the front even when the display is
#     already on, so it is never used. Touch events keep the screen alive instead.
#  2. screen_off_timeout is forced back to 3000 ms by the ROM, so every invocation
#     re-unlocks and re-fronts the manager before acting.
focus() { dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; }
LAUNCH='am start -a android.intent.action.MAIN -c org.lsposed.manager.LAUNCH_MANAGER -n com.android.shell/.BugreportWarningActivity'

ensure() {
  case "$(focus)" in *Keyguard*) input swipe 208 440 208 60 300; sleep 1 ;; esac
  case "$(focus)" in
    *lsposed.manager*) ;;
    *) $LAUNCH >/dev/null 2>&1; sleep 2 ;;
  esac
}

shot() { screencap -p "/data/local/tmp/$1.png"; chmod 666 "/data/local/tmp/$1.png"
         echo "shot/$1: $(ls -l /data/local/tmp/$1.png | awk '{print $5}') bytes"; }

cmd=$1; shift
ensure
case "$cmd" in
  show)  ;;
  tap)   input tap "$1" "$2"; sleep 1; shift 2 ;;
  swipe) input swipe "$1" "$2" "$3" "$4" "${5:-300}"; sleep 1; shift 4; [ $# -gt 0 ] && shift ;;
  back)  input keyevent KEYCODE_BACK; sleep 1 ;;
  dump)  uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; chmod 666 /data/local/tmp/ui.xml ;;
esac
[ -n "$1" ] && shot "$1"
echo "focus: $(focus)"
