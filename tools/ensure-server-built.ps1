# =====================================================================
# ensure-server-built.ps1
#
# 作用：启动后端之前，先判断"服务端 Java 源码是否比编译产物新"。
#       如果新（或产物缺失），自动执行 mvn install 重新构建；
#       如果是最新的，直接跳过，不浪费启动时间。
#
# ---------------------------------------------------------------------
# 为什么非要这一步（血泪，别删这段注释）：
#
#   1) 启动脚本用 `mvn exec:java` 拉起后端。exec:java 只做一件事：
#      在当前 JVM 里跑一个 main 方法。它【不会】触发 compile 阶段。
#      实测：`mvn exec:java` 的执行序列里只有 exec:java 一行，
#      没有任何 maven-compiler-plugin，0.7 秒就结束。
#
#      后果：改完 .java 不重新构建，后端跑的还是上一次编译出来的 class，
#      新规则永远不生效 —— 表现为"代码明明改了，游戏里没变"。
#
#   2) 还有更隐蔽的一层：game-room 依赖 game-domain，而 exec:java 是在
#      game-room 目录下【单模块】运行，game-domain 会被从本地仓库
#      (~/.m2/repository) 解析成 jar，而不是读 server/game-domain/
#      target/classes。所以只要动过 game-domain（大多数规则都在那里），
#      就必须 `mvn install` 把新 jar 装进本地仓库，否则白改。
#
#   3) 判定口径用"最旧的产物"而不是"最新的产物"：
#      出现过 target\classes 已经是新编译的、但 .m2 里 jar 还是旧的
#      这种情况，只看最新的会误判成"已是最新"而跳过构建。
#
#   退出码：0 = 可以启动（已最新，或重新构建成功）
#           1 = 构建失败，不要启动后端
# =====================================================================

$ErrorActionPreference = 'Stop'

$ServerDir = 'D:\workbuddy\projects\gunzi-hall\server'
$MvnPath   = 'D:\ENV23\apache-maven-3.9.9\bin\mvn.cmd'
$M2Jar     = Join-Path $env:USERPROFILE '.m2\repository\com\gunzihall\game-domain\0.1.0-SNAPSHOT\game-domain-0.1.0-SNAPSHOT.jar'

if (-not (Test-Path $ServerDir)) {
    Write-Host "[错误] 找不到服务端目录：$ServerDir" -ForegroundColor Red
    exit 1
}

# ---------- 1. 最新的源码文件 ----------
$srcRoots = @("$ServerDir\game-domain\src\main", "$ServerDir\game-room\src\main") |
            Where-Object { Test-Path $_ }

$newestSrc = $null
if ($srcRoots.Count -gt 0) {
    $newestSrc = Get-ChildItem -Path $srcRoots -Recurse -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

# ---------- 2. 编译产物的时间戳（取最旧的一个作为标尺） ----------
$proofPaths = @(
    "$ServerDir\game-domain\target\classes",
    "$ServerDir\game-room\target\classes",
    $M2Jar
)

$marks   = @()
$missing = @()
foreach ($p in $proofPaths) {
    if (-not (Test-Path $p)) { $missing += $p; continue }
    if ((Get-Item $p).PSIsContainer) {
        $inner = Get-ChildItem -Path $p -Recurse -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($inner) { $marks += $inner.LastWriteTime } else { $missing += $p }
    } else {
        $marks += (Get-Item $p).LastWriteTime
    }
}

# ---------- 3. 判断是否需要重建 ----------
# 两个口径取"或"，任一成立就算是最新的、直接跳过：
#   (a) 所有编译产物都比最新源码新 —— 说明刚构建过（手工 mvn install 走这条）
#   (b) 上次成功构建的标记文件比最新源码新 —— 专门覆盖 (a) 判断不了的怪情况：
#       maven-jar-plugin 在"没有东西需要编译"时根本不重写 jar，而 install 又是
#       按原时间戳拷过去的，于是本地仓库里那个 jar 的时间会一直冻在很久以前。
#       少了这一条，源码一改就会永远被判"过期"，每次启动都白跑一遍构建。
$stampFile = Join-Path $ServerDir '.last-build-stamp'

$stale = $true
$reason = ''

$newestAt = $null
if ($null -ne $newestSrc) { $newestAt = $newestSrc.LastWriteTime }

$oldestAt = $null
if ($marks.Count -gt 0) { $oldestAt = $marks | Sort-Object | Select-Object -First 1 }

$fmt = 'MM-dd HH:mm:ss'

if ($missing.Count -gt 0) {
    $reason = '编译产物缺失（可能从没构建过，或被 clean 清掉了）'
} elseif ($null -eq $newestAt) {
    $reason = '找不到任何服务端源码，保险起见构建一次'
} elseif ($null -ne $oldestAt -and $oldestAt -ge $newestAt) {
    $stale = $false
} elseif (Test-Path $stampFile) {
    $stampAt = (Get-Item $stampFile).LastWriteTime
    if ($stampAt -ge $newestAt) {
        $stale = $false
    } else {
        $reason = "源码 $($newestSrc.Name)（$($newestAt.ToString($fmt))）比上次构建（$($stampAt.ToString($fmt))）新"
    }
} else {
    $reason = "源码 $($newestSrc.Name)（$($newestAt.ToString($fmt))）比编译产物（$($oldestAt.ToString($fmt))）新"
}

if (-not $stale) {
    Write-Host "[OK] 服务端代码无改动，跳过构建" -ForegroundColor Green
    exit 0
}

# ---------- 4. 重建 ----------
Write-Host ""
Write-Host "[构建] 服务端代码有更新：$reason" -ForegroundColor Yellow
Write-Host "[构建] 执行 mvn -o -DskipTests install（通常 15-40 秒）..." -ForegroundColor Yellow
Write-Host ""

if (-not (Test-Path $MvnPath)) { $MvnPath = 'mvn' }

Push-Location $ServerDir
try {
    & $MvnPath -o -DskipTests install
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Host "[失败] 服务端构建失败（mvn 退出码 $exitCode），后端不会启动。" -ForegroundColor Red
    Write-Host "       先看上面的报错把编译错误改掉，再重新运行启动脚本。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[OK] 服务端构建完成，新代码已生效" -ForegroundColor Green

# 记下这次成功构建的时间，供下次启动判断"还需不需要重建"
Set-Content -Path $stampFile -Value ("last successful build: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -Encoding UTF8

exit 0
