@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command "$target = Join-Path $PSScriptRoot 'NOVA.exe'; if (-not (Test-Path $target)) { throw 'NOVA.exe not found. Extract the desktop package first.' }; $desktop = [Environment]::GetFolderPath('Desktop'); $shell = New-Object -ComObject WScript.Shell; $link = $shell.CreateShortcut((Join-Path $desktop 'NOVA.lnk')); $link.TargetPath = $target; $link.WorkingDirectory = $PSScriptRoot; $link.Description = 'Launch offline NOVA Desktop'; $link.Save(); Write-Host 'NOVA shortcut created.'"
if errorlevel 1 (
  echo Could not create the shortcut.
  pause
  exit /b 1
)
