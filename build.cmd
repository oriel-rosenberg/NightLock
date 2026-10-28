@echo off
rem Builds NightLock with the portable toolchain in C:\Dev\android-toolchain.
rem Usage: build.cmd [gradle tasks and -P flags]   (default: assembleRelease)
rem   build.cmd                                         release APK  -> nightlock.apk
rem   build.cmd assembleRelease -Pnightlock.testMode=true   same, with TEST_MODE durations
rem   build.cmd testDebugUnitTest                       unit tests
rem Only the release APK is copied here; a debug build stays in %BUILD_ROOT%\app\outputs\apk\debug.
setlocal
if "%NIGHTLOCK_TOOLCHAIN%"=="" set "NIGHTLOCK_TOOLCHAIN=C:\Dev\android-toolchain"
set "JAVA_HOME=%NIGHTLOCK_TOOLCHAIN%\jdk"
set "ANDROID_HOME=%NIGHTLOCK_TOOLCHAIN%\sdk"
set "ANDROID_USER_HOME=%NIGHTLOCK_TOOLCHAIN%\android-user"
set "GRADLE_USER_HOME=%NIGHTLOCK_TOOLCHAIN%\gradle-home"
set "BUILD_ROOT=%NIGHTLOCK_TOOLCHAIN%\build\nightlock"
rem The private signing key. Back this folder up: without it, updates can't install over the app on the phone.
set "KEYSTORE=%NIGHTLOCK_TOOLCHAIN%\keys\nightlock\keystore.properties"

set "TASKS=%*"
if "%TASKS%"=="" set "TASKS=assembleRelease"

call "%~dp0gradlew.bat" -p "%~dp0." "-Pnightlock.buildRoot=%BUILD_ROOT%" "-Pnightlock.keystore=%KEYSTORE%" %TASKS%
if errorlevel 1 exit /b 1

set "OUT=%BUILD_ROOT%\app\outputs\apk\release"
if exist "%OUT%\app-release-unsigned.apk" (
    echo ERROR: release APK is unsigned: "%KEYSTORE%" is missing. Not copying it.
    del "%OUT%\app-release-unsigned.apk"
    exit /b 1
)

rem Only hand over an APK from a full assembleRelease run: that's the task that runs the unit tests and
rem the manifest check first. Skipping tasks (-x) or other packaging tasks don't count.
echo(%TASKS%| findstr /i /c:"assembleRelease" >nul || goto :done
echo(%TASKS%| findstr /i /c:" -x " /c:"--exclude-task" >nul && (
    echo NOTE: tasks were skipped, so nightlock.apk was not updated.
    goto :done
)
if exist "%OUT%\app-release.apk" copy /y "%OUT%\app-release.apk" "%~dp0nightlock.apk" >nul

:done
endlocal
