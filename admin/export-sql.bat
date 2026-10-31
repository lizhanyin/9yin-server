@echo off
setlocal EnableExtensions
title Nineyin Export SQL

rem ============================================================
rem  数据库导出脚本：导出表结构/配置数据为 .sql.gz（tar+gzip）
rem  存入仓库 sql\ 目录 —— 数据库结构与配置数据的权威版本存档。
rem
rem  用法：
rem    export-sql.bat
rem        全量基线导出：结构（67 表 IF NOT EXISTS）
rem        + 配置表数据（game_/server_/teams/gm_permissions 等，REPLACE），
rem        产出 NNNN_schema_日期.sql.gz 与 NNNN_config_data_日期.sql.gz
rem    export-sql.bat --table "表1,表2" [--name 说明]
rem        指定表的结构+数据单文件（后续加表/改字段后的增量导出用），
rem        产出 NNNN_名称_日期.sql.gz；--name 缺省取第一个表名
rem
rem  规则（详见 sql\README.md）：
rem    - 序号 NNNN 自动递增（sql\ 目录当前最大序号 +1）
rem    - .sql.gz 实为 tar+gzip 单文件归档，解压用 tar -xzf
rem    - 增量 SQL 要求幂等：CREATE TABLE IF NOT EXISTS / 条件 ALTER
rem ============================================================

rem ---- 环境路径（同名环境变量已定义时优先） ----
if not defined MYSQL_HOME set "MYSQL_HOME=G:\pojie\ai\mysql-26"
if not defined MYSQL_PORT set "MYSQL_PORT=3306"
if not defined MYSQL_USER set "MYSQL_USER=nineyin"
if not defined MYSQL_PASS set "MYSQL_PASS=nineyin123"
if not defined DB_NAME    set "DB_NAME=nineyin"
set "MYSQL_EXE=%MYSQL_HOME%\bin\mysql.exe"
set "MYSQLDUMP=%MYSQL_HOME%\bin\mysqldump.exe"
set "TAR=%SystemRoot%\System32\tar.exe"
rem MYSQL_PWD 向 mysql/mysqldump 传凭据，避免 -p 的命令行警告
set "MYSQL_PWD=%MYSQL_PASS%"

set "MODE=full"
set "TABLES_ARG="
set "NOTE_NAME="
:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="--table" ( set "MODE=table" & set "TABLES_ARG=%~2" & shift & shift & goto parse_args )
if /i "%~1"=="--name"  ( set "NOTE_NAME=%~2" & shift & shift & goto parse_args )
echo [错误] 未知参数: %~1
echo 用法: export-sql.bat [--table "表1,表2"] [--name 说明]
exit /b 2
:args_done

cd /d "%~dp0"
if not exist "sql" mkdir "sql"
set "WORK=%TEMP%\nineyin_export"
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%"

for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd"') do set "TODAY=%%t"

rem ---- 计算下一序号：取 sql\*.sql.gz 文件名前缀 NNNN 的最大值 ----
set "MAX_SEQ=10000"
for %%f in ("sql\*.sql.gz") do call :bump_seq "%%~nf"
set /a NEXT_SEQ=%MAX_SEQ% + 1
set "SEQ=%NEXT_SEQ:~1%"
echo [信息] 序号: %SEQ%  日期: %TODAY%

rem ---------- 模式一：全量基线 ----------
if "%MODE%"=="full" goto export_full

rem ---------- 模式二：指定表 ----------
if "%TABLES_ARG%"=="" (
    echo [错误] --table 需要表名参数，如 --table "new_table" 或 --table "t1,t2"
    exit /b 2
)
if "%NOTE_NAME%"=="" (
    for /f "tokens=1 delims=," %%t in ("%TABLES_ARG%") do set "NOTE_NAME=%%t"
)
set "FINAL=%SEQ%_%NOTE_NAME%_%TODAY%"
call :dump_tables "%TABLES_ARG%" "%FINAL%"
if errorlevel 1 goto fail
goto done

:export_full
rem 结构：全部表（IF NOT EXISTS、无 DROP，可重放）
set "FINAL=%SEQ%_schema_%TODAY%"
"%MYSQLDUMP%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% --no-data --skip-add-drop-table --skip-add-locks --skip-comments --set-gtid-purged=OFF --default-character-set=utf8mb4 %DB_NAME% >"%WORK%\raw.sql" 2>nul
if errorlevel 1 (
    echo [错误] mysqldump 导出结构失败。
    exit /b 1
)
findstr /v /c:"SQL_LOG_BIN" /c:"GTID_PURGED" "%WORK%\raw.sql" >"%WORK%\raw2.sql"
call :idempotent_ddl "%WORK%\raw2.sql" "%WORK%\%FINAL%.sql"
if errorlevel 1 goto fail
call :pack "%FINAL%"
if errorlevel 1 goto fail

rem 配置表数据：动态取配置表清单（排除角色运行时表 accounts/roles/role_%）
"%MYSQL_EXE%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% -N -B -e "SELECT GROUP_CONCAT(table_name SEPARATOR ' ') FROM information_schema.tables WHERE table_schema='%DB_NAME%' AND table_name NOT LIKE 'role\_%%' AND table_name NOT IN ('accounts','roles')" >"%WORK%\tables_raw.txt" 2>nul
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "(Get-Content '%WORK%\tables_raw.txt' -Raw).Trim()"`) do set "CONFIG_TABLES=%%t"
if "%CONFIG_TABLES%"=="" (
    echo [错误] 未取得配置表清单（库 %DB_NAME% 是否为空？）
    exit /b 1
)
set /a NEXT_SEQ=%NEXT_SEQ% + 1
set "SEQ=%NEXT_SEQ:~1%"
set "FINAL=%SEQ%_config_data_%TODAY%"
call :dump_data "%CONFIG_TABLES%" "%FINAL%"
if errorlevel 1 goto fail
goto done

rem ================= 工具函数 =================

rem 导出指定表（结构 IF NOT EXISTS + 数据 REPLACE，单文件）
:dump_tables
set "DT_TABLES=%~1"
set "DT_TABLES=%DT_TABLES:,= %"
set "DT_FINAL=%~2"
"%MYSQLDUMP%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% --skip-add-drop-table --skip-add-locks --skip-comments --set-gtid-purged=OFF --replace --single-transaction --default-character-set=utf8mb4 %DB_NAME% %DT_TABLES% >"%WORK%\raw.sql" 2>nul
if errorlevel 1 (
    echo [错误] mysqldump 导出表失败: %DT_TABLES%
    exit /b 1
)
findstr /v /c:"SQL_LOG_BIN" /c:"GTID_PURGED" "%WORK%\raw.sql" >"%WORK%\raw2.sql"
call :idempotent_ddl "%WORK%\raw2.sql" "%WORK%\%DT_FINAL%.sql"
if errorlevel 1 exit /b 1
call :pack "%DT_FINAL%"
exit /b %ERRORLEVEL%

rem 仅导出数据（REPLACE，可重放=以存档为准恢复）
:dump_data
set "DD_TABLES=%~1"
set "DD_FINAL=%~2"
"%MYSQLDUMP%" -h127.0.0.1 -P%MYSQL_PORT% -u%MYSQL_USER% --no-create-info --replace --skip-add-locks --skip-comments --set-gtid-purged=OFF --single-transaction --default-character-set=utf8mb4 %DB_NAME% %DD_TABLES% >"%WORK%\raw.sql" 2>nul
if errorlevel 1 (
    echo [错误] mysqldump 导出数据失败。
    exit /b 1
)
findstr /v /c:"SQL_LOG_BIN" /c:"GTID_PURGED" "%WORK%\raw.sql" >"%WORK%\%DD_FINAL%.sql"
call :pack "%DD_FINAL%"
exit /b %ERRORLEVEL%

rem 把 CREATE TABLE 行改为 CREATE TABLE IF NOT EXISTS
:idempotent_ddl
powershell -NoProfile -Command "$c=[IO.File]::ReadAllText('%~1'); [IO.File]::WriteAllText('%~2', ($c -replace '(?m)^CREATE TABLE ', 'CREATE TABLE IF NOT EXISTS '))"
if errorlevel 1 (
    echo [错误] 结构幂等化处理失败。
    exit /b 1
)
exit /b 0

rem 压缩入 sql\（tar+gzip，内部文件名与压缩包同名 .sql）
:pack
"%TAR%" -czf "sql\%~1.sql.gz" -C "%WORK%" "%~1.sql"
if errorlevel 1 (
    echo [错误] 压缩失败: %~1.sql.gz
    exit /b 1
)
echo [信息] 已导出 sql\%~1.sql.gz
exit /b 0

rem 序号推进（子过程避免延迟展开）：前缀补 1 后按数字串比较
:bump_seq
for /f "tokens=1 delims=_-" %%n in ("%~1") do (
    echo %%n| findstr /r "^[0-9][0-9][0-9][0-9]$" >nul && if 1%%n GTR %MAX_SEQ% set "MAX_SEQ=1%%n"
)
exit /b 0

:fail
echo [错误] 导出失败，已中止。
exit /b 1

:done
echo.
echo 导出完成，sql 目录现有：
dir /b "sql\*.sql.gz"
rmdir /s /q "%WORK%" >nul 2>&1
endlocal
exit /b 0
