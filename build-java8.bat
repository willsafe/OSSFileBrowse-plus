@echo off
setlocal EnableDelayedExpansion

cd /d "%~dp0"

set "JAVA_VER_LINE="
for /f "delims=" %%i in ('java -version 2^>^&1') do (
  set "JAVA_VER_LINE=%%i"
  goto :gotJavaVer
)
:gotJavaVer

set "JAVAC_VER_LINE="
for /f "delims=" %%i in ('javac -version 2^>^&1') do (
  set "JAVAC_VER_LINE=%%i"
  goto :gotJavacVer
)
:gotJavacVer

set "JAVA8_OK="
echo !JAVA_VER_LINE! | find "1.8." >nul && set "JAVA8_OK=1"
echo !JAVA_VER_LINE! | find "\"8." >nul && set "JAVA8_OK=1"
if not defined JAVA8_OK (
  echo [ERROR] Current java is not Java 8: !JAVA_VER_LINE!
  exit /b 1
)

set "JAVAC8_OK="
echo !JAVAC_VER_LINE! | find "javac 1.8." >nul && set "JAVAC8_OK=1"
echo !JAVAC_VER_LINE! | find "javac 8." >nul && set "JAVAC8_OK=1"
if not defined JAVAC8_OK (
  echo [ERROR] Current javac is not Java 8: !JAVAC_VER_LINE!
  exit /b 1
)

echo [INFO] Java check passed.
echo [INFO] !JAVA_VER_LINE!
echo [INFO] !JAVAC_VER_LINE!

if exist out rmdir /s /q out
if exist target rmdir /s /q target
mkdir out\classes
mkdir target

set "SRC_FILES="
for /r src\main\java %%f in (*.java) do (
  set "SRC_FILES=!SRC_FILES! "%%f""
)

if not defined SRC_FILES (
  echo [ERROR] No Java source files found.
  exit /b 1
)

javac -encoding UTF-8 -source 1.8 -target 1.8 -d out\classes !SRC_FILES!
if errorlevel 1 exit /b 1

xcopy /E /I /Y src\main\resources out\classes >nul
if errorlevel 1 exit /b 1

(
  echo Manifest-Version: 1.0
  echo Main-Class: com.ossfilebrowse.plus.Main
) > out\MANIFEST.MF

jar cfm target\OSSFileBrowse-plus.jar out\MANIFEST.MF -C out\classes .
if errorlevel 1 exit /b 1

echo [OK] Build success: target\OSSFileBrowse-plus.jar
exit /b 0
