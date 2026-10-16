@echo off
setlocal
title StarMP installer
set "SRC=%~dp0StarMP"
set "GAME="
for %%D in ("%ProgramFiles(x86)%\Fractal Softworks\Starsector" "%ProgramFiles%\Fractal Softworks\Starsector" "C:\Games\Starsector" "D:\Starsector" "%USERPROFILE%\Starsector") do (
  if not defined GAME if exist "%%~D\starsector-core" set "GAME=%%~D"
)
if not defined GAME (
  echo Could not find Starsector automatically.
  set /p GAME=Paste your Starsector folder path ^(the one containing starsector-core^): 
)
if not exist "%GAME%\starsector-core" (
  echo That folder does not contain starsector-core. Nothing installed.
  pause & exit /b 1
)
if not exist "%GAME%\mods" mkdir "%GAME%\mods"
echo Installing to "%GAME%\mods\StarMP" ...
xcopy "%SRC%" "%GAME%\mods\StarMP\" /E /I /Y /Q >nul
if errorlevel 1 (
  echo Copy failed. If Starsector is in Program Files, right-click this file and choose "Run as administrator".
  pause & exit /b 1
)
echo.
echo Done. Open the Starsector launcher, tick StarMP under Mods, and start the game.
echo If Windows Firewall asks about smnet.exe, click Allow.
pause
