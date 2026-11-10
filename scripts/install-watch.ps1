<#
.SYNOPSIS
Builds AbsorbWear and installs it on the paired watch in one go.

.DESCRIPTION
Builds the requested flavor (dev by default, which pairs with the Absorb Dev
phone install), connects to the watch over wireless ADB when an address is
given, installs the APK, and launches the app. A signature mismatch from an
older install is handled by uninstalling first.

.PARAMETER Flavor
"dev" (com.barnabas.absorb.dev, pairs with Absorb Dev) or "github"
(com.barnabas.absorb, pairs with the stable Absorb).

.PARAMETER Watch
The watch's wireless-debugging address, e.g. 192.168.1.23:5555. Falls back to
the ABSORB_WATCH environment variable, then to whatever watch adb already sees.
The first time, pair on the watch (Settings > Developer options > Wireless
debugging > Pair new device) and run "adb pair <ip>:<pairing-port>" once.

.PARAMETER NoBuild
Install the APK from the last build instead of building again.

.EXAMPLE
.\scripts\install-watch.ps1
.\scripts\install-watch.ps1 -Watch 192.168.1.23:5555
.\scripts\install-watch.ps1 -Flavor github -NoBuild
#>
param(
    [ValidateSet("dev", "github")]
    [string]$Flavor = "dev",
    [ValidateSet("release", "debug")]
    [string]$BuildType = "release",
    [string]$Watch = $env:ABSORB_WATCH,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$adb = "C:\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }

$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

# Release by default: debug builds of Compose are far slower on a watch CPU
# and make scrolling look broken when it is not.
$variant = $Flavor.Substring(0, 1).ToUpper() + $Flavor.Substring(1) + $BuildType.Substring(0, 1).ToUpper() + $BuildType.Substring(1)
$apk = Join-Path $repo "app\build\outputs\apk\$Flavor\$BuildType\app-$Flavor-$BuildType.apk"
$package = if ($Flavor -eq "dev") { "com.barnabas.absorb.dev" } else { "com.barnabas.absorb" }

if (-not $NoBuild) {
    Write-Host "Building $variant..." -ForegroundColor Cyan
    & .\gradlew.bat ":app:assemble$variant" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}
if (-not (Test-Path $apk)) { throw "No APK at $apk. Build first (drop -NoBuild)." }

if ($Watch) {
    Write-Host "Connecting to $Watch..." -ForegroundColor Cyan
    & $adb connect $Watch | Out-Host
}

# Pick the watch among whatever adb sees, so a plugged-in phone is never the target.
$serials = & $adb devices | Select-String "\sdevice$" | ForEach-Object { ($_ -split "\s+")[0] }
if (-not $serials) { throw "No device online. Turn on Wireless debugging on the watch and pass -Watch <ip:port>." }
$watchSerial = $null
foreach ($s in $serials) {
    $kind = (& $adb -s $s shell getprop ro.build.characteristics 2>$null) -join ""
    if ($kind -match "watch") { $watchSerial = $s; break }
}
if (-not $watchSerial) {
    if ($serials.Count -eq 1) { $watchSerial = $serials[0] }
    else { throw "Several devices online but none reports itself as a watch: $($serials -join ', '). Pass -Watch." }
}
Write-Host "Installing $package on $watchSerial..." -ForegroundColor Cyan

$out = & $adb -s $watchSerial install -r $apk 2>&1 | Out-String
if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE") {
    Write-Host "Existing install is signed differently or newer; replacing it." -ForegroundColor Yellow
    & $adb -s $watchSerial uninstall $package | Out-Host
    $out = & $adb -s $watchSerial install -r $apk 2>&1 | Out-String
}
Write-Host $out.Trim()
if ($out -notmatch "Success") { throw "Install failed" }

& $adb -s $watchSerial shell am start -n "$package/com.barnabas.absorb.wear.MainActivity" | Out-Null
Write-Host "Done. $package is running on the watch." -ForegroundColor Green
