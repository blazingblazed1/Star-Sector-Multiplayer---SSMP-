@echo off
setlocal
title SSMP installer
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
if not exist "%GAME%\mods\StarMP\bin\ssmp-launcher.exe" goto :nolauncher
rem 0.1.5.5: a NEW desktop shortcut "Starsector (SSMP)" (your existing Starsector shortcut is not changed).
rem It opens the SSMP launcher: Single Player (SSMP off) or Multiplayer (SSMP on, crash logger starts first).
rem The icon is taken from starsector.exe itself.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'Starsector (SSMP).lnk')); $s.TargetPath='%GAME%\mods\StarMP\bin\ssmp-launcher.exe'; $s.WorkingDirectory='%GAME%'; $s.IconLocation='%GAME%\starsector.exe,0'; $s.Description='Starsector - Single Player or SSMP Multiplayer'; $s.Save()" >nul 2>&1
if errorlevel 1 (
  echo Could not create the desktop shortcut. You can start "%GAME%\mods\StarMP\bin\ssmp-launcher.exe" yourself.
) else (
  echo Created the desktop shortcut "Starsector (SSMP)".
)
echo.
echo Done. Start the game with the "Starsector (SSMP)" shortcut on your desktop.
goto :end
:nolauncher
echo.
echo Done. Open the Starsector launcher, tick StarMP under Mods, and start the game.
:end
echo If Windows Firewall asks about smnet.exe, click Allow.
pause
