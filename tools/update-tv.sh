#!/usr/bin/env bash
# 一条命令把当前代码更新到电视上：构建 → 覆盖安装 → 校验版本与前台。
#
#   TV=192.168.0.2 tools/update-tv.sh              # 构建并安装 standard
#   TV=192.168.0.2 FORM=guard tools/update-tv.sh   # 装 guard 形态
#   TV=192.168.0.2 SKIP_BUILD=1 tools/update-tv.sh # 只装现成 APK
#
# 这个脚本只做「构建 + 覆盖安装（install -r，保留数据）+ 读状态校验」。
# 不会卸载、不会清数据、不会停用原厂桌面、不会重启/待机/关机。
#
# 电视离线时：先开机（小爱说「打开电视」，或按遥控器电源键），等屏幕亮起再重跑。
set -uo pipefail

TV="${TV:?请指定电视 IP，例如 TV=192.168.0.2 tools/update-tv.sh}"
PORT="${PORT:-5555}"
FORM="${FORM:-standard}"
PKG="app.xingchuan.tv"
ACTIVITY="$PKG/.MainActivity"
SERIAL="$TV:$PORT"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_TYPE="$(echo "$FORM" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
APK="$ROOT/app/build/outputs/apk/$FORM/release/app-$FORM-release.apk"

# ---- adb 定位：优先环境变量，其次 PATH，最后本机工具链默认位置 ----
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"
  elif [ -x "/d/android-toolchain/android-sdk/platform-tools/adb.exe" ]; then
    ADB="/d/android-toolchain/android-sdk/platform-tools/adb.exe"
  else ADB="adb"; fi
fi

say() { printf '\n== %s ==\n' "$*"; }

# ---- 1. 连接：最多等 60 秒，期间提示去开机 ----
say "连接 $SERIAL"
"$ADB" start-server >/dev/null 2>&1
"$ADB" connect "$SERIAL" 2>&1 | tail -1
ready=0
for i in $(seq 1 20); do
  if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = "device" ]; then ready=1; break; fi
  [ "$i" = "1" ] && echo "等待设备响应；若电视是关着的，用「小爱同学，打开电视」或遥控器电源键唤醒…"
  sleep 3
done
if [ "$ready" != "1" ]; then
  echo "仍连不上 $SERIAL。检查：电视是否已开机、和电脑是否同一网段、电视的 ADB 调试是否还开着。"
  exit 1
fi

# ---- 2. 构建（默认构建，SKIP_BUILD=1 跳过）----
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  say "构建 $FORM"
  ( cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-D:/android-toolchain/jdk17}" ./gradlew "assemble${BUILD_TYPE}Release" -q 2>&1 | tail -6 )
  st=${PIPESTATUS[0]}
  [ "$st" != "0" ] && { echo "构建失败（exit $st），未安装。"; exit 1; }
fi

if [ ! -f "$APK" ]; then
  echo "找不到 $APK"
  exit 1
fi
printf 'APK: %s  (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"

# ---- 3. 覆盖安装（保留数据与签名）----
say "安装（install -r，保留数据）"
"$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -3

# ---- 4. 校验：版本号 / 前台 / 守护开关 ----
say "校验"
VER="$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r')"
echo "版本：$VER"

FG="$("$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | tr -d '\r')"
case "$FG" in
  *"$PKG"*) echo "前台：星川 ✓" ;;
  *) echo "前台：不是星川（$FG）"
     echo "正在拉起一次…"
     "$ADB" -s "$SERIAL" shell am start -n "$ACTIVITY" >/dev/null 2>&1
     sleep 3
     FG="$("$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | tr -d '\r')"
     case "$FG" in *"$PKG"*) echo "前台：星川 ✓" ;; *) echo "前台：仍不是星川 ✗" ;; esac ;;
esac

GUARD="$("$ADB" -s "$SERIAL" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
ACC="$("$ADB" -s "$SERIAL" shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')"
case "$GUARD" in
  *GuardService*) echo "守护：已绑定（accessibility_enabled=$ACC）✓" ;;
  *) echo "守护：未绑定 —— 若主页键会掉回原厂桌面，按 AGENTS.md 第 6 节开启" ;;
esac

echo
echo "完成。要看主页键是否生效：连按 3 次主页键，每次都应回到星川。"
