@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d out @sources.txt
set RC=%errorlevel%
del sources.txt
if not %RC%==0 (
  echo [ERROR] Compile failed.
  pause
  exit /b 1
)
echo [OK] Compile success. Classes in out\ directory.
