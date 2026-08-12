@echo off
REM Creates a Desktop shortcut for Test-DPLS (bypasses ExecutionPolicy).
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-desktop-shortcut.ps1" %*
if errorlevel 1 pause
