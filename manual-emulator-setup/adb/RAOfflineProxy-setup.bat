@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=patch"
if /I "%ACTION%"=="patch" shift
if /I "%ACTION%"=="revert" shift

if /I not "%ACTION%"=="patch" if /I not "%ACTION%"=="revert" (
    echo Usage: RAOfflineProxy-setup.bat {patch^|revert}
    exit /b 1
)

set "CONFIG_PATH=/storage/emulated/0/Android/data/com.raofflineproxy/files/manual-emulator-setup/adb-config.json"
set "CONFIG_JSON="

for /f "delims=" %%J in ('adb shell "if [ -f '%CONFIG_PATH%' ]; then cat '%CONFIG_PATH%'; fi" 2^>nul') do set "CONFIG_JSON=!CONFIG_JSON!%%J"
if not defined CONFIG_JSON (
    echo Could not find RAOfflineProxy manual setup config at %CONFIG_PATH%
    echo Open RAOfflineProxy once so it can export the ADB setup config file.
    exit /b 1
)

for /f "delims=" %%P in ('powershell -NoProfile -Command "$json = @'
%CONFIG_JSON%
'@ | ConvertFrom-Json; if ($json.proxyPort) { $json.proxyPort }"') do set "PORT=%%P"
for /f "delims=" %%E in ('powershell -NoProfile -Command "$json = @'
%CONFIG_JSON%
'@ | ConvertFrom-Json; if ($json.enabledEmulators) { if ($json.enabledEmulators -is [string]) { $json.enabledEmulators.Trim('[',']') } else { ($json.enabledEmulators -join ',') } }"') do set "EMULATORS=%%E"

if not defined PORT (
    echo RAOfflineProxy exported config is missing the proxy port.
    exit /b 1
)

if not defined EMULATORS (
    echo RAOfflineProxy exported config is missing the enabled emulators.
    exit /b 1
)

if /I "%ACTION%"=="revert" set "EMULATORS=retroarch,dolphin"

set "RETROARCH_TARGET1=/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"
set "RETROARCH_TARGET2=/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg"
set "RETROARCH_TARGET3=/storage/emulated/0/RetroArch/retroarch.cfg"
set "RETROARCH_PACKAGE1=com.retroarch.aarch64"
set "RETROARCH_PACKAGE2=com.retroarch"
set "DOLPHIN_TARGET1=/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/RetroAchievements.ini"
set "DOLPHIN_TARGET2=/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu.beta/files/Config/RetroAchievements.ini"
set "DOLPHIN_TARGET3=/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu.debug/files/Config/RetroAchievements.ini"
set "DOLPHIN_TARGET4=/storage/emulated/0/dolphin-emu/Config/RetroAchievements.ini"
set "DOLPHIN_PACKAGE1=org.dolphinemu.dolphinemu"
set "DOLPHIN_PACKAGE2=org.dolphinemu.dolphinemu.beta"
set "DOLPHIN_PACKAGE3=org.dolphinemu.dolphinemu.debug"
set "TMPDIR=%TEMP%\raofflineproxy-adb"
set "STATUS=0"

where adb >nul 2>nul
if errorlevel 1 (
    echo adb was not found in PATH.
    echo Install Android platform-tools and try again.
    exit /b 1
)

if not exist "%TMPDIR%" mkdir "%TMPDIR%"

call :has_emulator retroarch
if "%HAS_EMULATOR%"=="1" (
    call :stop_package_if_running "%RETROARCH_PACKAGE1%" "RetroArch"
    call :stop_package_if_running "%RETROARCH_PACKAGE2%" "RetroArch"
    if /I "%ACTION%"=="patch" (
        call :patch_retroarch
    ) else (
        call :revert_retroarch
    )
)

call :has_emulator dolphin
if "%HAS_EMULATOR%"=="1" (
    call :stop_package_if_running "%DOLPHIN_PACKAGE1%" "Dolphin"
    call :stop_package_if_running "%DOLPHIN_PACKAGE2%" "Dolphin"
    call :stop_package_if_running "%DOLPHIN_PACKAGE3%" "Dolphin"
    if /I "%ACTION%"=="patch" (
        call :patch_dolphin
    ) else (
        call :revert_dolphin
    )
)

rd /s /q "%TMPDIR%" >nul 2>nul
exit /b %STATUS%

:has_emulator
set "HAS_EMULATOR=0"
echo ,%EMULATORS%, | findstr /I /C:",%~1," >nul && set "HAS_EMULATOR=1"
goto :eof

:stop_package_if_running
set "PACKAGE=%~1"
set "EMULATOR_NAME=%~2"
set "PACKAGE_RUNNING="
for /f %%P in ('adb shell pidof "%PACKAGE%" 2^>nul') do set "PACKAGE_RUNNING=%%P"
if defined PACKAGE_RUNNING (
    echo %EMULATOR_NAME% is running. Closing %PACKAGE% before %ACTION%.
    adb shell am force-stop "%PACKAGE%" >nul
    timeout /t 1 /nobreak >nul
)
goto :eof

:patch_retroarch
set "PATCHED=0"
call :patch_retroarch_target "%RETROARCH_TARGET1%"
if "%PATCHED%"=="1" goto :eof
call :patch_retroarch_target "%RETROARCH_TARGET2%"
if "%PATCHED%"=="1" goto :eof
call :patch_retroarch_target "%RETROARCH_TARGET3%"
if "%PATCHED%"=="1" goto :eof
echo Could not find RetroArch config in any supported location.
set "STATUS=1"
goto :eof

:patch_retroarch_target
set "TARGET=%~1"
set "TMPFILE=%TMPDIR%\retroarch.cfg"
adb shell "test -f '%TARGET%'"
if errorlevel 1 goto :eof
echo Found RetroArch config at %TARGET%
adb pull "%TARGET%" "%TMPFILE%" >nul
if errorlevel 1 (
    echo Failed to pull %TARGET%
    set "STATUS=1"
    goto :eof
)
call :ensure_line "%TMPFILE%" "cheevos_custom_host" "127.0.0.1:%PORT%"
call :ensure_line "%TMPFILE%" "cheevos_hardcore_mode_enable" "false"
adb push "%TMPFILE%" "%TARGET%" >nul
if errorlevel 1 (
    echo Failed to push patched RetroArch config back to device.
    set "STATUS=1"
    goto :eof
)
set "PATCHED=1"
echo Patched RetroArch for port %PORT%.
goto :eof

:revert_retroarch
set "REVERTED=0"
call :revert_retroarch_target "%RETROARCH_TARGET1%"
if "%REVERTED%"=="1" goto :eof
call :revert_retroarch_target "%RETROARCH_TARGET2%"
if "%REVERTED%"=="1" goto :eof
call :revert_retroarch_target "%RETROARCH_TARGET3%"
if "%REVERTED%"=="1" goto :eof
echo Could not find RetroArch config in any supported location.
set "STATUS=1"
goto :eof

:revert_retroarch_target
set "TARGET=%~1"
set "TMPFILE=%TMPDIR%\retroarch.cfg"
adb shell "test -f '%TARGET%'"
if errorlevel 1 goto :eof
echo Found RetroArch config at %TARGET%
adb pull "%TARGET%" "%TMPFILE%" >nul
if errorlevel 1 (
    echo Failed to pull %TARGET%
    set "STATUS=1"
    goto :eof
)
call :replace_line "%TMPFILE%" "cheevos_custom_host" ""
adb push "%TMPFILE%" "%TARGET%" >nul
if errorlevel 1 (
    echo Failed to push reverted RetroArch config back to device.
    set "STATUS=1"
    goto :eof
)
set "REVERTED=1"
echo Reverted RetroArch proxy host.
goto :eof

:patch_dolphin
set "PATCHED=0"
call :patch_dolphin_target "%DOLPHIN_TARGET1%"
if "%PATCHED%"=="1" goto :eof
call :patch_dolphin_target "%DOLPHIN_TARGET2%"
if "%PATCHED%"=="1" goto :eof
call :patch_dolphin_target "%DOLPHIN_TARGET3%"
if "%PATCHED%"=="1" goto :eof
call :patch_dolphin_target "%DOLPHIN_TARGET4%"
if "%PATCHED%"=="1" goto :eof
echo Could not find Dolphin config in any supported location.
set "STATUS=1"
goto :eof

:patch_dolphin_target
set "TARGET=%~1"
set "TMPFILE=%TMPDIR%\RetroAchievements.ini"
adb shell "test -f '%TARGET%'"
if errorlevel 1 goto :eof
echo Found Dolphin config at %TARGET%
adb pull "%TARGET%" "%TMPFILE%" >nul
if errorlevel 1 (
    echo Failed to pull %TARGET%
    set "STATUS=1"
    goto :eof
)
call :ensure_line "%TMPFILE%" "HostUrl" "127.0.0.1:%PORT%"
call :ensure_line "%TMPFILE%" "HardcoreEnabled" "False"
adb push "%TMPFILE%" "%TARGET%" >nul
if errorlevel 1 (
    echo Failed to push patched Dolphin config back to device.
    set "STATUS=1"
    goto :eof
)
set "PATCHED=1"
echo Patched Dolphin for port %PORT%.
goto :eof

:revert_dolphin
set "REVERTED=0"
call :revert_dolphin_target "%DOLPHIN_TARGET1%"
if "%REVERTED%"=="1" goto :eof
call :revert_dolphin_target "%DOLPHIN_TARGET2%"
if "%REVERTED%"=="1" goto :eof
call :revert_dolphin_target "%DOLPHIN_TARGET3%"
if "%REVERTED%"=="1" goto :eof
call :revert_dolphin_target "%DOLPHIN_TARGET4%"
if "%REVERTED%"=="1" goto :eof
echo Could not find Dolphin config in any supported location.
set "STATUS=1"
goto :eof

:revert_dolphin_target
set "TARGET=%~1"
set "TMPFILE=%TMPDIR%\RetroAchievements.ini"
adb shell "test -f '%TARGET%'"
if errorlevel 1 goto :eof
echo Found Dolphin config at %TARGET%
adb pull "%TARGET%" "%TMPFILE%" >nul
if errorlevel 1 (
    echo Failed to pull %TARGET%
    set "STATUS=1"
    goto :eof
)
call :replace_line "%TMPFILE%" "HostUrl" ""
adb push "%TMPFILE%" "%TARGET%" >nul
if errorlevel 1 (
    echo Failed to push reverted Dolphin config back to device.
    set "STATUS=1"
    goto :eof
)
set "REVERTED=1"
echo Reverted Dolphin proxy host.
goto :eof

:ensure_line
powershell -NoProfile -Command ^
    "$path = [System.IO.Path]::GetFullPath('%~1');" ^
    "$key = '%~2';" ^
    "$value = '%~3';" ^
    "$content = Get-Content -Raw -Path $path;" ^
    "$replacement = $key + ' = \"' + $value + '\"';" ^
    "$content = [regex]::Replace($content, ('^[\t ]*' + [regex]::Escape($key) + '[\t ]*=.*$'), $replacement, 'Multiline');" ^
    "if ($content -notmatch ('(?m)^[\t ]*' + [regex]::Escape($key) + '[\t ]*=')) { $content = $content.TrimEnd() + [Environment]::NewLine + $replacement + [Environment]::NewLine }" ^
    "Set-Content -Path $path -Value $content"
if errorlevel 1 set "STATUS=1"
goto :eof

:replace_line
powershell -NoProfile -Command ^
    "$path = [System.IO.Path]::GetFullPath('%~1');" ^
    "$key = '%~2';" ^
    "$value = '%~3';" ^
    "$content = Get-Content -Raw -Path $path;" ^
    "$replacement = $key + ' = \"' + $value + '\"';" ^
    "$content = [regex]::Replace($content, ('^[\t ]*' + [regex]::Escape($key) + '[\t ]*=.*$'), $replacement, 'Multiline');" ^
    "Set-Content -Path $path -Value $content"
if errorlevel 1 set "STATUS=1"
goto :eof
