@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem ===== NOVA Desktop (Windows) launcher =====

if not exist models mkdir models

set "MODEL="
for %%f in ("models\*.gguf") do if not defined MODEL set "MODEL=%%~ff"

if not defined MODEL (
  echo.
  echo  NOVA Desktop needs a model file (.gguf) in the "models" folder.
  echo.
  echo  Download one with any browser, for example:
  echo    Qwen3 1.7B ^(recommended^):
  echo      https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf
  echo    Qwen3 0.6B ^(smaller, for weak PCs^):
  echo      https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
  echo.
  echo  Tip: you can also copy a .gguf file from your phone's NOVA
  echo  models folder - they are the exact same files.
  echo.
  pause
  exit /b 1
)

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
