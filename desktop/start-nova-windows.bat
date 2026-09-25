@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

rem ===== NOVA Desktop (Windows) launcher =====

if not exist models mkdir models

set "MODEL="
for %%f in ("models\*.gguf") do if not defined MODEL set "MODEL=%%~ff"

if not defined MODEL (
  echo.
  echo  NOVA Desktop needs a model file (.gguf) in the "models" folder.
  echo.
  echo  I can download one for you now - internet needed, one time only:
  echo    [1] Qwen3 1.7B  -  best answers  -  about 1.1 GB
  echo    [2] Qwen3 0.6B  -  fastest        -  about 0.4 GB
  echo    [3] Skip - I will add a model file myself
  echo.
  choice /C 123 /N /M "Choose 1, 2 or 3: "
  if !errorlevel! EQU 3 goto nostart
  if !errorlevel! EQU 2 (
    set "URL=https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"
    set "FNAME=Qwen3-0.6B-Q4_K_M.gguf"
  ) else (
    set "URL=https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    set "FNAME=Qwen3-1.7B-Q4_K_M.gguf"
  )
  echo.
  echo  Downloading !FNAME! - please wait, this can take a while...
  echo.
  curl -L -C - --progress-bar -o "models\!FNAME!.part" "!URL!"
  if errorlevel 1 (
    echo.
    echo  Download did not finish. Run this file again -
    echo  it will resume where it stopped. If it keeps failing,
    echo  download the model manually with the links in README.md.
    pause
    goto nostart
  )
  ren "models\!FNAME!.part" "!FNAME!"
  set "MODEL=%CD%\models\!FNAME!"
)

echo.
echo  Starting NOVA Desktop...
echo  Model: %MODEL%
echo  Chat will open at http://127.0.0.1:8080
echo  Keep this window open while chatting. Close it to stop NOVA.
echo.
start "" http://127.0.0.1:8080
llama-server.exe -m "%MODEL%" --alias NOVA --path web -c 4096 --port 8080 --host 127.0.0.1
echo.
echo  NOVA stopped.
pause

:nostart
