@echo off
rem Downloads the newest ATLauncher-Enhanced test build (made by GitHub on every push to a feature/fix branch)
rem and runs it on your installed ATLauncher's folder, so it sees the same instances.
rem Close the real ATLauncher first: both launchers write the same files.
rem Extra arguments go to the launcher, e.g. --debug --debug-level=5 logs how long each step takes.
setlocal

set "DATA=%APPDATA%\ATLauncher"
set "CACHE=%LOCALAPPDATA%\ATLauncher-Enhanced"
set "JAR=%CACHE%\ATLauncher-Enhanced.jar"
set "URL=https://github.com/TooCoolCello/ATLauncher-Enhanced/releases/download/test-build/ATLauncher-Enhanced.jar"

if not exist "%DATA%" (
    echo No ATLauncher folder at "%DATA%".
    pause
    exit /b 1
)

tasklist /fi "imagename eq ATLauncher.exe" | find /i "ATLauncher.exe" >nul
if not errorlevel 1 (
    echo The real ATLauncher is open. Close it first, then run this again.
    pause
    exit /b 1
)

if not exist "%CACHE%" mkdir "%CACHE%"

echo Downloading the newest test build...
curl.exe -fsSL -o "%JAR%.download" "%URL%"
if errorlevel 1 (
    del "%JAR%.download" 2>nul
    if not exist "%JAR%" (
        echo Download failed and there is no earlier build to fall back on.
        pause
        exit /b 1
    )
    echo Download failed. Starting the last build you downloaded instead.
) else (
    move /y "%JAR%.download" "%JAR%" >nul
)

rem ATLauncher's own bundled Java, else whatever Java is on PATH
set "JAVA=%DATA%\jre\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=javaw"

start "" "%JAVA%" -Djna.nosys=true -Djava.net.preferIPv4Stack=true -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true -jar "%JAR%" "--working-dir=%DATA%" --no-launcher-update %*
