#!/usr/bin/env bash
# 开机自愈回归：重启电视，核对「是否直接回到星川」「守护与屏保是否自动恢复」。
#
#   TV=192.168.0.24:5555 tools/boot-check.sh        # 跑 1 轮
#   TV=192.168.0.24:5555 tools/boot-check.sh 3      # 跑 3 轮
#
# 会重启电视，跑之前先确认用户同意。每轮都记录原始读数，最后给一张结论表。
# 判定标准（对应 v1.3.3 的开机自愈）：
#   1) 开机后 mCurrentFocus / mResumedActivity 都是 app.xingchuan.tv
#   2) enabled_accessibility_services 含 GuardService 且 accessibility_enabled=1
#   3) screensaver_components 是星川、screensaver_enabled=1、screen_off_timeout=180000
#   4) 按一次 KEYCODE_HOME 后仍停在星川（守护接管）
set -uo pipefail

TV="${TV:?请指定电视 IP，例如 TV=192.168.0.24:5555 tools/boot-check.sh}"
ROUNDS="${1:-1}"
PORT="${PORT:-5555}"
PKG="app.xingchuan.tv"
case "$TV" in
  *:*) SERIAL="$TV" ;;
  *) SERIAL="$TV:$PORT" ;;
esac
SETTLE="${SETTLE:-30}"

if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
  elif [ -x "/d/android-toolchain/android-sdk/platform-tools/adb.exe" ]; then
    ADB="/d/android-toolchain/android-sdk/platform-tools/adb.exe"
  else ADB="adb"; fi
fi

sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

connect() {
  "$ADB" start-server >/dev/null 2>&1
  for _ in $(seq 1 25); do
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
    [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = "device" ] && return 0
    sleep 3
  done
  return 1
}

wait_boot() {
  # 先等它真的重启（uptime 掉下去），再等 boot_completed=1
  local up
  for _ in $(seq 1 40); do
    sleep 5
    "$ADB" connect "$SERIAL" >/dev/null 2>&1
    up="$(sh_ "cat /proc/uptime" | awk '{print int($1)}')"
    if [ -n "$up" ] && [ "$up" -lt 120 ] && [ "$(sh_ getprop sys.boot_completed)" = "1" ]; then
      echo "   重启完成，uptime=${up}s"
      return 0
    fi
  done
  return 1
}

ok=0; bad=0
declare -a LINES

for r in $(seq 1 "$ROUNDS"); do
  echo
  echo "===== 第 $r/$ROUNDS 轮 ====="
  connect || { echo "连不上 $SERIAL，终止"; exit 1; }

  echo "[重启前]"
  echo "   守护: $(sh_ settings get secure enabled_accessibility_services)"
  echo "   屏保: $(sh_ settings get secure screensaver_components) enabled=$(sh_ settings get secure screensaver_enabled)"
  echo "   前台: $(sh_ "dumpsys window | grep mCurrentFocus" | sed 's/.*mCurrentFocus=//')"

  sh_ reboot >/dev/null 2>&1
  echo "[已发送重启]"
  wait_boot || { echo "等不到开机完成，终止"; exit 1; }
  echo "   静置 ${SETTLE}s 让固件把所有开机流程走完…"
  sleep "$SETTLE"
  connect || { echo "开机后又连不上，终止"; exit 1; }

  focus="$(sh_ "dumpsys window | grep mCurrentFocus" | sed 's/.*mCurrentFocus=//')"
  resumed="$(sh_ "dumpsys activity activities | grep -m1 mResumedActivity" | sed 's/.*mResumedActivity: //')"
  guard="$(sh_ settings get secure enabled_accessibility_services)"
  acc="$(sh_ settings get secure accessibility_enabled)"
  saver_cmp="$(sh_ settings get secure screensaver_components)"
  saver_on="$(sh_ settings get secure screensaver_enabled)"
  timeout="$(sh_ settings get system screen_off_timeout)"

  sh_ input keyevent KEYCODE_HOME >/dev/null 2>&1
  sleep 3
  focus2="$(sh_ "dumpsys window | grep mCurrentFocus" | sed 's/.*mCurrentFocus=//')"

  v_focus=✗; case "$focus$resumed" in *"$PKG"*"$PKG"*) v_focus=✓ ;; esac
  v_guard=✗; case "$guard" in *GuardService*) [ "$acc" = "1" ] && v_guard=✓ ;; esac
  v_saver=✗; case "$saver_cmp" in *"$PKG"*) [ "$saver_on" = "1" ] && [ "$timeout" = "180000" ] && v_saver=✓ ;; esac
  v_home=✗; case "$focus2" in *"$PKG"*) v_home=✓ ;; esac

  echo "[开机后]"
  echo "   前台: $focus"
  echo "   后台栈顶: $resumed"
  echo "   守护: $guard (accessibility_enabled=$acc)"
  echo "   屏保: $saver_cmp enabled=$saver_on timeout=$timeout"
  echo "   按主页键后: $focus2"
  echo "   判定: 前台$v_focus 守护$v_guard 屏保$v_saver 主页键$v_home"

  if [ "$v_focus$v_guard$v_saver$v_home" = "✓✓✓✓" ]; then
    ok=$((ok+1)); LINES+=("第 $r 轮: 通过")
  else
    bad=$((bad+1)); LINES+=("第 $r 轮: 失败（前台$v_focus 守护$v_guard 屏保$v_saver 主页键$v_home）")
  fi
done

echo
echo "===== 汇总（$ROUNDS 轮）====="
for l in "${LINES[@]}"; do echo "  $l"; done
echo "  通过 $ok 轮 / 失败 $bad 轮"
[ "$bad" = "0" ]
