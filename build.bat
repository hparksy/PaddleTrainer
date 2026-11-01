@echo off
rem Builds release\PaddleTrainer.exe with Qt 5.15.2 msvc2019_64 + VS2022, runs the keyer self-check first.
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
set PATH=C:\Qt\5.15.2\msvc2019_64\bin;%PATH%
cd /d "%~dp0"
if not exist build mkdir build
cd build
cl /nologo /std:c++17 /EHsc /utf-8 ..\test_morse.cpp >nul || exit /b 1
.\test_morse.exe || exit /b 1
qmake ..\PaddleTrainer.pro CONFIG+=release || exit /b 1
nmake /nologo || exit /b 1
windeployqt --no-translations --no-compiler-runtime release\PaddleTrainer.exe >nul || exit /b 1
echo built: %CD%\release\PaddleTrainer.exe
