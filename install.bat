@echo off
setlocal

:: ── paths ────────────────────────────────────────────────────────────────────
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set APK=app\build\outputs\apk\debug\app-debug.apk
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

:: ── check device ─────────────────────────────────────────────────────────────
echo Checking for connected device...
"%ADB%" devices | findstr /v "List of" | findstr "device" >nul
if errorlevel 1 (
    echo ERROR: No Android device connected.
    echo   1. Connect your phone via USB
    echo   2. Enable USB Debugging: Settings ^> Developer Options ^> USB Debugging
    echo   3. Accept the RSA fingerprint dialog on your phone
    pause & exit /b 1
)

:: ── build ────────────────────────────────────────────────────────────────────
echo Building...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo BUILD FAILED. Check errors above.
    pause & exit /b 1
)

:: ── install ──────────────────────────────────────────────────────────────────
echo Uninstalling old version...
"%ADB%" uninstall com.arcisai.nvr 2>nul

echo Installing new version...
"%ADB%" install "%APK%"
if errorlevel 1 (
    echo INSTALL FAILED.
    pause & exit /b 1
)

echo.
echo Done! Launch Arcis NVR on your phone.
pause
