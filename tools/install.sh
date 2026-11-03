#!/usr/bin/env bash
# 把星川装到电视上，并把每一步的原始输出打出来。
#
#   TV=192.168.1.20 tools/install.sh             # standard 形态
#   TV=192.168.1.20 FORM=guard tools/install.sh  # 固件拒装 HOME 声明时
#
# 这个脚本不会重启、待机、关机、卸载、清空数据，也不会停用原厂桌面。
# 那些动作必须由人按 AGENTS.md 里的步骤确认后手动执行。
set -uo pipefail

TV="${TV:?请指定电视 IP，例如 TV=192.168.1.20 tools/install.sh}"
PORT="${PORT:-5555}"
FORM="${FORM:-standard}"
ADB="${ADB:-adb}"
SERIAL="$TV:$PORT"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/$FORM/release/app-$FORM-release.apk"

run() {
  echo "\$ adb -s $SERIAL $*"
  "$ADB" -s "$SERIAL" "$@" 2>&1
  echo
}

if [ ! -f "$APK" ]; then
  echo "找不到 $APK"
  echo "先构建：./gradlew assemble${FORM^}Release"
  exit 1
fi

echo "== 1. 连接 =="
"$ADB" connect "$SERIAL" 2>&1
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" devices 2>&1
echo

echo "== 2. 记录现场（请把下面这几行原样保存，回滚要用） =="
run shell getprop ro.build.version.sdk
run shell getprop ro.product.manufacturer
run shell getprop ro.product.model
run shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
run shell settings get secure screensaver_components
run shell settings get secure enabled_accessibility_services
run shell settings get secure enabled_notification_listeners

echo "== 3. 安装 $FORM 形态 =="
echo "\$ adb -s $SERIAL install -r $APK"
"$ADB" -s "$SERIAL" install -r "$APK" 2>&1
echo

echo "== 4. 设为默认桌面 =="
# 部分固件没有 cmd role，这一行报错可忽略
run shell cmd role add-role-holder --user 0 android.app.role.HOME app.xingchuan.tv
run shell cmd package set-home-activity app.xingchuan.tv/.MainActivity
run shell input keyevent KEYCODE_HOME
sleep 2

echo "== 5. 验证 =="
run shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
run shell settings get secure enabled_accessibility_services
run shell "dumpsys window | grep mCurrentFocus"

SHOT="$ROOT/xingchuan-check.png"
"$ADB" -s "$SERIAL" shell screencap -p /sdcard/xingchuan-check.png 2>&1
"$ADB" -s "$SERIAL" pull /sdcard/xingchuan-check.png "$SHOT" 2>&1
echo "截图：$SHOT（请自己看一眼再下结论）"
echo
echo "完成。若主页键仍回到原厂桌面：先确认第 4 步没有报错，再考虑开启桌面守护（AGENTS.md 第 6 节）。"
