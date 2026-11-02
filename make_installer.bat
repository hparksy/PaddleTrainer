@echo off
rem Builds the app, collects a clean copy in build\dist (Qt + VC runtime app-local) and packs build\installer\PaddleTrainer-Setup-*.exe
call "%~dp0build.bat" || exit /b 1
cd /d "%~dp0"
if exist build\dist rmdir /s /q build\dist
mkdir build\dist
copy /y build\release\PaddleTrainer.exe build\dist\ >nul || exit /b 1
windeployqt --no-translations --no-compiler-runtime --no-opengl-sw build\dist\PaddleTrainer.exe >nul || exit /b 1
rem VC++ runtime next to the exe, so the target PC needs no redistributable install
copy /y "%VCToolsRedistDir%x64\Microsoft.VC143.CRT\msvcp140*.dll" build\dist\ >nul || exit /b 1
copy /y "%VCToolsRedistDir%x64\Microsoft.VC143.CRT\vcruntime140*.dll" build\dist\ >nul || exit /b 1
set /p VER=<VERSION
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" /Q /DAppVer=%VER% installer.iss || exit /b 1
echo installer: %CD%\build\installer
