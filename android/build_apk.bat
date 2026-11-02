@echo off
rem Builds android\build\PaddleTrainer.apk: Java self-check first, then Gradle (tools from the one-time setup in %LOCALAPPDATA%\AndroidTools).
setlocal
for /d %%d in ("%LOCALAPPDATA%\AndroidTools\jdk\jdk-17*") do set JAVA_HOME=%%d
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set GRADLE=%LOCALAPPDATA%\AndroidTools\gradle\gradle-8.10.2\bin\gradle.bat
cd /d "%~dp0"
if not exist build mkdir build
"%JAVA_HOME%\bin\javac" -encoding UTF-8 -d build\selfcheck app\src\main\java\com\parksy\paddletrainer\Morse.java app\src\test\java\com\parksy\paddletrainer\MorseTest.java || exit /b 1
"%JAVA_HOME%\bin\java" -cp build\selfcheck com.parksy.paddletrainer.MorseTest || exit /b 1
call "%GRADLE%" -q --console=plain assembleRelease || exit /b 1
copy /y app\build\outputs\apk\release\app-release.apk build\PaddleTrainer.apk >nul || exit /b 1
echo built: %CD%\build\PaddleTrainer.apk
