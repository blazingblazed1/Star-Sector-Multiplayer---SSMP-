@echo off
title SSMP multibox test copies
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\multibox.ps1"
pause
