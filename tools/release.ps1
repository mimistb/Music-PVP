<#
.SYNOPSIS
    Dango Music 一键发版：规范校验 → 版本号生成 → 多环境构建 → 产物归档 → 通知推送。

.DESCRIPTION
    全流程无人工干预，任一阶段失败即中止（已成功的产物保留并写入清单）。
    运行状态实时打印，同时写入 release/last-run.json 供外部监控读取。

.PARAMETER Bump
    语义化版本递增方式：patch 只进最后一位（0-9 循环进位，遵循项目既有规则），
    minor / major 依次进高位。

.PARAMETER SkipBuild
    只做校验 / 版本 / 归档，不执行 gradle（适合 CI 里已单独构建的场景）。

.PARAMETER Modules
    需要构建的端，默认自动探测 legacy / modern / modern26。

.EXAMPLE
    .\tools\release.ps1
    .\tools\release.ps1 -Bump minor -Webhook $env:DANGO_RELEASE_WEBHOOK
#>
param(
    [ValidateSet('patch', 'minor', 'major')]
    [string]$Bump = 'patch',
    [string[]]$Modules = @(),
    [string]$OutDir = '',
    [string]$ArtifactStore = '',
    [string]$Webhook = $env:DANGO_RELEASE_WEBHOOK,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.432.6-hotspot',
    [switch]$SkipLint,
    [switch]$SkipBuild,
    [switch]$NoBump,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = Join-Path $Root 'release' }
if ([string]::IsNullOrWhiteSpace($ArtifactStore)) { $ArtifactStore = Join-Path $OutDir 'artifacts' }
if ([string]::IsNullOrWhiteSpace($JdkHome) -or -not (Test-Path $JdkHome)) { $JdkHome = $env:JAVA_HOME }

$VersionFile = Join-Path $Root 'version.properties'
$StatusFile = Join-Path $OutDir 'last-run.json'
$startedAt = Get-Date
$status = [ordered]@{
    startedAt   = $startedAt.ToString('o')
    finishedAt  = $null
    version     = $null
    result      = 'running'
    stages      = @()
}

function Write-Stage([string]$Name, [string]$State, [string]$Detail) {
    $color = switch ($State) {
        'ok'    { 'Green' }
        'skip'  { 'DarkGray' }
        'fail'  { 'Red' }
        default { 'Cyan' }
    }
    Write-Host ("[{0:HH:mm:ss}] {1,-10} {2} {3}" -f (Get-Date), $State.ToUpper(), $Name, $Detail) -ForegroundColor $color
    $script:stageRows += [pscustomobject]@{ name = $Name; state = $State; detail = $Detail; at = (Get-Date).ToString('o') }
}

function Save-Status([string]$Result) {
    $status.finishedAt = (Get-Date).ToString('o')
    $status.result = $Result
    $status.stages = $script:stageRows
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $status | ConvertTo-Json -Depth 6 | Set-Content -Path $StatusFile -Encoding UTF8
}

$script:stageRows = @()

# ---------------------------------------------------------------- 1. 代码规范校验

function Invoke-Lint {
    $errors = @()
    if (-not (Test-Path $VersionFile)) {
        $errors += 'version.properties 缺失'
    }
    else {
        # 文件里有注释行，必须只认 version= 那一行；每一位都只能是 0-9 的单个数字
        $raw = Get-Content $VersionFile -Raw
        if (-not [regex]::IsMatch($raw, '(?m)^\s*version\s*=\s*\d\.\d\.\d\s*$')) {
            $errors += 'version.properties 缺少合法的 version=x.y.z 行（每一位必须是 0-9 的单个数字）'
        }
    }

    # ASM transformer 注册与源码一一对应：删模块时最容易漏掉这里
    $plugin = Join-Path $Root 'legacy\src\main\java\me\sorihiko\dango\core\DangoCorePlugin.java'
    if (Test-Path $plugin) {
        $src = Get-Content $plugin -Raw
        $names = [regex]::Matches($src, '"me\.sorihiko\.dango\.core\.([A-Za-z0-9_]+)"') |
            ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
        foreach ($n in $names) {
            $file = Join-Path $Root "legacy\src\main\java\me\sorihiko\dango\core\$n.java"
            if (-not (Test-Path $file)) { $errors += "transformer 类缺失：$n" }
        }
    }

    # 已下线模块的引用残留：直接扫描源码目录，不依赖 git（CI / 沙箱里没有 git 也能跑）
    $srcRoots = @('common', 'legacy', 'modern', 'modern26') |
        ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path $_ }
    $legacyRefs = $null
    if ($srcRoots.Count -gt 0) {
        $legacyRefs = Get-ChildItem -Path $srcRoots -Recurse -File -Include '*.kt', '*.java', '*.json', '*.properties' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch '\\build\\' } |
            Select-String -Pattern 'WaveyCapes|CapeTransformer|CapeHooks' -List
    }
    if ($legacyRefs) { $errors += '检测到已下线模块的残留引用' }

    if ($errors.Count -gt 0) { throw ($errors -join '; ') }
}

# ---------------------------------------------------------------- 2. 版本号生成

function Get-NextVersion([string]$Current, [string]$Kind) {
    if ($Current -notmatch '^\d\.\d\.\d$') {
        throw "当前版本号 $Current 不合法：必须是 x.y.z，且每一位都是 0-9 的单个数字"
    }
    $p = $Current.Split('.')
    $major = [int]$p[0]; $minor = [int]$p[1]; $patch = [int]$p[2]
    switch ($Kind) {
        'major' { $major++; $minor = 0; $patch = 0 }
        'minor' { $minor++; $patch = 0 }
        default { $patch++ }
    }
    # 逐位进位：任何一位满 10 就清零并向高位进 1，保证每一位始终是单个数字
    # 2.2.2 -> 2.2.3 / 2.8.9 -> 2.9.0 / 2.9.9 -> 3.0.0
    if ($patch -gt 9) { $patch = 0; $minor++ }
    if ($minor -gt 9) { $minor = 0; $major++ }
    if ($major -gt 9) { throw "版本号 $Current 已到 9.9.9，无法再进位（首位也不能出现两位数）" }
    return "$major.$minor.$patch"
}

function Invoke-Version {
    $raw = Get-Content $VersionFile -Raw
    $m = [regex]::Match($raw, '(?m)^\s*version\s*=\s*(\d\.\d\.\d)\s*$')
    if (-not $m.Success) { throw 'version.properties 缺少合法的 version=x.y.z 行（每一位必须是 0-9 的单个数字）' }
    $current = $m.Groups[1].Value
    $next = if ($NoBump) { $current } else { Get-NextVersion $current $Bump }
    if (-not $DryRun -and $next -ne $current) {
        # 只替换 version= 那一行，保留注释；保持 UTF-8 无 BOM
        $updated = [regex]::Replace($raw, '(?m)^(\s*version\s*=\s*)\d\.\d\.\d', "`${1}$next")
        [System.IO.File]::WriteAllText($VersionFile, $updated, (New-Object System.Text.UTF8Encoding($false)))
    }
    $script:version = $next
    $status.version = $next
    return "$current -> $next"
}

# ---------------------------------------------------------------- 3. 多环境构建

function Resolve-Modules {
    if ($Modules.Count -gt 0) { return $Modules }
    $found = @()
    foreach ($m in @('legacy', 'modern', 'modern26')) {
        if (Test-Path (Join-Path $Root "$m\gradlew.bat")) { $found += $m }
    }
    return $found
}

function Invoke-Build([string]$Module) {
    $dir = Join-Path $Root $Module
    $gradleArgs = @('build', '--offline', '--no-daemon')
    if ($DryRun) {
        Write-Host "  (dry-run) ${Module}: gradlew $($gradleArgs -join ' ')"
        return
    }
    $env:JAVA_HOME = $JdkHome
    Push-Location $dir
    try {
        & .\gradlew.bat @gradleArgs 2>&1 | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw "$Module 构建失败（exit $LASTEXITCODE）" }
    }
    finally {
        Pop-Location
    }
}

# ---------------------------------------------------------------- 4. 产物归档

function Invoke-Package {
    New-Item -ItemType Directory -Force -Path $ArtifactStore | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $target = Join-Path $ArtifactStore "v$($script:version)-$stamp"
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    $files = @()

    $libDirs = Resolve-Modules | ForEach-Object { Join-Path $Root "$_\build\libs" }
    foreach ($dir in $libDirs) {
        if (-not (Test-Path $dir)) { continue }
        Get-ChildItem -Path $dir -Filter '*.jar' -File |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            ForEach-Object {
                $dest = Join-Path $target $_.Name
                Copy-Item $_.FullName $dest -Force
                $files += [pscustomobject]@{
                    name   = $_.Name
                    size   = $_.Length
                    sha256 = (Get-FileHash $dest -Algorithm SHA256).Hash
                }
            }
    }
    if ($files.Count -eq 0) { throw '没有找到可归档的构建产物' }

    $manifest = [ordered]@{
        version   = $script:version
        builtAt   = (Get-Date).ToString('o')
        artifacts = $files
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $target 'manifest.json') -Encoding UTF8
    Write-Host "  归档目录：$target"
    return $target
}

# ---------------------------------------------------------------- 5. 通知推送

function Invoke-Notify([string]$ArtifactDir) {
    $text = "Dango Music v$($script:version) 发版完成`n产物：$ArtifactDir"
    $channels = @()
    if (-not [string]::IsNullOrWhiteSpace($Webhook)) {
        $channels += [pscustomobject]@{ type = 'webhook'; url = $Webhook }
    }
    $channelFile = Join-Path $PSScriptRoot 'channels.json'
    if (Test-Path $channelFile) {
        (Get-Content $channelFile -Raw | ConvertFrom-Json) | ForEach-Object { $channels += $_ }
    }
    if ($channels.Count -eq 0) { return '未配置通知渠道，已跳过（可设置 DANGO_RELEASE_WEBHOOK）' }

    $sent = 0
    foreach ($c in $channels) {
        if ($DryRun) { continue }
        try {
            $body = @{ msg_type = 'text'; content = @{ text = $text } } | ConvertTo-Json -Depth 4
            Invoke-RestMethod -Uri $c.url -Method Post -ContentType 'application/json; charset=utf-8' `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 15 | Out-Null
            $sent++
        }
        catch {
            Write-Host "  通知失败：$($c.type) $($c.url) - $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    return "已推送 $sent/$($channels.Count) 个渠道"
}

# ---------------------------------------------------------------- 主流程

$script:version = $null
try {
    if (-not $SkipLint) {
        Write-Stage 'lint' 'run' '代码规范 / 注册一致性校验'
        try { Invoke-Lint; Write-Stage 'lint' 'ok' '通过' }
        catch { Write-Stage 'lint' 'fail' $_.Exception.Message; throw }
    }
    else { Write-Stage 'lint' 'skip' '按参数跳过' }

    Write-Stage 'version' 'run' "语义化版本（$Bump）"
    $v = Invoke-Version
    Write-Stage 'version' 'ok' $v

    if (-not $SkipBuild) {
        $mods = Resolve-Modules
        if ($mods.Count -eq 0) { throw '没有找到可构建的端（legacy/modern/modern26）' }
        foreach ($m in $mods) {
            Write-Stage "build:$m" 'run' 'gradlew build --offline'
            Invoke-Build $m
            Write-Stage "build:$m" 'ok' '完成'
        }
    }
    else { Write-Stage 'build' 'skip' '按参数跳过' }

    Write-Stage 'package' 'run' "归档到 $ArtifactStore"
    $dir = Invoke-Package
    Write-Stage 'package' 'ok' $dir

    Write-Stage 'notify' 'run' '多渠道通知'
    $msg = Invoke-Notify $dir
    Write-Stage 'notify' 'ok' $msg

    Save-Status 'success'
    Write-Host "`n发版成功：v$($script:version)" -ForegroundColor Green
    Write-Host "状态文件：$StatusFile"
}
catch {
    Write-Stage 'fatal' 'fail' $_.Exception.Message
    Save-Status 'failed'
    Write-Host "`n发版失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
