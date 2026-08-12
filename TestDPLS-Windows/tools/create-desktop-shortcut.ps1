# Create a Desktop shortcut for Test-DPLS (Windows WPF client).
# Run:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create-desktop-shortcut.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\create-desktop-shortcut.ps1 -Publish

param(
    [switch]$Publish,
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Project = Join-Path $Root "src\TestDPLS\TestDPLS.csproj"

if ($Publish) {
    $OutDir = Join-Path $Root "artifacts\$Runtime"
    Write-Host "Publishing to $OutDir ..."
    dotnet publish $Project -c $Configuration -r $Runtime --self-contained true -o $OutDir
    $Target = Join-Path $OutDir "TestDPLS.exe"
} else {
    Write-Host "Building $Configuration ..."
    dotnet build $Project -c $Configuration
    $Target = Join-Path $Root "src\TestDPLS\bin\$Configuration\net8.0-windows10.0.19041.0\TestDPLS.exe"
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
