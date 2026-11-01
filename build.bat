@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo   九阴服务端构建脚本 (clean package)
echo ==========================================

echo.
echo [1/3] 清理旧的 build 目录...
if exist "build" rmdir /s /q "build"
mkdir "build"
if errorlevel 1 (
    echo [错误] 无法创建 build 目录！
    exit /b 1
)

echo.
echo [2/3] Maven 打包全部模块（跳过测试，可在命令行追加额外 Maven 参数）...
call mvnw.cmd clean package -DskipTests %*
if errorlevel 1 (
    echo.
    echo [错误] Maven 构建失败，请检查上方日志。
    exit /b 1
)

echo.
echo [3/3] 复制可运行的 shaded jar 到 build\ ...
copy /y "nineyin\target\nineyin-server-*.jar" "build\" >nul
if errorlevel 1 (
    echo [错误] 未找到 nineyin\target\nineyin-server-*.jar！
    exit /b 1
)
copy /y "tools\data-import\target\data-import-*.jar" "build\" >nul
if errorlevel 1 (
    echo [错误] 未找到 tools\data-import\target\data-import-*.jar！
    exit /b 1
)

echo.
echo 构建完成，build 目录内容：
dir /b "build"
echo.
echo 主服务启动示例: java -jar build\nineyin-server-0.1.0-SNAPSHOT.jar

endlocal
