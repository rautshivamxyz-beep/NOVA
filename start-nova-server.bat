@echo off
rem ===================================================================
rem  NOVA model server - double-click this file on your laptop.
rem  It starts the model. Your phone finds the laptop by itself.
rem  Keep this window open while you use NOVA. Close it to stop.
rem ===================================================================
setlocal
cd /d "%~dp0"

echo.
echo   NOVA model server
echo   -----------------
echo.

rem ---- find llama-server.exe -----------------------------------------
set "SERVER="
if exist "%~dp0llama-server.exe" set "SERVER=%~dp0llama-server.exe"
if not defined SERVER if exist "%~dp0llama\llama-server.exe" set "SERVER=%~dp0llama\llama-server.exe"
if not defined SERVER if exist "%~dp0bin\llama-server.exe" set "SERVER=%~dp0bin\llama-server.exe"
if not defined SERVER for /r "%~dp0" %%f in (llama-server.exe) do if not defined SERVER set "SERVER=%%f"

if not defined SERVER (
  echo   I could not find llama-server.exe next to this file.
  echo.
  echo   1^) Opening the download page in your browser...
  echo   2^) Download the WINDOWS zip.
  echo      - If your laptop has an NVIDIA graphics card, pick the one
  echo        with "cuda" in the name ^(much faster^).
  echo      - Otherwise pick the plain one ^(avx2^).
  echo   3^) Unzip it INTO THIS FOLDER, so llama-server.exe sits
  echo      right next to this file.
  echo   4^) Double-click this file again.
  echo.
  start "" "https://github.com/ggml-org/llama.cpp/releases"
  pause
  exit /b 1
)

rem ---- find the model (.gguf) ----------------------------------------
set "MODEL="
if not defined MODEL if exist "%~dp0models" for %%f in ("%~dp0models\*.gguf") do if not defined MODEL set "MODEL=%%f"
if not defined MODEL for %%f in ("%~dp0*.gguf") do if not defined MODEL set "MODEL=%%f"

if not defined MODEL (
  echo   I found the server, but no model file.
  echo.
  echo   A model is one big file ending in .gguf ^(about 2 to 5 GB^).
  echo   1^) Opening a page where you can download one...
  echo   2^) Save it into a folder called "models" next to this file.
  echo   3^) Double-click this file again.
  echo.
  start "" "https://huggingface.co/models"
  pause
  exit /b 1
)

echo   Server : %SERVER%
echo   Model  : %MODEL%
echo.

rem ---- optional password ---------------------------------------------
rem  If a file called password.txt sits next to this one, its contents
rem  become the server's password. Only needed if you reach the server
rem  from OUTSIDE your home Wi-Fi. Type the same password into NOVA.
set "KEY="
if exist "%~dp0password.txt" set /p KEY=<"%~dp0password.txt"
if defined KEY echo   Password: on ^(from password.txt^)

rem ---- let the phone through the firewall (ignored if not admin) ------
netsh advfirewall firewall add rule name="NOVA model server" dir=in action=allow protocol=TCP localport=8080 >nul 2>&1

echo   Starting. Your phone can now find this laptop.
echo   Leave this window OPEN while you use NOVA.
echo.

if defined KEY (
  "%SERVER%" -m "%MODEL%" --host 0.0.0.0 --port 8080 -c 8192 --api-key "%KEY%"
) else (
  "%SERVER%" -m "%MODEL%" --host 0.0.0.0 --port 8080 -c 8192
)

echo.
echo   The server stopped.
pause
