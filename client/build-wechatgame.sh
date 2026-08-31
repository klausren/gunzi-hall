#!/bin/bash
# 一键构建微信小游戏（横屏 + 开发者域名校验关闭）
# 用法：./build-wechatgame.sh [appid]
#   appid 不传则用 touristappid（游客/测试号）；注册小游戏后传正式 appid
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
COCOS="/Applications/Cocos/Creator/3.8.8/CocosCreator.app/Contents/MacOS/CocosCreator"
APPID="${1:-touristappid}"
OUT="$PROJECT_DIR/build/wechatgame"

echo "==> Cocos 构建中（wechatgame）..."
env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS -u CODEBUDDY_NODE_BIN \
    "$COCOS" --no-sandbox --project "$PROJECT_DIR" \
    --build "platform=wechatgame" > /tmp/cocos-build-last.log 2>&1 || {
    # exit=36 是构建完成后的 worker 清理噪音，产物存在即视为成功
    if [ ! -f "$OUT/game.json" ]; then
        echo "构建失败，日志：/tmp/cocos-build-last.log"; exit 1
    fi
}

echo "==> 补丁 deviceOrientation=landscape"
python3 - "$OUT/game.json" <<'EOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["deviceOrientation"] = "landscape"
json.dump(d, open(p, "w"), indent=4, ensure_ascii=False)
EOF

echo "==> 补丁 project.config.json（appid=$APPID, urlCheck=false）"
python3 - "$OUT/project.config.json" "$APPID" <<'EOF'
import json, sys
p, appid = sys.argv[1], sys.argv[2]
d = json.load(open(p))
d["appid"] = appid
d.setdefault("setting", {})["urlCheck"] = False  # 开发期不校验合法域名（ws://局域网IP 需要）
json.dump(d, open(p, "w"), indent=4, ensure_ascii=False)
EOF

echo "==> 完成：$OUT"
echo "    下一步：微信开发者工具导入该目录 → 预览/真机调试"
