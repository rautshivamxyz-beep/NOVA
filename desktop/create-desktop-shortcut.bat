@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command "$target = Join-Path $PSScriptRoot 'start-nova-windows.bat'; if (-not (Test-Path $target)) { throw 'start-nova-windows.bat not found.' }; $desktop = [Environment]::GetFolderPath('Desktop'); $shell = New-Object -ComObject WScript.Shell; $link = $shell.CreateShortcut((Join-Path $desktop 'NOVA Desktop.lnk')); $link.TargetPath = $target; $link.WorkingDirectory = $PSScriptRoot; $link.Description = 'Launch offline NOVA Desktop'; $link.Save(); Write-Host 'NOVA Desktop shortcut created on your desktop.'"
if errorlevel 1 (
  echo Could not create the shortcut.
  pause
  exit /b 1
)
echo.
echo You can now launch NOVA from the desktop shortcut.
pause
