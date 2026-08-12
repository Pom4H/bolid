@echo off
REM Creates a Desktop shortcut for Тест-ДПЛС.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-desktop-shortcut.ps1" %*
pause
