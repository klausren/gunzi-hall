# build-wechatgame.ps1
# Windows 等价于 macOS 的 build-wechatgame.sh：构建微信小游戏并应用强制补丁。
# 补丁逻辑与任老师 2026-09-07 的 build-wechatgame.sh 对齐。
#
# Usage:
#   .\build-wechatgame.ps1                      # 完整流程：同步 serverUrl -> 构建 -> 补丁
#   .\build-wechatgame.ps1 -PatchOnly           # 跳过构建，只对已有产物补丁
#   .\build-wechatgame.ps1 -CocosExe "D:\Cocos\Creator\3.8.8\CocosCreator.exe"
#   .\build-wechatgame.ps1 -AppId "wx_your_appid"
#
# Cocos CLI 退出码：32 参数非法 / 34 构建错误 / 36 成功（36 也含 worker 清理噪音，见下）。

param(
    [string]$CocosExe = "",                    # CocosCreator.exe 路径（留空自动探测）
    [string]$Project = "",                     # 项目目录（默认：本脚本所在目录）
    [string]$BuildPath = "",                   # 输出目录（默认：<project>\build\wechatgame）
    [string]$AppId = "wxf88033b2ee303d71",     # 小游戏 appid
    [string]$LibVersion = "3.7.9",             # 基础库版本（latest 会触发小游戏识别 bug，勿改）
    [switch]$PatchOnly                          # 跳过构建，只补丁
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Fail([string]$msg)       { Write-Host "    [FAIL] $msg" -ForegroundColor Red; exit 1 }

# 无 BOM UTF-8 写入（PS 5.1 的 `Set-Content -Encoding UTF8` 会加 BOM，部分微信开发者工具版本拒收）
function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $enc)
}

# ---------- 1. 解析路径 ----------
if (-not $Project) { $Project = $PSScriptRoot }
$Project = (Resolve-Path -LiteralPath $Project).Path
if (-not $BuildPath) { $BuildPath = Join-Path $Project "build\wechatgame" }
Write-Step "Project : $Project"
Write-Step "Output  : $BuildPath"

# ---------- 2. 同步 serverUrl 为本机局域网 IP（真机调试需要，任老师 .sh 同款逻辑）----------
function Get-LanIp {
    try {
        $ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notmatch '^127\.' -and $_.IPAddress -ne '0.0.0.0' } |
            Sort-Object -Property InterfaceMetric |
            Select-Object -First 1).IPAddress
        if ($ip) { return $ip }
    } catch { }
    return "localhost"
}

if (-not $PatchOnly) {
    Write-Step "Syncing serverUrl to LAN IP ..."
    $ip = Get-LanIp
    $newUrl = "ws://${ip}:8080/ws"
    $targets = @(
        "assets/scenes/main.scene",
        "assets/scripts/ui/TableUI.ts",
        "assets/scripts/NetTest.ts"
    )
    $pat = 'ws://[A-Za-z0-9._-]+:8080/ws'
    foreach ($rel in $targets) {
        $p = Join-Path $Project $rel
        if (-not (Test-Path -LiteralPath $p)) { Write-Warn "跳过（不存在）: $rel"; continue }
        $src = Get-Content -LiteralPath $p -Raw
        $new = [regex]::Replace($src, $pat, $newUrl)
        if ($new -ne $src) {
            Write-Utf8NoBom -Path $p -Content $new
            Write-Ok "$rel -> $newUrl"
        } else {
            Write-Warn "未找到 serverUrl 模式: $rel"
        }
    }
}

# ---------- 3. 定位 Cocos Creator 3.8.8（-PatchOnly 跳过）----------
if (-not $PatchOnly) {
    $candidates = @(
        "C:\ProgramData\cocos\editors\Creator\3.8.8\CocosCreator.exe",
        "C:\Program Files\Cocos\Creator\3.8.8\CocosCreator.exe",
        "C:\Program Files\CocosCreator\3.8.8\CocosCreator.exe",
        "$env:USERPROFILE\.CocosCreator\3.8.8\CocosCreator.exe",
        "$env:LOCALAPPDATA\Programs\Cocos\Creator\3.8.8\CocosCreator.exe"
    )
    if ($CocosExe) {
        if (-not (Test-Path -LiteralPath $CocosExe)) { Fail "CocosCreator.exe 不存在: $CocosExe" }
    } else {
        foreach ($c in $candidates) {
            if (Test-Path -LiteralPath $c) { $CocosExe = $c; break }
        }
        if (-not $CocosExe) { Fail "未找到 CocosCreator.exe，请用 -CocosExe 指定路径。" }
    }
    Write-Ok "CocosCreator : $CocosExe"
}

# ---------- 4. 构建（-PatchOnly 跳过）----------
if (-not $PatchOnly) {
    Write-Step "Building wechatgame (Cocos CLI) ..."
    & $CocosExe --no-sandbox --project $Project --build "platform=wechatgame"
    $code = $LASTEXITCODE
    # 退出码 36 可能混有 worker 清理噪音，产物存在即视为成功（任老师 .sh 同款容错）
    if ($code -ne 36 -and -not (Test-Path -LiteralPath (Join-Path $BuildPath "game.json"))) {
        Fail "Cocos 构建失败，退出码 $code（32=参数错 34=构建错 36=成功）。"
    }
    Write-Ok "Build finished."
} else {
    Write-Warn "PatchOnly: 跳过 serverUrl 同步与 Cocos 构建。"
}

# ---------- 5. 校验产物 ----------
if (-not (Test-Path -LiteralPath (Join-Path $BuildPath "game.json"))) {
    Fail "构建产物缺少 game.json，预期在：$BuildPath"
}

# ---------- 6. 覆写 game.json 为 6 字段模板（对应 §8.1）----------
Write-Step "Patching game.json (6-field template) ..."
$gameJson = [ordered]@{
    deviceOrientation = "landscape"
    showStatusBar     = $false
    backgroundColor   = "#000000"
    pages             = @()
    subpackages       = @()
    networkTimeout    = [ordered]@{
        request       = 5000
        connectSocket = 5000
        uploadFile    = 5000
        downloadFile  = 500000
    }
}
Write-Utf8NoBom -Path (Join-Path $BuildPath "game.json") -Content ($gameJson | ConvertTo-Json -Depth 5)
Write-Ok "game.json rewritten (6 fields)."

# ---------- 7. 简化 project.config.json（对应任老师 .sh 的简化模板）----------
Write-Step "Patching project.config.json ..."
$pcPath = Join-Path $BuildPath "project.config.json"
if (Test-Path -LiteralPath $pcPath) {
    $old = Get-Content -LiteralPath $pcPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $projectname = if ($old.PSObject.Properties["projectname"]) { $old.projectname } else { "gunzi-client" }

    # 【关键铁律】miniprogramRoot 必须是 "./"（相对路径），绝不能空串。
    # 空串会让开发者工具找不到源码根目录，回退成按小程序逻辑找 app.json。
    $simplified = [ordered]@{
        description      = "项目配置文件。"
        miniprogramRoot  = "./"
        setting          = [ordered]@{
            urlCheck          = $false
            postcss           = $true
            minified          = $true
            newFeature        = $false
            enhance           = $true
            useIsolateContext = $true
        }
        compileType      = "game"
        libVersion       = $LibVersion
        appid            = $AppId
        projectname      = $projectname
        condition        = [ordered]@{
            search       = [ordered]@{ current = -1; list = @() }
            conversation = [ordered]@{ current = -1; list = @() }
            game         = [ordered]@{ currentL = -1; list = @(); current = -1 }
            miniprogram  = [ordered]@{ current = -1; list = @() }
        }
    }
    Write-Utf8NoBom -Path $pcPath -Content ($simplified | ConvertTo-Json -Depth 10)
    Write-Ok "project.config.json: appid=$AppId libVersion=$LibVersion compileType=game miniprogramRoot=./"
} else {
    Write-Warn "project.config.json 不存在，跳过（可能需先 GUI 构建）。"
}

# ---------- 8. 归一化 import 文件名为小写（对应 §8.2）----------
Write-Step "Normalizing import file names to lowercase ..."
$importDir = Join-Path $BuildPath "assets\main\import"
if (Test-Path -LiteralPath $importDir) {
    $upper = Get-ChildItem -LiteralPath $importDir -Recurse -File |
        Where-Object { $_.Name -cmatch '[A-Z]' }
    if ($upper.Count -eq 0) {
        Write-Ok "无大写文件名。"
    } else {
        # 两阶段重命名，规避大小写不敏感文件系统冲突
        $stage = @()
        foreach ($f in $upper) {
            Rename-Item -LiteralPath $f.FullName -NewName ("__lc__" + $f.Name)
            $stage += [pscustomobject]@{ File = $f; Tmp = Join-Path $f.DirectoryName ("__lc__" + $f.Name) }
        }
        foreach ($s in $stage) {
            Rename-Item -LiteralPath $s.Tmp -NewName $s.File.Name.ToLowerInvariant()
        }
        Write-Ok "已重命名 $($upper.Count) 个文件为小写。"
    }
} else {
    Write-Warn "import/ 不存在（可能空构建）。"
}

# ---------- 9. 包体积检查（微信小游戏主包上限 4MB，超过则真机报 80051）----------
Write-Step "Checking package size ..."
$limit = 4 * 1024 * 1024
$files = Get-ChildItem -LiteralPath $BuildPath -Recurse -File
$total = ($files | Measure-Object -Property Length -Sum).Sum
$mb = $total / 1MB
Write-Host ("    总大小: {0:N2} MB  （上限 4.00 MB，占用 {1}%）" -f $mb, [int]($total / $limit * 100))
$top5 = $files | Sort-Object Length -Descending | Select-Object -First 5
foreach ($f in $top5) {
    Write-Host ("      {0,8:N1} KB  {1}" -f ($f.Length / 1KB), $f.FullName.Substring($BuildPath.Length + 1))
}
if ($total -gt $limit) {
    Fail "超过 4MB！真机调试/上传会被拒（错误码 80051）。解法：settings/v2/packages/engine.json 做引擎功能裁剪。"
} elseif ($total -gt $limit * 0.8) {
    Write-Warn "已用超 80%，新增资源前建议裁剪引擎功能。"
} else {
    Write-Ok "体积达标。"
}

Write-Step "Done. 用微信开发者工具「新建项目」选择目录：$BuildPath"
Write-Host "      （项目类型留空，靠 compileType:game 自动识别）" -ForegroundColor Gray
