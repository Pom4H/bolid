# Create a Desktop shortcut for Тест-ДПЛС (Windows WPF client).
# Run from PowerShell after building or publishing the app:
#
#   .\tools\create-desktop-shortcut.ps1
#   .\tools\create-desktop-shortcut.ps1 -Publish

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
    throw "Не найден исполняемый файл: $Target"
}

$Desktop = [Environment]::GetFolderPath("Desktop")
$ShortcutPath = Join-Path $Desktop "Тест-ДПЛС.lnk"
$WorkDir = Split-Path -Parent $Target

$Wsh = New-Object -ComObject WScript.Shell
$Shortcut = $Wsh.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $Target
$Shortcut.WorkingDirectory = $WorkDir
$Shortcut.WindowStyle = 1
$Shortcut.Description = "Тест-ДПЛС — BLE-клиент управления ДПЛС"
$Shortcut.Save()

Write-Host "Ярлык создан: $ShortcutPath"
Write-Host "Цель: $Target"
