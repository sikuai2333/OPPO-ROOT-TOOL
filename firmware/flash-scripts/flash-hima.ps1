<#
  一键刷入 hima / LineageOS 23.2 镜像。

  默认刷 boot / dtbo / vbmeta / super 四个分区。
  LineageOS 23.2 是 Android 16 的镜像，比原厂新，不能靠 downgrade 回滚，
  刷之前务必确认手里有分区备份（见 docs/DEVICE-FACTS.md）。

  用法：
    .\flash-hima.ps1 -Images C:\path\to\out\target\product\hima
    .\flash-hima.ps1 -Images . -SkipSuper        # 只刷 boot/dtbo/vbmeta
    .\flash-hima.ps1 -Images . -WipeData         # 刷完进 recovery 格式化 data
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Images,
    [switch]$SkipSuper,
    [switch]$WipeData,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

function Fail($msg) { Write-Host "错误: $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

$adb      = Join-Path $PSScriptRoot '..\adb.exe' | Resolve-Path -ErrorAction SilentlyContinue
$adb      = if ($adb) { $adb } else { 'adb.exe' }
$fastboot = 'fastboot.exe'

Step "检查设备"
$devices = & $adb devices | Select-String -Pattern '\s+device$'
if (-not $devices) { Fail "没有找到 adb 设备，确认已开机、已授权调试并解锁 BL" }
$serial = ($devices[0] -split '\s+')[0]
Write-Host "    设备: $serial"

if (-not $Yes) {
    Write-Host ""
    Write-Host "将刷入以下位置的镜像: $Images" -ForegroundColor Yellow
    if ($WipeData) { Write-Host "并会格式化 /data（所有用户数据丢失）" -ForegroundColor Red }
    $answer = Read-Host "确认继续? (yes/no)"
    if ($answer -ne 'yes') { exit 1 }
}

$images = @(
    @{ Part = 'boot';   File = 'boot.img' },
    @{ Part = 'dtbo';   File = 'dtbo.img' },
    @{ Part = 'vbmeta'; File = 'vbmeta.img' }
)
if (-not $SkipSuper) { $images += @{ Part = 'super'; File = 'super.img' } }

foreach ($img in $images) {
    $path = Join-Path $Images $img.File
    if (-not (Test-Path $path)) { Fail "缺少镜像: $path" }
}

Step "重启到 bootloader"
& $adb -s $serial reboot bootloader | Out-Null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    if (& $fastboot devices | Select-String -Pattern '\s+fastboot$') { break }
    if ($i -eq 59) { Fail "设备未进入 fastboot" }
}
Write-Host "    fastboot 就绪"

foreach ($img in $images) {
    $path = Join-Path $Images $img.File
    $size = [math]::Round((Get-Item $path).Length / 1MB, 1)
    Step "刷 $($img.Part) ($size MB)"
    & $fastboot flash $img.Part $path
    if ($LASTEXITCODE -ne 0) { Fail "刷 $($img.Part) 失败" }
}

Step "重启"
& $fastboot reboot
Start-Sleep -Seconds 5

Step "等待开机"
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 5
    $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -eq '1') { Write-Host "    已开机"; break }
    if ($i -eq 89) {
        Write-Host ""
        Write-Host "超过 7 分钟仍未开机。" -ForegroundColor Yellow
        Write-Host "全新安装或从 ColorOS 过来时这是正常现象：/data 是加密的，LOS 读不了。" -ForegroundColor Yellow
        Write-Host "需要进 recovery 格式化 data 后再开机。" -ForegroundColor Yellow
        exit 2
    }
}

if ($WipeData) {
    Step "格式化 data"
    & $adb -s $serial reboot recovery
    Start-Sleep -Seconds 15
    Write-Host "    请在手机上选择 Factory reset -> Format data / factory reset" -ForegroundColor Yellow
    Write-Host "    完成后重启，再用 adb devices 确认" -ForegroundColor Yellow
    exit 0
}

Step "完成"
& $adb -s $serial shell getprop ro.lineage.version
