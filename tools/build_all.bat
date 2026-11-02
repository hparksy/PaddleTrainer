@echo off
rem Builds both apps (each runs its scoring self-check first) and collects the installers in dist\:
rem   dist\PaddleTrainer-Setup-<VERSION>.exe  (Windows)   dist\PaddleTrainer-<VERSION>.apk  (Android)
setlocal
cd /d "%~dp0.."
set /p VER=<VERSION
call "%~dp0..\desktop\make_installer.bat" || exit /b 1
call "%~dp0..\android\build_apk.bat" || exit /b 1
cd /d "%~dp0.."
if not exist dist mkdir dist
copy /y desktop\build\installer\PaddleTrainer-Setup-%VER%.exe dist\ >nul || exit /b 1
copy /y android\build\PaddleTrainer-%VER%.apk dist\ >nul || exit /b 1
echo.
echo Paddle Trainer %VER%: %CD%\dist
dir /b dist\*%VER%*
