#!/usr/bin/env bash
# 把 starriver/site 的静态页同步到智能家居后台的 public/xingchuan/，
# 之后在那个工作区执行一次 sites 发布，官网地址就是：
#   https://smart-home-hub-07039.app.workbuddy.host/xingchuan/?sharecode=OVUS5VJB42
#
# 只复制 index.html 与 shots/；.edgeone/ .env 属于 EdgeOne 部署配置，不带走。
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$HERE/site"
DST="${1:-/d/WorkBuddy/2026-11-03-14-24-22/smart-home-backend/public/xingchuan}"

[ -f "$SRC/index.html" ] || { echo "找不到 $SRC/index.html"; exit 1; }
mkdir -p "$DST"
cp -f "$SRC/index.html" "$DST/index.html"
rm -rf "$DST/shots"
cp -r "$SRC/shots" "$DST/shots"

echo "已同步到 $DST"
echo "下一步：在 smart-home-backend 工作区发布 sites，然后访问"
echo "  https://smart-home-hub-07039.app.workbuddy.host/xingchuan/?sharecode=OVUS5VJB42"
