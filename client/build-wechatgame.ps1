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
    [string]$AppId = "",                       # 小游戏 appid（留空 = 沿用上次产物的 / 内置默认）
    [string]$LibVersion = "3.7.9",             # 基础库版本（latest 会触发小游戏识别 bug，勿改）
    [switch]$PatchOnly                          # 跳过构建，只补丁
)

$ErrorActionPreference = "Stop"

# serverUrl 注入/还原的统一实现（注入本机 IP → 构建 → 还原成 127.0.0.1）。
# 做成幂等的"按模式替换"，而不是"存原文再写回"：这样无论上一次构建有没有正常收尾，
# 跑一遍就能把源码恢复成仓库里该有的样子，不会因为"上次残留了本机 IP"而漏掉还原。
function Set-ServerUrlInSources([string]$Project, [string]$Url) {
    $script:ProjectDir = $Project   # 记下来，供 Fail 在异常退出时也能还原
    $targets = @(
        "assets/scenes/main.scene",
        "assets/scripts/ui/TableUI.ts",
        "assets/scripts/NetTest.ts"
    )
    $pat = 'ws://[A-Za-z0-9._-]+:8080/ws'
    foreach ($rel in $targets) {
        $p = Join-Path $Project $rel
        if (-not (Test-Path -LiteralPath $p)) { Write-Warn "跳过（不存在）: $rel"; continue }
        # 【坑】PS 5.1 的 Get-Content 不带 -Encoding 时按系统 ANSI(中文 Windows=GBK) 解码，
        # 会把 UTF-8 源文件读成乱码，再以 UTF-8 写回即二次编码、不可逆损坏源码。
        # 因此这里强制用 .NET 的 UTF8 读取，与 Write-Utf8NoBom 严格配对。
        $src = [System.IO.File]::ReadAllText($p, (New-Object System.Text.UTF8Encoding($false)))
        $new = [regex]::Replace($src, $pat, $Url)
        if ($new -ne $src) {
            Write-Utf8NoBom -Path $p -Content $new
            Write-Ok "$rel -> $Url"
        } else {
            if ($src -notmatch $pat) { Write-Warn "未找到 serverUrl 模式: $rel" }
            else { Write-Host "    已是目标地址: $rel" -ForegroundColor DarkGray }
        }
    }
}

function Write-Step([string]$msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Fail([string]$msg) {
    Write-Host "    [FAIL] $msg" -ForegroundColor Red
    # 异常退出也要把源码还原成 127.0.0.1，否则本机 IP 会留在源码里被误提交。
    if ($script:ProjectDir) {
        Set-ServerUrlInSources -Project $script:ProjectDir -Url "ws://127.0.0.1:8080/ws"
    }
    exit 1
}

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

# ---------- 1b. 决定 appid（"粘性"：没显式指定就沿用上次产物里的）----------
# 【为什么】真机预览要求"登录开发者工具的微信号必须是该 AppID 的开发者"。若 AppID 属于
# 别人（如任老师），换用「小游戏测试号」是免依赖的自救路径 —— 但本脚本每次构建都会覆写
# project.config.json；一旦写回旧 AppID，开发者工具又会报「登录用户不是该小程序的开发者」，
# 症状是"明明导入成功了，重新构建一下又不行了"。所以：显式传 -AppId 时听调用方的；
# 没传就**沿用上一次产物里已有的 AppID**（开发者工具导入/创建项目时会把它写进
# project.config.json），只有连产物都没有时才退回内置默认值。
$DefaultAppId = "wx72e9a9e9764f0ee1"
if (-not $AppId) {
    $prevPc = Join-Path $BuildPath "project.config.json"
    if (Test-Path -LiteralPath $prevPc) {
        try {
            $pa = (Get-Content -LiteralPath $prevPc -Raw -Encoding UTF8 | ConvertFrom-Json).appid
            if ($pa -and ($pa -match '^wx[0-9a-fA-F]{16}$')) { $AppId = $pa }
        } catch { }
    }
    if ($AppId) { Write-Host "    appid = $AppId（沿用上次产物；要换请传 -AppId）" -ForegroundColor DarkGray }
    else { $AppId = $DefaultAppId; Write-Host "    appid = $AppId（内置默认）" -ForegroundColor DarkGray }
} else {
    Write-Host "    appid = $AppId（由 -AppId 指定）" -ForegroundColor DarkGray
}

# ---------- 2. 同步 serverUrl 为本机局域网 IP（真机调试需要，任老师 .sh 同款逻辑）----------
function Get-LanIp {
    # 虚拟网卡的名字特征 —— 一律排除。
    # 【为什么】真机连的是物理局域网，而虚拟网卡（VMware / VirtualBox / Hyper-V /
    # TUN / ZeroTier / Docker…）的地址虽然也是私网段，手机却**永远连不通**。
    # 麻烦在于它们常常正好是 192.168.x.x，而下面的"网段优先级"会把 192.168.* 排到
    # 第一位 —— 于是脚本挑中虚拟网卡、产物烧进一个连不通的地址，
    # 真机上表现为一直连不上（2026-09-20 排查真机连不上的重点怀疑项之一）。
    $virtualPat = 'VMware|VirtualBox|Hyper-V|Loopback|TAP-|TUN|Tailscale|ZeroTier|' +
                  'Docker|WSL|vEthernet|Npcap|OpenVPN|WireGuard|AnyConnect|VPN|' +
                  'Bluetooth|蓝牙|虚拟|VMnet'

    try {
        # 首选取"有默认网关"的接口：要连的局域网一定有网关，虚拟网卡通常没有。
        # 这比单纯看网段可靠得多。
        $cfgs = @(Get-NetIPConfiguration -ErrorAction Stop | Where-Object {
            $_.IPv4Address -and $_.IPv4DefaultGateway -and
            $_.InterfaceAlias -notmatch $virtualPat -and
            $_.InterfaceDescription -notmatch $virtualPat
        })
        if ($cfgs.Count -gt 0) {
            $pairs = @($cfgs | ForEach-Object {
                $ip = @($_.IPv4Address)[0].IPAddress
                $metric = 9999
                try {
                    $metric = (Get-NetIPInterface -InterfaceIndex $_.InterfaceIndex `
                        -AddressFamily IPv4 -ErrorAction Stop).InterfaceMetric
                } catch { }
                [pscustomobject]@{ IP = $ip; Metric = $metric }
            } | Where-Object {
                $_.IP -and $_.IP -notmatch '^127\.' -and $_.IP -notmatch '^169\.254\.'
            })
            # 同网段内按接口跃点数排（越小越优先，即系统认为的主网卡）
            foreach ($prefix in @('^192\.168\.', '^10\.', '^172\.(1[6-9]|2[0-9]|3[01])\.')) {
                $hit = $pairs | Where-Object { $_.IP -match $prefix } |
                    Sort-Object -Property Metric | Select-Object -First 1
                if ($hit) { return $hit.IP }
            }
            $hit = $pairs | Sort-Object -Property Metric | Select-Object -First 1
            if ($hit) { return $hit.IP }
        }
    } catch { }

    # 回退：老系统没有 Get-NetIPConfiguration，或上面全被过滤掉了 → 退回原来的网段挑选
    try {
        $cands = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notmatch '^127\.' -and
                $_.IPAddress -ne '0.0.0.0' -and
                $_.IPAddress -notmatch '^169\.254\.'   # APIPA：虚拟网卡/没拿到 DHCP，真机连不通
            })
        if ($cands.Count -eq 0) { return "localhost" }
        # 按「真实局域网」优先级取：192.168.* > 10.* > 172.16~31.*
        foreach ($prefix in @('^192\.168\.', '^10\.', '^172\.(1[6-9]|2[0-9]|3[01])\.')) {
            $hit = $cands | Where-Object { $_.IPAddress -match $prefix } |
                Sort-Object -Property InterfaceMetric | Select-Object -First 1
            if ($hit) { return $hit.IPAddress }
        }
        return ($cands | Sort-Object -Property InterfaceMetric | Select-Object -First 1).IPAddress
    } catch { }
    return "localhost"
}

if (-not $PatchOnly) {
    Write-Step "Syncing serverUrl to LAN IP ..."
    $ip = Get-LanIp
    $newUrl = "ws://${ip}:8080/ws"
    Set-ServerUrlInSources -Project $Project -Url $newUrl
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
$BuildT0 = $null
if (-not $PatchOnly) {
    Write-Step "Building wechatgame (Cocos CLI) ..."

    # 【为什么先记时间】Cocos Creator 是 Electron 应用，`& $CocosExe` **一启动就 detach**
    # （实测"耗时 0 秒"、$LASTEXITCODE 为空）。所以"命令一返回就去看产物"永远不可靠 ——
    # 旧产物会把"构建根本没跑"伪装成成功。唯一可信的判据是 **产物 mtime 晚于此刻**。
    $BuildT0 = Get-Date

    $isAdmin = (New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Warn "当前不是管理员。Cocos 要往**安装目录**写引擎缓存，历史上曾因此静默卡死；若本轮构建没跑起来，请改用「以管理员身份运行」。"
    } else {
        Write-Ok "以管理员身份运行"
    }

    # 【真凶，2026-09-20 定案】`ELECTRON_RUN_AS_NODE=1` 会让 CocosCreator.exe 这个
    # Electron 程序**退化成裸 Node 进程**，于是它完全不认构建参数，直接吐：
    #     CocosCreator.exe: bad option: --no-sandbox
    #     CocosCreator.exe: bad option: --project
    # 然后立刻退出、什么都不构建。这个变量是 Electron 系宿主（如 WorkBuddy 这类 IDE）
    # 注入给子进程的，所以"在 IDE/agent 里构建永远失败，用户手工双击却正常"。
    # 本脚本在宿主里被调用时必中此坑（2026-09-20 白查一轮权限才找到）。
    # 另外顺手清掉 Electron 的其它调试变量，避免影响被拉起的编辑器子进程。
    # 【坑】不要用 `Get-Item "Env:\$v"` / `Test-Path Env:\...` 去读或判断：
    # 宿主环境里若存在只有大小写不同的重复变量（`HTTPS_PROXY` + `https_proxy` 是常客），
    # Environment provider 构建字典时会抛 "已添加了具有相同键的项"，
    # 而本脚本 $ErrorActionPreference=Stop → 直接终止。走 .NET 就没有这个问题。
    foreach ($v in @('ELECTRON_RUN_AS_NODE', 'ELECTRON_NO_ATTACH_CONSOLE', 'ELECTRON_ENABLE_LOGGING')) {
        $val = [System.Environment]::GetEnvironmentVariable($v)
        if ($val) {
            Write-Host "    清除宿主环境变量 $v=$val" -ForegroundColor DarkGray
            [System.Environment]::SetEnvironmentVariable($v, $null)
        }
    }

    & $CocosExe --no-sandbox --project $Project --build "platform=wechatgame"
    $code = $LASTEXITCODE
    Write-Host "    Cocos 退出码 = $code（32=参数错 34=构建错 36=成功；空=已 detach，属正常）" -ForegroundColor Gray

    # 轮询等产物刷新。判据是 mtime，不是"文件是否存在"。
    $probe = Join-Path $BuildPath "assets\main\index.js"
    if (-not (Test-Path -LiteralPath $probe)) { $probe = Join-Path $BuildPath "game.json" }
    $prevMtime = if (Test-Path -LiteralPath $probe) { (Get-Item -LiteralPath $probe).LastWriteTime } else { [datetime]::MinValue }
    $waitMax = 300; $waited = 0; $fresh = $false
    while ($waited -lt $waitMax) {
        Start-Sleep -Seconds 3; $waited += 3
        if ((Test-Path -LiteralPath $probe) -and ((Get-Item -LiteralPath $probe).LastWriteTime -gt $prevMtime)) {
            $fresh = $true
            Write-Host "    产物已刷新（等待 ${waited}s）" -ForegroundColor Gray
            break
        }
        if ($waited % 30 -eq 0) { Write-Host "    等待产物中… 已等 ${waited}s" }
    }
    if ($fresh) { Write-Ok "Build finished." }
    else { Write-Warn "等满 ${waitMax}s 未见产物刷新 —— 构建很可能没跑起来，由第 5c 步最终判定。" }
} else {
    Write-Warn "PatchOnly: 跳过 serverUrl 同步与 Cocos 构建。"
}

# ---------- 5. 校验产物 ----------
if (-not (Test-Path -LiteralPath (Join-Path $BuildPath "game.json"))) {
    Fail "构建产物缺少 game.json，预期在：$BuildPath"
}

# ---------- 5b. 自检：产物里真正烧进去的战斗服地址（2026-09-20 新增）----------
# 【为什么必须自检】微信小游戏里**没有 location**，客户端拿不到"页面是从哪台机器加载的"，
# 所以战斗服地址只能来自构建时注入。一旦注入失效（没跑本脚本、或 IP 选错网卡），
# 真机上就只会永远停在"连接中…"——排查成本极高（要连手机、看日志、翻源码）。
# 这里在构建结束的第一时间把地址打出来，回环地址直接判失败。
if (-not $PatchOnly) {
    Write-Step "Verifying baked serverUrl ..."
    $scan = @(Get-ChildItem -LiteralPath $BuildPath -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @('.js', '.json') })
    $found = @($scan |
        Select-String -Pattern 'ws://[A-Za-z0-9._-]+:\d+/ws' -AllMatches -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Matches } | ForEach-Object { $_.Value } | Sort-Object -Unique)
    if ($found.Count -eq 0) {
        Write-Warn "产物里未找到 ws:// 地址（可能被打包器改写），跳过地址自检。"
    } else {
        foreach ($u in $found) { Write-Host "      产物地址: $u" }
        $bad = @($found | Where-Object { $_ -match '(127\.0\.0\.1|localhost)' })
        if ($bad.Count -gt 0) {
            Fail ("产物里烧的是本机回环地址（" + ($bad -join ', ') +
                  "）—— 真机一定连不上。请检查 serverUrl 同步步骤（本脚本第 2 步）。")
        }
        if ($newUrl -and ($found -notcontains $newUrl)) {
            Write-Warn "产物地址与本次注入的 $newUrl 不一致（可能残留旧构建产物），请人工确认。"
        } else {
            Write-Ok "产物地址已确认为 $newUrl"
        }
    }
}

# ---------- 5c. 自检：构建**是否真的发生了**（2026-09-20 新增）----------
# 【为什么必须有这一步】原判据是「退出码 ≠36 **且** game.json 不存在」才算失败。
# 而 game.json 是上一轮构建留下的旧文件、一直存在 → **构建完全没跑也会被判成功**；
# 更糟的是第 6/7 步会重写 game.json 与 project.config.json，把它们的时间戳刷新成"刚刚"，
# 于是整个产物目录看起来像刚构建过 —— 连"地址自检"也会通过（旧包里恰好是同一个 IP）。
# 2026-09-20 就是这样被骗过一次：交给真机测试的微信包里其实是 8 天前的代码，
# 新修复一条都不在里面，白白耗掉一轮真机验证。
# 判据用「产物代码 mtime > 源码 mtime」——不需要人工维护任何指纹，永不过期。
if (-not $PatchOnly) {
    Write-Step "Verifying build is real (freshness) ..."
    $codeFile = Join-Path $BuildPath "assets\main\index.js"
    $srcFiles = @(
        (Join-Path $Project "assets\scripts\net\NetClient.ts"),
        (Join-Path $Project "assets\scripts\ui\TableUI.ts")
    ) | Where-Object { Test-Path -LiteralPath $_ }

    if (-not (Test-Path -LiteralPath $codeFile)) {
        Fail "产物缺少 $codeFile —— 构建没有产出代码。"
    }
    $codeTime = (Get-Item -LiteralPath $codeFile).LastWriteTime
    $newestSrc = ($srcFiles | ForEach-Object { (Get-Item -LiteralPath $_).LastWriteTime } |
        Sort-Object -Descending | Select-Object -First 1)
    Write-Host ("    产物代码 : {0}" -f $codeTime)
    Write-Host ("    最新源码 : {0}" -f $newestSrc)

    # 主判据：本轮有没有真的把产物写出来。$fresh 来自第 4 步的轮询（mtime > $BuildT0）。
    if (-not $fresh) {
        Fail ("构建**没有产出任何新产物** —— 包里的仍是旧代码，不要拿去真机测试！" +
              " 常见原因：① 未以管理员运行（Cocos 写引擎缓存 EPERM → 静默卡死，最常见）；" +
              " ② 有残留 CocosCreator 进程占单实例锁；③ 编辑器卡在启动（Chromium network service crashed）。" +
              " 详见 skill「cocos-creator-cli-build」。")
    }
    # 辅证：即使产物刷新了，也不该比源码旧（正常情况两者会同时更新）。
    if ($newestSrc -and $codeTime -lt $newestSrc) {
        Write-Warn "产物代码时间早于源码，请人工确认包内容。"
    }
    Write-Ok "产物代码是新的（构建真实发生）"

    $plog = Join-Path $Project "temp\logs\project.log"
    if ((Test-Path -LiteralPath $plog) -and ((Get-Item -LiteralPath $plog).LastWriteTime -lt $BuildT0)) {
        Write-Warn "temp\logs\project.log 本轮没有新增，与上面的判定矛盾，建议人工复核。"
    }
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

    # 【铁律】这是小游戏（compileType:game）项目，不是小程序。
    # 千万不要写 miniprogramRoot——那是小程序专用字段，写了微信开发者工具 2.x 会误判项目类型，
    # 把 game.json 当成 app.json 去找，从而报"在项目根目录未找到 app.json"。
    # 另外必须保留 Cocos 自动生成的大批 setting.* / packOptions / editorSetting 等字段，
    # 它们与 libVersion 3.17.x 兼容；暴力覆盖会导致小游戏专有功能（分包/worker/Audio）报错。
    $simplified = [ordered]@{
        description = "项目配置文件。"
        setting     = [ordered]@{
            urlCheck          = $false
            postcss           = $true
            minified          = $true
            newFeature        = $false
            enhance           = $true
            useIsolateContext = $true
        }
        compileType = "game"
        libVersion  = $LibVersion
        appid       = $AppId
        projectname = $projectname
        condition   = [ordered]@{
            search       = [ordered]@{ current = -1; list = @() }
            conversation = [ordered]@{ current = -1; list = @() }
            game         = [ordered]@{ currentL = -1; list = @(); current = -1 }
            miniprogram  = [ordered]@{ current = -1; list = @() }
        }
    }
    Write-Utf8NoBom -Path $pcPath -Content ($simplified | ConvertTo-Json -Depth 10)
    Write-Ok "project.config.json: appid=$AppId libVersion=$LibVersion compileType=game（无 miniprogramRoot）"
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

# ---------- 10. 还原被注入的源码（本机 IP 不进仓库）----------
Write-Step "Restoring sources (back to 127.0.0.1) ..."
Set-ServerUrlInSources -Project $Project -Url "ws://127.0.0.1:8080/ws"
Write-Ok "源码已还原（不影响已构建的产物）。"

Write-Step "Done. 用微信开发者工具「新建项目」选择目录：$BuildPath"
Write-Host "      （项目类型留空，靠 compileType:game 自动识别）" -ForegroundColor Gray
