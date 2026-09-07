#!/bin/bash
# 一键构建微信小游戏（横屏 + 开发者域名校验关闭）
# 用法：./build-wechatgame.sh [appid]
#   appid 不传则用 wxf88033b2ee303d71（已注册的小游戏 appid）
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
COCOS="/Applications/Cocos/Creator/3.8.8/CocosCreator.app/Contents/MacOS/CocosCreator"
APPID="${1:-wxf88033b2ee303d71}"
OUT="$PROJECT_DIR/build/wechatgame"

echo "==> 自动同步 serverUrl（本机局域网 IP，手机真机调试需要）"
/usr/bin/python3 - "$PROJECT_DIR" <<'PYEOF'
import os, re, subprocess, sys

root = sys.argv[1]

# 取本机局域网 IP；取不到就退回 localhost（纯模拟器调试场景）
ip = ""
for iface in ("en0", "en1", "en2"):
    try:
        out = subprocess.run(["ipconfig", "getifaddr", iface],
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        out = ""
    if out and re.match(r"^\d+\.\d+\.\d+\.\d+$", out):
        ip = out
        break
if not ip:
    ip = "localhost"

new_url = f"ws://{ip}:8080/ws"
targets = [
    "assets/scenes/main.scene",
    "assets/scripts/ui/TableUI.ts",
    "assets/scripts/NetTest.ts",
]
pat = re.compile(r"ws://[A-Za-z0-9._-]+:8080/ws")

print(f"  本机 IP: {ip}  →  serverUrl = {new_url}")
for rel in targets:
    p = os.path.join(root, rel)
    if not os.path.exists(p):
        print(f"  ⚠️  跳过（不存在）: {rel}")
        continue
    with open(p, encoding="utf-8") as f:
        src = f.read()
    new_src, n = pat.subn(new_url, src)
    if n == 0:
        print(f"  ⚠️  未找到 serverUrl: {rel}")
        continue
    if new_src != src:
        with open(p, "w", encoding="utf-8") as f:
            f.write(new_src)
    print(f"  ✅ {rel}（替换 {n} 处）")
PYEOF

echo "==> Cocos 构建中（wechatgame）..."
env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS -u CODEBUDDY_NODE_BIN \
    "$COCOS" --no-sandbox --project "$PROJECT_DIR" \
    --build "platform=wechatgame" > /tmp/cocos-build-last.log 2>&1 || {
    # exit=36 是构建完成后的 worker 清理噪音，产物存在即视为成功
    if [ ! -f "$OUT/game.json" ]; then
        echo "构建失败，日志：/tmp/cocos-build-last.log"; exit 1
    fi
}

echo "==> 覆写 game.json（6 字段：landscape + 状态栏 + 背景色 + pages + subpackages + 网络超时）"
python3 - "$OUT/game.json" <<'EOF'
import json, sys
p = sys.argv[1]
# 6 个核心字段。其中 pages/subpackages 必须是数组（即便为空）——否则 SummerCompiler 拿 null deref
d = {
    "deviceOrientation": "landscape",
    "showStatusBar": False,
    "backgroundColor": "#000000",
    "pages": [],
    "subpackages": [],
    "networkTimeout": {
        "request": 5000,
        "connectSocket": 5000,
        "uploadFile": 5000,
        "downloadFile": 500000,
    },
}
# 三个"路径类字符串"字段若设空串会爆 "不能为 ''"，不开放就直接 pop 掉
for k in ("openDataContext", "workers", "lazyLoading"):
    d.pop(k, None)
json.dump(d, open(p, "w"), indent=4, ensure_ascii=False)
EOF

echo "==> 简化 project.config.json（Cocos 默认模板 + urlCheck/appid + libVersion=3.7.9）"
python3 - "$OUT/project.config.json" "$APPID" <<'EOF'
import json, sys
p, appid = sys.argv[1], sys.argv[2]
d = json.load(open(p))
# 只保留 Cocos 默认模板字段，避免开发者工具乱加 setting 干扰项目识别
simplified = {
    "description": "项目配置文件。",
    # 【关键铁律】miniprogramRoot 必须是 "./"（相对路径），绝不能留空串 ""。
    # 空串会让开发者工具找不到源码根目录，回退成按小程序逻辑找 app.json，
    # 报 "[app.json 文件内容错误] app.json: 在项目根目录未找到 app.json"，
    # 即使 compileType=game 也救不回来。devtool 打开项目时可能把它改回 "",
    # 重跑本脚本即可恢复。
    "miniprogramRoot": "./",
    "setting": {
        "urlCheck": False,
        "postcss": True,
        "minified": True,
        "newFeature": False,
        "enhance": True,
        "useIsolateContext": True,
    },
    "compileType": "game",
    # libVersion 官方文档类型是 String。可用值：
    # - "latest" 会触发基础库切到最新（如 3.16.2），对小游戏项目识别有 bug——会卡在"半识别"状态按小程序找 app.json
    # - "3.7.9" 是社区公认稳定值，对小游戏兼容性好
    # "widelyUsed" / "game" 等 Cocos 默认值在新版微信开发者工具会报 "string, string" 类型错误
    "libVersion": "3.7.9",
    "appid": appid,
    "projectname": d.get("projectname", "gunzi-client"),
    "condition": d.get("condition", {
        "search": {"current": -1, "list": []},
        "conversation": {"current": -1, "list": []},
        "game": {"currentL": -1, "list": [], "current": -1},
        "miniprogram": {"current": -1, "list": []},
    }),
}
json.dump(simplified, open(p, "w"), indent=4, ensure_ascii=False)
EOF

echo "==> 归一化 assets 下文件名为小写（引擎短码解码恒为小写 uuid，防微信端大小写敏感 FS 找不到文件）"
python3 - "$OUT" <<'EOF'
import os, sys
root = sys.argv[1]
assets = os.path.join(root, "assets")
count = 0
for base, _, files in os.walk(assets):
    for fn in files:
        if fn.lower() != fn:
            old = os.path.join(base, fn)
            tmp = old + ".tmp_lower"
            new = os.path.join(base, fn.lower())
            os.rename(old, tmp)
            os.rename(tmp, new)
            print("  renamed:", os.path.relpath(old, root), "->", os.path.relpath(new, root))
            count += 1
if count == 0:
    print("  （无大写文件名，跳过）")
EOF

echo "==> 包体积检查（微信小游戏主包上限 4MB = 4194304 字节，超过则真机调试/上传会报 80051）"
/usr/bin/python3 - "$OUT" <<'EOF'
import os, sys
root = sys.argv[1]
LIMIT = 4 * 1024 * 1024
total = 0
biggest = []
for base, _, files in os.walk(root):
    for fn in files:
        p = os.path.join(base, fn)
        if os.path.islink(p):
            continue
        try:
            sz = os.path.getsize(p)
        except OSError:
            continue
        total += sz
        biggest.append((sz, os.path.relpath(p, root)))
biggest.sort(reverse=True)
mb = total / 1024 / 1024
print(f"  总大小: {total} B = {mb:.2f} MB   （上限 4.00 MB，占用 {total/LIMIT*100:.0f}%）")
print("  体积 Top5:")
for sz, rel in biggest[:5]:
    print(f"    {sz/1024:8.1f} KB  {rel}")
if total > LIMIT:
    print("  ❌ 超过 4MB！真机调试/上传会被拒（错误码 80051）")
    print("     解法：settings/v2/packages/engine.json 做引擎功能裁剪（关 3d/spine/tiled-map/particle/physics 等）")
    sys.exit(1)
elif total > LIMIT * 0.8:
    print("  ⚠️  已用超 80%，新增资源前建议再裁剪")
else:
    print("  ✅ 体积达标")
EOF

echo "==> 完成：$OUT"
echo "    下一步：微信开发者工具导入该目录 → 预览/真机调试"
