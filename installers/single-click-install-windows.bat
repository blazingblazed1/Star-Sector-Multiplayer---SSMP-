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
rem 0.9.1.3: make sure the network helper can run here (it prints one line and exits). Nothing else is needed:
rem the helper only uses parts of Windows itself, and Starsector brings its own Java.
"%GAME%\mods\StarMP\bin\smnet.exe" < nul 2>nul | findstr /c:"hello" >nul
if errorlevel 1 (
  echo.
  echo WARNING: the SSMP network helper ^(mods\StarMP\bin\smnet.exe^) could not start.
  echo Hosting and joining will not work until it can. Usually antivirus blocked or removed it:
  echo allow smnet.exe in your antivirus, then run this installer again.
) else (
  echo Network helper check: OK
)
if not exist "%GAME%\mods\StarMP\bin\ssmp-launcher.exe" goto :nolauncher
rem 0.1.5.5: a NEW desktop shortcut "Starsector (SSMP)" (your existing Starsector shortcut is not changed).
rem It opens the SSMP launcher: IROH (online, join code) or LAN (direct / same network only).
rem The icon is taken from starsector.exe itself.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $d 'Starsector (SSMP).lnk')); $s.TargetPath='%GAME%\mods\StarMP\bin\ssmp-launcher.exe'; $s.WorkingDirectory='%GAME%'; $s.IconLocation='%GAME%\starsector.exe,0'; $s.Description='Starsector - SSMP Multiplayer (IROH or LAN)'; $s.Save()" >nul 2>&1
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
