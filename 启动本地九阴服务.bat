@echo off
setlocal EnableExtensions
title Nine Yin Java Server

set "ROOT=%~dp0"
set "JAR=%ROOT%nineyin-server-0.1.0-SNAPSHOT.jar"
set "GMJAR=%ROOT%nineyin-gm-0.1.0-SNAPSHOT.jar"
set "LOGDIR=%ROOT%logs"

rem Share root / role data live beside this script, same layout as the Go build.
set "NINEYIN_SERVER_ROOT=%ROOT%"
rem Role store MySQL DSN; comment out to use the built-in default (JSON fallback when MySQL is down).
set "NINEYIN_MYSQL_DSN=nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin"

if not exist "%JAR%" (
  echo ERROR: server jar not found:
  echo %JAR%
  pause
  exit /b 1
)
if not exist "%ROOT%resources\modern\share" (
  echo ERROR: runtime resources not found:
  echo %ROOT%resources\modern\share
  pause
  exit /b 1
)
if not exist "%GMJAR%" (
  echo ERROR: gm-web panel jar not found:
  echo %GMJAR%
  pause
  exit /b 1
)

set "JAVA_CMD="
for /f "delims=" %%i in ('where java 2^>nul') do (
  if not defined JAVA_CMD set "JAVA_CMD=%%i"
)
if not defined JAVA_CMD (
  echo ERROR: java not found on PATH ^(JDK 25+ required^).
  pause
  exit /b 1
)
if not exist "%LOGDIR%" mkdir "%LOGDIR%"

rem Game server: game 19061 + svrlist 4000 + GM RPC 19081 (single process).
rem gm-web panel: console 19080 + framework port 19083 (separate process, talks to game via summer RPC 19081).
rem Port probe: real TCP connect, NOT connection-table lookups. The JVM's IPv6
rem dual-mode listeners show State=0 in Get-NetTCPConnection/netstat (invisible
rem to "-State Listen"), and after the process dies those State=0 entries linger
rem as zombie TCBs - a table query cannot tell a live listener from a dead one.
rem A successful connect means a live service; refused means free to bind.
powershell.exe -NoProfile -Command "$try = { param($n) $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', $n); $true } catch { $false } finally { $c.Close() } }; if ((& $try 19061) -and (& $try 19080)) { exit 0 } else { exit 1 }"
set "RUNNING=%ERRORLEVEL%"
if "%RUNNING%"=="0" (
  echo Nine Yin Java server is already running.
  start "Nine Yin GM Panel (Java)" http://127.0.0.1:19080/
  exit /b 0
)

powershell.exe -NoProfile -Command "$try = { param($n) $c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', $n); $true } catch { $false } finally { $c.Close() } }; if ((& $try 19061) -or (& $try 4000) -or (& $try 19081) -or (& $try 19080) -or (& $try 19083)) { exit 0 } else { exit 1 }"
set "BUSY=%ERRORLEVEL%"
if "%BUSY%"=="0" (
  echo ERROR: one of the ports 19061/4000/19081/19080/19083 is already in use.
  echo Close the other Nine Yin server ^(Go build or an older Java copy^) before starting this one.
  pause
  exit /b 1
)

echo.
echo Starting Nine Yin Java server (two processes)...
echo Server list : 127.0.0.1:4000
echo Game        : 127.0.0.1:19061
echo GM RPC      : 127.0.0.1:19081
echo GM UI       : http://127.0.0.1:19080/  (gm-web: pick a server, then console)
echo Logs        : logs\nineyin-java.current.log , logs\gm-web.current.log
echo.

start "Nine Yin Java 19061" /D "%ROOT%" "%JAVA_CMD%" --enable-native-access=ALL-UNNAMED -jar "%JAR%"  1>>"%LOGDIR%\nineyin-java.current.log" 2>>&1

rem Panel status polls want the game RPC port up first; brief wait.
timeout /t 4 /nobreak >nul
start "Nine Yin GM Web 19080" /D "%ROOT%" "%JAVA_CMD%" -jar "%GMJAR%"  1>>"%LOGDIR%\gm-web.current.log" 2>>&1

timeout /t 2 /nobreak >nul
start "Nine Yin GM Panel (Java)" http://127.0.0.1:19080/

echo Launcher finished. Keep both Java windows open.
rem pause
