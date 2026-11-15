@echo off
setlocal
cd /d "%~dp0"
where python >nul 2>nul
if %errorlevel% equ 0 (
    python cli\gui.py %*
    exit /b %errorlevel%
)
echo Python 3 (with tkinter) was not found in PATH. Download the standalone Windows EXE instead.
pause
exit /b 1
