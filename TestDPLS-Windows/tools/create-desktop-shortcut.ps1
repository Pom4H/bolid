# Create a Desktop shortcut for Test-DPLS (Windows WPF client).
# Run:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create-desktop-shortcut.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create-desktop-shortcut.ps1 -Publish
#
# Requires .NET 8 SDK: https://dotnet.microsoft.com/download/dotnet/8.0

param(
    [switch]$Publish,
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [string]$ExePath = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Project = Join-Path $Root "src\TestDPLS\TestDPLS.csproj"

function Test-DotNetSdk {
    try {
        $null = & dotnet --list-sdks 2>$null
        if ($LASTEXITCODE -ne 0) { return $false }
        $sdks = & dotnet --list-sdks 2>$null
        return ($sdks | Where-Object { $_ -match '^8\.' }).Count -gt 0
    } catch {
        return $false
    }
}

if ($ExePath -ne "") {
    $Target = $ExePath
} elseif ($Publish) {
    if (-not (Test-DotNetSdk)) {
        throw @"
.NET 8 SDK is not installed.
Install from: https://dotnet.microsoft.com/download/dotnet/8.0
Then reopen PowerShell and run this script again.
"@
    }
    $OutDir = Join-Path $Root "artifacts\$Runtime"
    Write-Host "Publishing to $OutDir ..."
    & dotnet publish $Project -c $Configuration -r $Runtime --self-contained true -o $OutDir
    if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE" }
    $Target = Join-Path $OutDir "TestDPLS.exe"
} else {
    $DefaultTarget = Join-Path $Root "src\TestDPLS\bin\$Configuration\net8.0-windows10.0.19041.0\TestDPLS.exe"
    if (Test-Path $DefaultTarget) {
        $Target = $DefaultTarget
    } else {
        if (-not (Test-DotNetSdk)) {
            throw @"
.NET 8 SDK is not installed, so the app cannot be built.
1) Install SDK: https://dotnet.microsoft.com/download/dotnet/8.0
   (choose .NET 8 SDK x64 installer)
2) Close and reopen PowerShell
3) Run again:
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create-desktop-shortcut.ps1
"@
        }
        Write-Host "Building $Configuration ..."
        & dotnet build $Project -c $Configuration
        if ($LASTEXITCODE -ne 0) { throw "dotnet build failed with exit code $LASTEXITCODE" }
        $Target = $DefaultTarget
    }
}

if (-not (Test-Path $Target)) {
    throw "Executable not found: $Target"
}

$Desktop = [Environment]::GetFolderPath("Desktop")
$ShortcutPath = Join-Path $Desktop "Test-DPLS.lnk"
$WorkDir = Split-Path -Parent $Target

$Wsh = New-Object -ComObject WScript.Shell
$Shortcut = $Wsh.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $Target
$Shortcut.WorkingDirectory = $WorkDir
$Shortcut.WindowStyle = 1
$Shortcut.Description = "Test-DPLS BLE client"
$Shortcut.Save()

Write-Host "Shortcut created: $ShortcutPath"
Write-Host "Target: $Target"
