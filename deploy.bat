@echo off
setlocal EnableExtensions
title Nineyin Deploy

rem ============================================================
rem  九阴 Java 服务端 完整部署脚本（一条龙）
rem
rem  流程：
rem    [1] 构建（build.bat，--skip-build 可跳过）
rem    [2] MySQL 就绪（mysql-26 实例，端口 3306，未启动则自动拉起）
rem    [3] 数据库升级（按序重放 sql\*.sql.gz 权威存档，缺表补建/配置以存档为准）
rem    [4] 部署 jar 到服务器目录（旧份自动备份）
rem    [5] 同步运行时文件（scripts/ Lua 脚本镜像、缺失时补 roles.json）
rem    [6] 启动 Java 服务端（游戏 19061 / 区服列表 4000 / GM 19080）
rem
rem  用法：deploy.bat [--skip-build] [--no-start] [--import-config]
rem    --skip-build     跳过构建，直接使用 build\ 下现有 jar
rem    --no-start       只升级 + 部署，不启动服务
rem    --import-config  额外导入配置数据（game_/server_ 等表全量重建，
rem                     不触碰 role_ 角色数据；数据源见 DATA_SRC）
rem ============================================================

rem ---- 环境路径（同名环境变量已定义时优先，便于临时覆盖） ----
if not defined MYSQL_HOME  set "MYSQL_HOME=G:\pojie\ai\mysql-26"
if not defined SERVER_ROOT set "SERVER_ROOT=F:\Game\Online\9yin\9yin_server1"
if not defined DATA_SRC    set "DATA_SRC=G:\pojie\ai\9yin-go-server1\data"
if not defined MYSQL_PORT  set "MYSQL_PORT=3306"
if not defined MYSQL_USER  set "MYSQL_USER=nineyin"
if not defined MYSQL_PASS  set "MYSQL_PASS=nineyin123"
set "MYSQL_DSN=%MYSQL_USER%:%MYSQL_PASS%@tcp(127.0.0.1:%MYSQL_PORT%)/nineyin"

cd /d "%~dp0"

set "SKIP_BUILD=0"
set "NO_START=0"
set "IMPORT_CONFIG=0"
:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--skip-build"    set "SKIP_BUILD=1"    & shift & goto parse_args
if /i "%~1"=="--no-start"      set "NO_START=1"      & shift & goto parse_args
if /i "%~1"=="--import-config" set "IMPORT_CONFIG=1" & shift & goto parse_args
echo [错误] 未知参数: %~1
echo 用法: deploy.bat [--skip-build] [--no-start] [--import-config]
exit /b 2
:args_done

echo ==========================================
echo   九阴服务端 完整部署
echo ==========================================
echo   MySQL      : %MYSQL_HOME%  (127.0.0.1:%MYSQL_PORT%)
echo   服务器目录 : %SERVER_ROOT%
echo   配置数据源 : %DATA_SRC%
echo ==========================================
echo.

rem ---------- [1/6] 构建 ----------
if "%SKIP_BUILD%"=="1" (
    echo [1/6] 跳过构建（--skip-build）
) else (
    echo [1/6] 构建全部模块...
    call build.bat
    if errorlevel 1 (
        echo [错误] 构建失败，部署中止。
        pause
        exit /b 1
    )
)
if not exist "build\nineyin-server-*.jar" (
    echo [错误] build\ 下没有 nineyin-server jar，请去掉 --skip-build 重新运行。
    pause
    exit /b 1
)
if not exist "build\data-import-*.jar" (
    echo [错误] build\ 下没有 data-import jar，请去掉 --skip-build 重新运行。
    pause
    exit /b 1
)

rem ---------- [2/6] MySQL 就绪 ----------
echo [2/6] 检查 MySQL (127.0.0.1:%MYSQL_PORT%) ...
call :mysql_ping
if %ERRORLEVEL%==0 goto mysql_ok

rem 未就绪：确认没有本实例的僵死进程（进程在而端口不通 = 启动失败）
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='mysqld.exe'\" | Select-Object -ExpandProperty CommandLine" >"%TEMP%\nineyin_mysqld.txt" 2>nul
findstr /i /c:"%MYSQL_HOME%" "%TEMP%\nineyin_mysqld.txt" >nul
if not errorlevel 1 (
    echo [错误] 检测到 %MYSQL_HOME% 的 mysqld 进程在运行，但端口 %MYSQL_PORT% 连不上。
    echo         可能实例启动失败，请查看 MySQL 窗口或 %MYSQL_HOME%\mysqld.err.log。
    pause
    exit /b 1
)

if not exist "%MYSQL_HOME%\start-mysql.bat" (
    echo [错误] 找不到 %MYSQL_HOME%\start-mysql.bat
    pause
    exit /b 1
)
echo [信息] MySQL 未运行，正在启动（新窗口）...
start "MySQL %MYSQL_PORT%" /D "%MYSQL_HOME%" "%MYSQL_HOME%\start-mysql.bat"

set /a WAIT_SEC=0
:mysql_wait
timeout /t 2 /nobreak >nul
set /a WAIT_SEC+=2
call :mysql_ping
if %ERRORLEVEL%==0 goto mysql_ok
call :tcp_alive %MYSQL_PORT%
if %ERRORLEVEL%==0 (
    echo [错误] 端口 %MYSQL_PORT% 已被其它 MySQL 实例占用（nineyin 账号无法认证）。
    echo         若为 F 盘参考服自带的 mysqld，请先在任务管理器结束 mysqld.exe
    echo         （或关闭参考服的 MySQL 窗口）后重跑本脚本。当前 mysqld 进程：
    type "%TEMP%\nineyin_mysqld.txt" 2>nul
    pause
    exit /b 1
)
if %WAIT_SEC% GEQ 40 (
    echo [错误] 等待 MySQL 启动超时（40 秒）。请查看 MySQL 窗口的报错信息。
    pause
    exit /b 1
)
goto mysql_wait

:mysql_ok
echo [信息] MySQL 已就绪。
echo.

rem ---------- [3/6] 数据库升级（sql\ 目录权威脚本） ----------
echo [3/6] 数据库升级（按序重放 sql\*.sql.gz）...
call admin\upgrade-db.bat
if errorlevel 1 (
    echo [错误] 数据库升级失败，部署中止。
    pause
    exit /b 1
)
if "%IMPORT_CONFIG%"=="1" (
    echo [信息] --import-config：导入配置数据（数据源 %DATA_SRC%）...
    set "DI_JAR="
    for %%f in ("build\data-import-*.jar") do set "DI_JAR=%%~ff"
    java -jar "%DI_JAR%" -dsn "%MYSQL_DSN%" -data "%DATA_SRC%" import config
    if errorlevel 1 (
        echo [错误] 配置数据导入失败，可修复后重跑（sql\ 升级结果不受影响）。
        pause
        exit /b 1
    )
)
echo.

rem ---------- [4/6] 部署 jar ----------
echo [4/6] 部署 jar 到 %SERVER_ROOT% ...
if not exist "%SERVER_ROOT%" (
    echo [错误] 服务器目录不存在: %SERVER_ROOT%
    pause
    exit /b 1
)
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "TS=%%t"
set "SRC_JAR="
for %%f in ("build\nineyin-server-*.jar") do set "SRC_JAR=%%~nxf"
if exist "%SERVER_ROOT%\%SRC_JAR%" (
    copy /y "%SERVER_ROOT%\%SRC_JAR%" "%SERVER_ROOT%\%SRC_JAR%.bak-%TS%" >nul
    echo [信息] 旧服务端已备份: %SRC_JAR%.bak-%TS%
)
copy /y "build\%SRC_JAR%" "%SERVER_ROOT%\" >nul
if errorlevel 1 (
    echo [错误] 复制 %SRC_JAR% 失败。
    pause
    exit /b 1
)
set "SRC_DI="
for %%f in ("build\data-import-*.jar") do set "SRC_DI=%%~nxf"
copy /y "build\%SRC_DI%" "%SERVER_ROOT%\" >nul
if errorlevel 1 (
    echo [错误] 复制 %SRC_DI% 失败。
    pause
    exit /b 1
)
echo [信息] 已部署: %SRC_JAR% 、 %SRC_DI%
echo.

rem ---------- [5/6] 同步运行时文件 ----------
echo [5/6] 同步运行时文件（scripts/ Lua 脚本镜像）...
robocopy "scripts" "%SERVER_ROOT%\scripts" /MIR /NJH /NJS /NDL /NFL /NP >nul
if errorlevel 8 (
    echo [错误] robocopy 同步 scripts 失败（代码 %ERRORLEVEL%）。
    pause
    exit /b 1
)
if not exist "%SERVER_ROOT%\data" mkdir "%SERVER_ROOT%\data"
if not exist "%SERVER_ROOT%\data\roles.json" (
    if exist "%DATA_SRC%\roles.json" (
        copy /y "%DATA_SRC%\roles.json" "%SERVER_ROOT%\data\" >nul
        echo [信息] 已补 data\roles.json（JSON 兜底存档；MySQL 正常时以库为准）
    ) else (
        echo [警告] %DATA_SRC%\roles.json 不存在，服务端将以 MySQL 单后端启动。
    )
)
echo.

rem ---------- [6/6] 启动 ----------
if "%NO_START%"=="1" (
    echo [6/6] 跳过启动（--no-start）
) else (
    echo [6/6] 启动 Java 服务端...
    call "%SERVER_ROOT%\启动本地九阴服务-Java版.bat"
)
echo.
echo ==========================================
echo 部署完成。
echo   游戏      : 127.0.0.1:19061
echo   区服列表  : 127.0.0.1:4000
echo   GM 面板   : http://127.0.0.1:19080/
echo   日志      : %SERVER_ROOT%\logs\nineyin-java.current.log
echo ==========================================
endlocal
exit /b 0

rem ================= 工具函数 =================

rem MySQL 认证级探活：连通且 nineyin 账号可用才算活
:mysql_ping
"%MYSQL_HOME%\bin\mysql.exe" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -p%MYSQL_PASS% -N -e "SELECT 1" >nul 2>&1
exit /b %ERRORLEVEL%

rem TCP 端口探活（真连接，非 netstat 表查：双栈监听对连接表查询隐身，
rem 且进程死亡后 State=0 僵尸项无法区分活监听）
:tcp_alive
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try{$c.Connect('127.0.0.1',%~1); exit 0}catch{exit 1}finally{$c.Close()}"
exit /b %ERRORLEVEL%
