param(
  [Parameter(Mandatory = $true)]
  [string]$OtaVersion,
  [Parameter(Mandatory = $true)]
  [string]$Model,
  [Parameter(Mandatory = $true)]
  [string]$Carrier,
  [string]$Region = "CN",
  [string]$OutJson = ""
)

$ErrorActionPreference = "Stop"

function Get-InstalledUpdater {
  $cmd = Get-Command updater -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }

  $fallback = Join-Path $env:USERPROFILE "go\bin\updater.exe"
  if (Test-Path $fallback) { return $fallback }

  return $null
}

function Try-BuildRepoUpdater {
  $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
  $binDir = Join-Path $repoRoot "tools\bin"
  $cacheDir = Join-Path $repoRoot "tools\.cache\OplusUpdater"
  $exe = Join-Path $binDir "updater.exe"

  if (Test-Path $exe) {
    return $exe
  }

  $gitCmd = Get-Command git -ErrorAction SilentlyContinue
  if (-not $gitCmd) { return $null }

  $goCmd = Get-Command go -ErrorAction SilentlyContinue
  if (-not $goCmd) {
    $goFallback = "C:\Program Files\Go\bin\go.exe"
    if (Test-Path $goFallback) { $goCmd = $goFallback } else { return $null }
  }

  try {
    New-Item -ItemType Directory -Force $binDir | Out-Null
    New-Item -ItemType Directory -Force (Split-Path $cacheDir) | Out-Null

    if (-not (Test-Path $cacheDir)) {
      & $gitCmd clone --depth 1 https://github.com/SakuraKyuo/OplusUpdater.git $cacheDir *> $null
    }
    else {
      & $gitCmd -C $cacheDir fetch --depth 1 origin *> $null
      & $gitCmd -C $cacheDir reset --hard origin/master *> $null
    }

    & $goCmd -C $cacheDir build -o $exe .\cmd\updater *> $null
    if (Test-Path $exe) { return $exe }
  }
  catch {
    # ignore and fallback to installed updater
  }

  return $null
}

function Strip-Ansi([string]$Text) {
  if (-not $Text) { return $Text }
  return [regex]::Replace($Text, "\x1B\[[0-9;]*[A-Za-z]", "")
}

$updaterCmd = Try-BuildRepoUpdater
if (-not $updaterCmd) {
  $updaterCmd = Get-InstalledUpdater
}
if (-not $updaterCmd) {
  Write-Error "No updater available. Install Go+Git, or run: go install github.com/Houvven/OplusUpdater/cmd/updater@latest"
  exit 1
}

$help = (& $updaterCmd -h 2>&1 | Out-String)

# New CLI supports --json and string mode.
$supportsJson = $help -match "--json"
# Old CLI has --mode int and no --json.
$oldModeInt = $help -match "Mode:\s*0"

if ($supportsJson) {
  $raw = & $updaterCmd $OtaVersion --region $Region --model $Model --carrier $Carrier --mode manual --json
}
elseif ($oldModeInt) {
  $raw = & $updaterCmd $OtaVersion --region $Region --model $Model --carrier $Carrier --mode 0
}
else {
  Write-Error "Unsupported updater CLI format. Please check updater -h output."
  exit 1
}

if (-not $raw) {
  Write-Error "updater returned empty output"
  exit 1
}

$jsonText = Strip-Ansi($raw | Out-String)
$obj = $jsonText | ConvertFrom-Json

if ($obj.responseCode -ne 200) {
  Write-Error ("query failed responseCode={0} errMsg={1}" -f $obj.responseCode, $obj.errMsg)
  exit 1
}

$packet = $obj.body.components[0].componentPackets
$result = [PSCustomObject]@{
  query_ota_version = $OtaVersion
  model             = $Model
  carrier           = $Carrier
  region            = $Region
  ota_version       = $obj.body.otaVersion
  version_name      = $obj.body.versionName
  package_size      = $packet.size
  package_md5       = $packet.md5
  package_url       = $packet.url
  package_manualUrl = $packet.manualUrl
  security_patch    = $obj.body.securityPatch
  query_tool        = $updaterCmd
}

if ($OutJson) {
  $result | ConvertTo-Json -Depth 6 | Set-Content -Path $OutJson -Encoding UTF8
}

$result | Format-List
