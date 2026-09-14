@echo off
setlocal EnableExtensions
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Users\Administrator\tools\jdk-17"
if "%ANDROID_SDK_ROOT%"=="" set "ANDROID_SDK_ROOT=C:\Users\Administrator\Android\Sdk"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "GRADLE_HOME=C:\Users\Administrator\tools\gradle-8.11.1"
if "%GRADLE_OPTS%"=="" set "GRADLE_OPTS=-Djava.net.preferIPv4Stack=true"
if not exist "C:\Users\Administrator\tools\java-tmp" mkdir "C:\Users\Administrator\tools\java-tmp"
set "TEMP=C:\Users\Administrator\tools\java-tmp"
set "TMP=C:\Users\Administrator\tools\java-tmp"
set "TMPDIR=C:\Users\Administrator\tools\java-tmp"
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Extracting Gradle 8.11.1...
  powershell -NoProfile -Command "Expand-Archive -LiteralPath 'C:\Users\Administrator\tools\gradle-8.11.1-bin.zip' -DestinationPath 'C:\Users\Administrator\tools' -Force"
)
"%GRADLE_HOME%\bin\gradle.bat" %*
