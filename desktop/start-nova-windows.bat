@echo off
setlocal EnableExtensions
cd /d "%~dp0"

title NOVA Desktop Launcher

if not exist "llama-server.exe" (
  echo.
  echo  NOVA engine is missing.
  echo  This launcher must be used with the NOVA-Desktop-Windows package.
  echo.
  pause
  exit /b 1
)

if not exist "nova-web\index.html" (
  echo.
  echo  NOVA interface files are missing: nova-web\index.html
  echo.
  pause
  exit /b 1
)

if not exist "models" mkdir "models"

set "MODEL="
for %%F in ("%~dp0models\*.gguf") do (
  if exist "%%~fF" if not defined MODEL set "MODEL=%%~fF"
)

if not defined MODEL (
  echo.
  echo  No local GGUF model was found.
  echo.
  echo  Put one .gguf model file inside:
  echo    %~dp0models
  echo.
  echo  You can copy a model from your phone or download one separately.
  echo  NOVA does not download models or call cloud AI services.
  echo.
  pause
  exit /b 2
)

rem Refuse to expose NOVA on the network. The engine listens only on localhost.
powershell -NoProfile -ExecutionPolicy Bypass -Command "if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 (
  echo.
  echo  Port 8080 is already in use.
  echo  Close the other NOVA instance or change the port in this file.
  echo.
  start "" "http://127.0.0.1:8080"
  exit /b 0
)

echo.
echo  Starting NOVA Desktop...
echo  Model: %MODEL%
echo  Runtime: local GGUF inference
echo  Network access: localhost only
echo.
echo  A separate NOVA Engine window will open.
echo  Keep it running while you chat. Close that window to stop NOVA.
echo.

start "NOVA Engine" /D "%~dp0" "%~dp0llama-server.exe" -m "%MODEL%" --alias NOVA --path "%~dp0nova-web" -c 4096 --port 8080 --host 127.0.0.1 -ngl 99

echo  Waiting for the model to load...
set "READY="
for /L %%I in (1,1,180) do (
  curl.exe --silent --fail "http://127.0.0.1:8080/health" >nul 2>&1
  if not errorlevel 1 (
    set "READY=1"
    goto :ready
  )
  timeout /t 1 /nobreak >nul
)

:ready
if not defined READY (
  echo.
  echo  NOVA did not become ready within 3 minutes.
  echo  Check the NOVA Engine window for the actual error.
  echo.
  pause
  exit /b 3
)

echo.
echo  NOVA is ready. Opening the chat...
start "" "http://127.0.0.1:8080"
exit /b 0
