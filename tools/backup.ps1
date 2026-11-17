<#
.SYNOPSIS
    Music PVP 构建备份：版本号递增 + 固定备份命名 + 产物归档。

.DESCRIPTION
    备份名固定为「Music PVP X.X.X+1.8.9.jar」与「Music PVP X.X.X+1.8.9-src.zip」，
    X.X.X 的每一位都只能是 0-9 的单个数字，递增时从末尾位开始、逐位进位：

        2.2.2 -> 2.2.3        常规迭代
        2.8.9 -> 2.9.0        末尾位满 9 归零，中间位 +1
        2.9.9 -> 3.0.0        中间位也满 9，中间位/末尾位归零，首位 +1
        9.9.9 -> 报错          已到可表示上界，再进首位就成了两位数

.PARAMETER Bump
    递增方式：patch（默认，只动末尾位并按位进位）、minor、major、none（不递增）。

.PARAMETER Version
    直接指定版本号，优先级高于 Bump；同样必须是每一位单个数字的 x.y.z。

.PARAMETER Jar
    要备份的 jar，默认取 legacy\build\libs\DangoMusic-<version>+1.8.9.jar。

.PARAMETER SrcZip
    要备份的源码包；不传就只备份 jar。

.PARAMETER Dir
    备份目录，默认是本仓库上层的 music pvp。

.PARAMETER NoSync
    只算版本、只复制，不回写 version.properties。

.PARAMETER NormalizeExisting
    把备份目录里所有历史文件按新格式重命名（非法版本号按 10 进制等价归一）。

.PARAMETER SelfTest
    只跑进位边界与命名格式自测，不读写任何文件。

.EXAMPLE
    .\tools\backup.ps1 -SelfTest
    .\tools\backup.ps1 -Bump patch -SrcZip ..\music pvp\Music PVP 3.0.4+1.8.9-src.zip
    .\tools\backup.ps1 -NormalizeExisting
#>
param(
    [ValidateSet('patch', 'minor', 'major', 'none')]
    [string]$Bump = 'patch',
    [string]$Version = '',
    [string]$Jar = '',
    [string]$SrcZip = '',
    [string]$Dir = '',
    [switch]$NoSync,
    [switch]$NormalizeExisting,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$VersionFile = Join-Path $Root 'version.properties'
if ([string]::IsNullOrWhiteSpace($Dir)) {
    $Dir = [System.IO.Path]::GetFullPath((Join-Path $Root '..\music pvp'))
}

$VersionPattern = '^\d\.\d\.\d$'
$BackupNamePattern = '^Music PVP \d\.\d\.\d\+1\.8\.9(-src)?\.(jar|zip)$'
$LegacyNamePattern = '^(?i:music pvp) (\d+)\.(\d+)\.(\d+)\+1\.8\.9(-src)?\.(jar|zip)$'

function Assert-Version([string]$Value) {
    if ($Value -notmatch $VersionPattern) {
        throw "版本号 $Value 不合法：必须是 x.y.z，且每一位都是 0-9 的单个数字"
    }
}

function Get-NextVersion([string]$Current, [string]$Kind) {
    Assert-Version $Current
    $p = $Current.Split('.')
    $major = [int]$p[0]; $minor = [int]$p[1]; $patch = [int]$p[2]
    switch ($Kind) {
        'major' { $major++; $minor = 0; $patch = 0 }
        'minor' { $minor++; $patch = 0 }
        'none' { return $Current }
        default { $patch++ }
    }
    # 逐位进位：哪一位满 10 就清零并向高位进 1，保证每一位始终是单个数字
    if ($patch -gt 9) { $patch = 0; $minor++ }
    if ($minor -gt 9) { $minor = 0; $major++ }
    if ($major -gt 9) { throw "版本号 $Current 已到 9.9.9，无法再进位（首位也不能出现两位数）" }
    return "$major.$minor.$patch"
}

# 非法历史版本按 10 进制等价归一：2.10.4 -> 3.0.4，1.9.10 -> 2.0.0
function ConvertTo-CanonicalVersion([string]$Value) {
    if ($Value -notmatch '^\d+\.\d+\.\d+$') {
        throw "无法归一化的版本号：$Value"
    }
    $p = $Value.Split('.')
    $total = [int]$p[0] * 100 + [int]$p[1] * 10 + [int]$p[2]
    $major = [int][System.Math]::Truncate($total / 100)
    if ($major -gt 9) { throw "版本号 $Value 归一化后首位超过 9：$major" }
    $minor = [int][System.Math]::Truncate(($total % 100) / 10)
    $patch = $total % 10
    return "$major.$minor.$patch"
}

function Get-BackupName([string]$Value, [switch]$Source) {
    Assert-Version $Value
    if ($Source) { return "Music PVP $Value+1.8.9-src.zip" }
    return "Music PVP $Value+1.8.9.jar"
}

function Read-CurrentVersion {
    $raw = Get-Content -LiteralPath $VersionFile -Raw
    $m = [regex]::Match($raw, '(?m)^\s*version\s*=\s*([^\r\n#]+?)\s*$')
    if (-not $m.Success) { throw 'version.properties 缺少 version= 行' }
    return $m.Groups[1].Value
}

function Write-Version([string]$Value) {
    $raw = Get-Content -LiteralPath $VersionFile -Raw
    # 只替换 version= 那一行，保留注释和 CRLF
    $updated = [regex]::Replace($raw, '(?m)^(\s*version\s*=\s*)[^\r\n]+', "`${1}$Value")
    [System.IO.File]::WriteAllText($VersionFile, $updated, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-NormalizeExisting([string]$Directory) {
    if (-not (Test-Path -LiteralPath $Directory)) { throw "备份目录不存在：$Directory" }
    $plan = @()
    foreach ($file in Get-ChildItem -LiteralPath $Directory -File) {
        $m = [regex]::Match($file.Name, $LegacyNamePattern)
        if (-not $m.Success) {
            if ($file.Name -notmatch $BackupNamePattern) {
                Write-Host "  跳过（不符合任何已知命名）：$($file.Name)" -ForegroundColor DarkGray
            }
            continue
        }
        $canonical = ConvertTo-CanonicalVersion "$($m.Groups[1].Value).$($m.Groups[2].Value).$($m.Groups[3].Value)"
        $target = Get-BackupName $canonical -Source:$m.Groups[4].Success
        if ($file.Name -ceq $target) { continue }
        $plan += [pscustomobject]@{ Source = $file.FullName; OldName = $file.Name; Target = $target }
    }
    if ($plan.Count -eq 0) { Write-Host '  无需重命名'; return 0 }

    # 目标名去重：两个不同的旧文件映射到同一个新名字时必须报错，不能静默覆盖
    $dup = $plan | Group-Object { $_.Target.ToLowerInvariant() } | Where-Object { $_.Count -gt 1 }
    if ($dup) { throw ("重命名目标冲突：" + (($dup | ForEach-Object { $_.Name }) -join ', ')) }

    # 两阶段改名：先挪到临时名，避免 A->B 时 B 还是待改名的旧文件
    $staged = @()
    $i = 0
    foreach ($item in $plan) {
        $tmp = Join-Path (Split-Path -Parent $item.Source) ("__backup_rename_{0}.tmp" -f $i++)
        Rename-Item -LiteralPath $item.Source -NewName (Split-Path -Leaf $tmp)
        $staged += [pscustomobject]@{ Temp = $tmp; Target = $item.Target; OldName = $item.OldName }
    }
    foreach ($item in $staged) {
        Rename-Item -LiteralPath $item.Temp -NewName $item.Target
        Write-Host ("  {0}  ->  {1}" -f $item.OldName, $item.Target) -ForegroundColor DarkGray
    }
    return $staged.Count
}

function Invoke-SelfTest {
    $cases = @(
        @{ from = '2.2.2'; kind = 'patch'; want = '2.2.3'; note = '常规迭代' },
        @{ from = '2.2.8'; kind = 'patch'; want = '2.2.9'; note = '末尾位 8->9' },
        @{ from = '2.8.9'; kind = 'patch'; want = '2.9.0'; note = '末尾位满 9 进位' },
        @{ from = '2.9.9'; kind = 'patch'; want = '3.0.0'; note = '连续进位到首位' },
        @{ from = '0.0.9'; kind = 'patch'; want = '0.1.0'; note = '零值边界' },
        @{ from = '0.9.9'; kind = 'patch'; want = '1.0.0'; note = '零段进位' },
        @{ from = '9.9.8'; kind = 'patch'; want = '9.9.9'; note = '可表示上界' },
        @{ from = '2.5.7'; kind = 'minor'; want = '2.6.0'; note = 'minor 归零 patch' },
        @{ from = '2.9.5'; kind = 'minor'; want = '3.0.0'; note = 'minor 满 9 进位' },
        @{ from = '3.9.9'; kind = 'major'; want = '4.0.0'; note = 'major 归零低位' },
        @{ from = '3.0.4'; kind = 'none'; want = '3.0.4'; note = '不递增' }
    )
    $fail = 0
    foreach ($c in $cases) {
        $got = Get-NextVersion $c.from $c.kind
        if ($got -ne $c.want) {
            $fail++
            Write-Host ("FAIL  {0} {1} -> {2}，期望 {3}（{4}）" -f $c.from, $c.kind, $got, $c.want, $c.note) -ForegroundColor Red
        }
        else {
            Write-Host ("PASS  {0} {1} -> {2}  {3}" -f $c.from, $c.kind, $got, $c.note) -ForegroundColor Green
        }
    }

    # 9.9.9 再进位必须报错，而不是吐出 10.0.0
    try {
        $overflow = Get-NextVersion '9.9.9' 'patch'
        $fail++
        Write-Host "FAIL  9.9.9 patch -> $overflow，期望抛异常" -ForegroundColor Red
    }
    catch {
        Write-Host 'PASS  9.9.9 patch -> 抛出异常（首位不会出现两位数）' -ForegroundColor Green
    }

    # 非法版本号必须被拒绝
    foreach ($bad in @('2.10.4', '1.9.10', '2.2', '2.2.2.2', 'a.b.c')) {
        try {
            Assert-Version $bad
            $fail++
            Write-Host "FAIL  $bad 未被拒绝" -ForegroundColor Red
        }
        catch {
            Write-Host "PASS  $bad 被拒绝" -ForegroundColor Green
        }
    }

    # 历史非法版本归一化
    foreach ($c in @(
            @{ from = '2.10.4'; want = '3.0.4' },
            @{ from = '2.10.0'; want = '3.0.0' },
            @{ from = '1.9.10'; want = '2.0.0' },
            @{ from = '2.9.9'; want = '2.9.9' })) {
        $got = ConvertTo-CanonicalVersion $c.from
        if ($got -ne $c.want) {
            $fail++
            Write-Host ("FAIL  {0} 归一化 -> {1}，期望 {2}" -f $c.from, $got, $c.want) -ForegroundColor Red
        }
        else {
            Write-Host ("PASS  {0} 归一化 -> {1}" -f $c.from, $got) -ForegroundColor Green
        }
    }

    # 全链路：从 0.0.0 连续递增 301 次，每一位都必须始终是单个数字
    $chain = '0.0.0'
    $badDigit = 0
    $at299 = ''
    $at300 = ''
    for ($i = 1; $i -le 301; $i++) {
        $chain = Get-NextVersion $chain 'patch'
        if ($chain -notmatch $VersionPattern) { $badDigit++ }
        if ($i -eq 299) { $at299 = $chain }
        if ($i -eq 300) { $at300 = $chain }
    }
    if ($badDigit -eq 0 -and $at299 -eq '2.9.9' -and $at300 -eq '3.0.0' -and $chain -eq '3.0.1') {
        Write-Host 'PASS  0.0.0 连续递增 301 次：299->2.9.9，300->3.0.0，301->3.0.1，全程单位数' -ForegroundColor Green
    }
    else {
        $fail++
        Write-Host "FAIL  连续递增异常：i299=$at299 i300=$at300 i301=$chain 异常位=$badDigit" -ForegroundColor Red
    }

    # 命名格式
    if ((Get-BackupName '3.0.4') -ne 'Music PVP 3.0.4+1.8.9.jar') {
        $fail++
        Write-Host "FAIL  jar 命名 = $(Get-BackupName '3.0.4')" -ForegroundColor Red
    }
    else {
        Write-Host 'PASS  jar 命名 = Music PVP 3.0.4+1.8.9.jar' -ForegroundColor Green
    }
    if ((Get-BackupName '3.0.4' -Source) -ne 'Music PVP 3.0.4+1.8.9-src.zip') {
        $fail++
        Write-Host "FAIL  源码包命名 = $(Get-BackupName '3.0.4' -Source)" -ForegroundColor Red
    }
    else {
        Write-Host 'PASS  源码包命名 = Music PVP 3.0.4+1.8.9-src.zip' -ForegroundColor Green
    }
    foreach ($name in @('Music PVP 3.0.4+1.8.9.jar', 'Music PVP 3.0.4+1.8.9-src.zip')) {
        if ($name -notmatch $BackupNamePattern) {
            $fail++
            Write-Host "FAIL  $name 不符合固定格式" -ForegroundColor Red
        }
    }

    if ($fail -gt 0) {
        Write-Host "`n自测失败：$fail 项" -ForegroundColor Red
        exit 1
    }
    Write-Host "`n自测全部通过" -ForegroundColor Green
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

if ($NormalizeExisting) {
    $count = Invoke-NormalizeExisting $Dir
    Write-Host "已规范化 $count 个文件：$Dir" -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- 版本推进

$current = Read-CurrentVersion
Assert-Version $current
$target = if (-not [string]::IsNullOrWhiteSpace($Version)) { $Version } else { Get-NextVersion $current $Bump }
Assert-Version $target
if (-not $NoSync -and $target -ne $current) {
    Write-Version $target
}
Write-Host "版本：$current -> $target" -ForegroundColor Cyan

# ---------------------------------------------------------------- 产物归档

if ([string]::IsNullOrWhiteSpace($Jar)) {
    $Jar = Join-Path $Root "legacy\build\libs\DangoMusic-$target+1.8.9.jar"
}
if (-not (Test-Path -LiteralPath $Jar)) {
    throw "找不到构建产物：$Jar（先跑 legacy 的 reobfShadowJar）"
}
New-Item -ItemType Directory -Force -Path $Dir | Out-Null

$destJar = Join-Path $Dir (Get-BackupName $target)
Copy-Item -LiteralPath $Jar -Destination $destJar -Force
Write-Host "  jar      -> $destJar"

if (-not [string]::IsNullOrWhiteSpace($SrcZip)) {
    if (-not (Test-Path -LiteralPath $SrcZip)) { throw "找不到源码包：$SrcZip" }
    $destZip = Join-Path $Dir (Get-BackupName $target -Source)
    Copy-Item -LiteralPath $SrcZip -Destination $destZip -Force
    Write-Host "  src.zip  -> $destZip"
}

Write-Host "备份完成：Music PVP $target+1.8.9" -ForegroundColor Green
