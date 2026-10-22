#!/bin/bash
#滚子大厅 · 环境自检脚本
# 用法：bash tools/env-check.sh（在项目根目录执行）
set -u

OK="✅"
WARN="⚠️"
ERR="❌"
pass=0
warn=0
fail=0
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

row() {
  printf "  %-24s %s\n" "$1" "$2"
}
addp() { pass=$((pass + 1)); }
addw() { warn=$((warn + 1)); }
addf() { fail=$((fail + 1)); }

echo "=========================================="
echo " 滚子大厅 · 环境自检"
echo "=========================================="

# ---- 1. JDK ----
echo ""
echo "[1/7] JDK"
if command -v java >/dev/null 2>&1; then
  JV="$(java -version 2>&1 | head -1)"
  JARCH="$(file "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)" 2>/dev/null | grep -oE 'arm64|x86_64' | head -1)"
  [ -z "$JARCH" ] && JARCH="unknown"
  if echo "$JV" | grep -q "17"; then
    row "版本" "$OK $JV"
    addp
  else
    row "版本" "$WARN ${JV}（本项目需 JDK 17）"
    addw
  fi
  if [ "$JARCH" = "arm64" ] || [ "$(uname -m)" = "arm64" ]; then
    row "架构" "$OK $JARCH"
  else
    row "架构" "$WARN $JARCH —— macOS 系统 JDK 21 常是 x86_64 坏件，建议装 arm64 JDK 17"
  fi
else
  row "JDK" "$ERR 未安装（需 arm64 JDK 17）"
  addf
fi

# ---- 2. Maven ----
echo ""
echo "[2/7] Maven"
if command -v mvn >/dev/null 2>&1; then
  row "mvn" "$OK $(mvn -v 2>/dev/null | head -1)"
  addp
else
  row "mvn" "$WARN 未在 PATH（macOS 建议用绝对路径或加入 PATH）"
  addw
fi

# ---- 3. Cocos Creator ----
echo ""
echo "[3/7] Cocos Creator"
cocos_found=0
for p in \
  "/Applications/Cocos/Creator/3.8.8/CocosCreator.app/Contents/MacOS/CocosCreator" \
  "/Applications/CocosCreator/Creator/3.8.8/CocosCreator.app/Contents/MacOS/CocosCreator"; do
  if [ -f "$p" ]; then
    row "3.8.8" "$OK 已安装"
    cocos_found=1
    break
  fi
done
if [ "$cocos_found" -eq 0 ]; then
  if ls -d /Applications/Cocos/Creator/*/ >/dev/null 2>&1; then
    row "Cocos" "$WARN 有其他版本，本项目固定用 3.8.8"
  else
    row "Cocos" "$WARN 未安装（需 Cocos Creator 3.8.8）"
  fi
  addw
else
  addp
fi

# ---- 4. 微信开发者工具 ----
echo ""
echo "[4/7] 微信开发者工具"
if [ -e "/Applications/wechatwebdevtools.app" ]; then
  row "安装" "$OK 已安装"
  addp
else
  row "安装" "$WARN 未检测到（需 wechatwebdevtools）"
  addw
fi

# ---- 5. 项目结构 ----
echo ""
echo "[5/7] 项目结构"
for d in server/game-domain server/game-room client/assets/scripts/ui client/assets/scripts/net; do
  if [ -d "$ROOT/$d" ]; then
    row "$d" "$OK"
    addp
  else
    row "$d" "$WARN 缺失"
    addw
  fi
done
if [ -f "$ROOT/client/assets/scripts/ui/TableUI.ts" ]; then
  tl="$(wc -l < "$ROOT/client/assets/scripts/ui/TableUI.ts" | tr -d ' ')"
  row "TableUI.ts" "$OK ui/TableUI.ts（$tl 行）"
  addp
elif [ -f "$ROOT/client/assets/scripts/TableUI.ts" ]; then
  row "TableUI.ts" "$WARN 在旧路径 scripts/（已重构到 ui/ 子目录）"
  addw
else
  row "TableUI.ts" "$ERR 未找到"
  addf
fi

# ---- 6. 构建产物 ----
echo ""
echo "[6/7] 构建产物（build/wechatgame）"
OUT="$ROOT/client/build/wechatgame"
if [ -d "$OUT" ]; then
  if [ -f "$OUT/game.json" ]; then
    n="$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))))" "$OUT/game.json" 2>/dev/null || echo 0)"
    if [ "$n" = "6" ]; then
      row "game.json" "$OK 顶层 6 字段（铁律合规）"
      addp
    else
      row "game.json" "$ERR 顶层 $n 个字段（铁律：只能 6 个）"
      addf
    fi
  else
    row "game.json" "$WARN 不存在（需先构建）"
    addw
  fi

  upper="$(find "$OUT" -name '*.meta' 2>/dev/null | grep -cE '[A-Z]')"
  if [ "$upper" = "0" ]; then
    row "uuid 小写" "$OK"
    addp
  else
    row "uuid 小写" "$ERR $upper 个文件含大写（微信端会黑屏）"
    addf
  fi

  size_k="$(du -sk "$OUT" 2>/dev/null | cut -f1)"
  if [ -n "$size_k" ] && [ "$size_k" -lt 4096 ]; then
    row "包体" "$OK ${size_k}KB（上限 4096KB）"
    addp
  else
    row "包体" "$WARN ${size_k}KB 接近或超过 4MB 上限"
    addw
  fi

  myip=""
  for i in en0 en1 en2; do
    r="$(ipconfig getifaddr "$i" 2>/dev/null)"
    if [ -n "$r" ]; then myip="$r"; break; fi
  done
  if [ -n "$myip" ]; then
    if grep -q "$myip" "$OUT/assets/main/index.js" 2>/dev/null; then
      row "serverUrl IP" "$OK ${myip}（与本机一致）"
      addp
    else
      row "serverUrl IP" "$WARN 产物 IP 与本机（${myip}）不一致，需重跑构建脚本"
      addw
    fi
  fi
else
  row "构建产物" "$WARN 尚未构建（跑 build-wechatgame）"
  addw
fi

# ---- 7. 战斗服端口 ----
echo ""
echo "[7/7] 战斗服端口 8080"
if command -v python3 >/dev/null 2>&1; then
  if python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', 8080)) == 0 else 1)
" 2>/dev/null; then
    row "8080" "$OK 正在监听（战斗服已启动）"
    addp
  else
    row "8080" "$WARN 未监听（真机调试前需启动 ServerMain）"
    addw
  fi
else
  row "8080" "$WARN 无 python3，跳过检测"
fi

# ---- 汇总 ----
echo ""
echo "=========================================="
echo " 通过 ${pass} 项 / 警告 ${warn} 项 / 失败 ${fail} 项"
echo "=========================================="
if [ "$fail" -gt 0 ]; then
  echo ""
  echo "有失败项，请对照《新人接入指南》第 6 章处理。"
fi
echo ""
echo "下一步："
echo "  1. 起战斗服：cd server && mvn exec:java -Dexec.mainClass=com.gunzihall.room.ServerMain"
echo "  2. 构建客户端：cd client && ./build-wechatgame.sh"
echo "  3. 微信开发者工具导入 client/build/wechatgame，点「真机调试」扫码"