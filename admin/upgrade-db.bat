@echo off
setlocal EnableExtensions
title Nineyin Upgrade DB

rem ============================================================
rem  数据库升级脚本：按文件名序重放 sql\*.sql.gz 到目标库。
rem  sql\ 目录是数据库结构与配置数据的权威版本存档
rem  （deploy.bat 第 [3] 步即调用本脚本）。
rem
rem  用法：upgrade-db.bat [库名]
rem    库名缺省 nineyin；指定其它库名可用于测试重建
rem    （如 upgrade-db.bat nineyin_upgrade_test 验证基线完备性）
rem
rem  语义：每个文件都要求幂等——
rem    结构文件 = CREATE TABLE IF NOT EXISTS（缺表补建，不动数据）
rem    配置数据 = REPLACE INTO（以 sql 存档为准覆盖同名主键行）
rem    增量文件 = 需写成条件 ALTER / IF NOT EXISTS（参照 export-sql.bat 产物）
rem ============================================================

rem ---- 环境路径（同名环境变量已定义时优先） ----
if not defined MYSQL_HOME set "MYSQL_HOME=G:\pojie\ai\mysql-26"
if not defined MYSQL_PORT set "MYSQL_PORT=3306"
if not defined MYSQL_USER set "MYSQL_USER=nineyin"
if not defined MYSQL_PASS set "MYSQL_PASS=nineyin123"
set "MYSQL_EXE=%MYSQL_HOME%\bin\mysql.exe"
set "TAR=%SystemRoot%\System32\tar.exe"
rem MYSQL_PWD 向 mysql 传凭据，避免 -p 的命令行警告
set "MYSQL_PWD=%MYSQL_PASS%"

set "DB=nineyin"
if not "%~1"=="" set "DB=%~1"

cd /d "%~dp0"

rem ---- MySQL 探活（认证级；未运行则提示，拉起实例归 deploy.bat/手动） ----
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -N -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo [错误] MySQL 127.0.0.1:%MYSQL_PORT% 未就绪。
    echo         请先启动 %MYSQL_HOME%\start-mysql.bat 或运行 deploy.bat。
    exit /b 1
)

rem ---- 建库（幂等；nineyin 账号无建库权限时要求库已存在） ----
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -e "CREATE DATABASE IF NOT EXISTS `%DB%` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >nul 2>&1
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -N -B %DB% -e "SELECT 1" >nul 2>&1
if errorlevel 1 (
    echo [错误] 目标库 %DB% 不可用：不存在，且 nineyin 账号无建库权限。
    echo         请先用管理员账号执行：
    echo         CREATE DATABASE `%DB%` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    echo         然后重跑本脚本。
    exit /b 1
)
echo [信息] 目标库: %DB%

rem ---- 无脚本检查 ----
set "COUNT=0"
for %%f in ("..\sql\*.sql.gz") do set /a COUNT+=1
if %COUNT%==0 (
    echo [错误] sql\ 目录没有 .sql.gz 升级脚本（先运行 export-sql.bat 生成）。
    exit /b 1
)

rem ---- 按文件名序重放 ----
set "WORK=%TEMP%\nineyin_upgrade"
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%"

echo [信息] 开始按序重放 %COUNT% 个脚本...
for %%f in ("..\sql\*.sql.gz") do (
    call :apply_one "%%~nf"
    if errorlevel 1 goto fail
)

rmdir /s /q "%WORK%" >nul 2>&1
echo.
echo [信息] 数据库 %DB% 升级完成（%COUNT% 个脚本全部成功）。
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -N -e "SELECT CONCAT('  表数: ', COUNT(*)) FROM information_schema.tables WHERE table_schema='%DB%'" 2>nul
endlocal
exit /b 0

:apply_one
rem 入参 = %%~nf（已含 .sql 后缀，如 0001_schema_20261031.sql）
set "NAME=%~1"
rem 只执行 NNNN_ 前缀的升级脚本，跳过其它文件
echo %NAME%| findstr /b /r "[0-9][0-9][0-9][0-9]_" >nul || (
    echo   [警告] 跳过非升级脚本: %NAME%.gz
    exit /b 0
)
echo   执行 %NAME%.gz ...
"%TAR%" -xzf "..\sql\%NAME%.gz" -C "%WORK%"
if errorlevel 1 (
    echo [错误] 解压失败: %NAME%.gz
    exit /b 1
)
if not exist "%WORK%\%NAME%" (
    echo [错误] 压缩包内没有 %NAME%（应打包单文件 .sql）
    exit /b 1
)
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% --default-character-set=utf8mb4 %DB% < "%WORK%\%NAME%"
if errorlevel 1 (
    echo [错误] 执行失败: %NAME%（查看上方 MySQL 报错）
    exit /b 1
)
del "%WORK%\%NAME%" >nul 2>&1
exit /b 0

:fail
rmdir /s /q "%WORK%" >nul 2>&1
echo [错误] 升级中止于上述脚本。
exit /b 1
