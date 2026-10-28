@echo off
chcp 65001 >nul
title Mars Concert - NetEase Music API Proxy
cd /d "%~dp0"
echo.
echo  Starting Mars Concert NetEase Music API Proxy...
echo  URL: http://localhost:3000
echo  Keep this window OPEN while using the website.
echo  Press Ctrl+C to stop.
echo.
node app.js
pause
