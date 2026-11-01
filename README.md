# 九阴真经 Java 服务端（nineyin-server）

基于 [Summer 框架](docs/Summer框架手册.md)（JDK 21 / Netty）重新开发的九阴真经服务端。

功能蓝本：`9yin_server_public`（C++ 版，从 Go 反汇编源码移植）——协议、系统实现、
资源加载、数据表均以该工程为参照移植。

## 工程结构（Maven 多模块）

```
├── summer/                  # Summer 框架源码（保持原样，不做业务修改）
├── nineyin/                 # 九阴游戏服务端主体 + 入口（shade 可执行 jar）
│   └── com/nineyin/         # server/transport/protocol/login/scene/game/db/auth/shop
├── gm-web/                  # GM HTTP 面板（内嵌 Netty HTTP + 控制台页面）
├── tools/
│   └── data-import/         # 数据导入工具（-dsn … apply-schema 建表；导入器逐步补齐）
└── docs/
    ├── Summer框架手册.md     # 原 Summer 框架 README（框架用法手册）
    ├── data-table-design.*  # data 目录 JSON → 45 张 MySQL 表设计
    └── reference/           # Summer 官方 ECS 游戏服示例（登录/玩家/队伍模块参考）
```

依赖方向：`nineyin → summer`、`gm-web → summer`（gm-web 与游戏服已拆分为两个独立进程，
经 summer RPC 交互）；`tools/data-import` 独立无内部依赖。

## 端口规划（对齐 go 版）

| 端口  | 归属进程 | 用途               |
|-------|---------|--------------------|
| 4000  | 游戏服   | 区服列表（纯文本 svrlist） |
| 19061 | 游戏服   | 游戏主协议（FxNet2 帧：XOR 0xFA6D3BCC + 0xEE 转义 + EE EE 终止） |
| 19081 | 游戏服   | GM RPC（summer StringLine，仅 127.0.0.1，`@Remote(filter)` 端口隔离） |
| 19080 | gm-web   | GM 管理页 `/` + 控制台 `/console.html`（仅本机） |
| 19083 | gm-web   | summer 框架自身端口（无外部用途） |

## GM 面板（gm-web 模块，独立进程）

浏览器打开 `http://127.0.0.1:19080/`——**管理页**（index.html）管理多台游戏服：服务器
列表存浏览器 localStorage（`gm_servers`），按 `host:RPC端口` 添加；面板按需经
`ClientMgr.register` 动态注册 summer 客户端（断线自动重连、失败 5s 退避），轮询在线状态。
选中服务器后进入 **控制台**（console.html，URL 带 `?s=host:port`；无参数自动跳回管理页）。

- **动态数据**（在线会话/服务器状态/命令下发/minimal-login 开关）经 summer RPC 调游戏服
  `GmRemote`（`@Remote(filter=true, serverName="gm")`，仅 19081 端口可达；方法返回 JSON
  字符串，两模块不共享类）。会话数据含门派/场景中文显示名（游戏服侧 FactionNames/SceneNames
  解析）。
- **17 个目录接口**（道具/卡牌/称号/经脉/坐骑/时装/礼包/天气/禁地/声望/身份/词条等）由
  gm-web 本地 `GmCatalog` 供给（share 根 GBK INI + MySQL 优先 + data/ JSON 回退；本机
  所有游戏服共享数据源）。
- 命令按钮走 `POST /api/sessions/{id}/command` → RPC `GmRemote.command` 入 `GmHub` 队列，
  消费侧与拆分前一致。
- 游戏服离线时面板降级：管理页显示未连接、控制台 sessions 空列表 / status `game_connected:false`。

两进程启动：仓库根 `启动本地九阴服务.bat`（游戏 jar → gm-web jar，继承
`NINEYIN_SERVER_ROOT`/`NINEYIN_MYSQL_DSN`）；面板端口可用 `-Dnineyin.gm.host/port` 覆盖。
依赖的部署数据：share 根（`NINEYIN_SERVER_ROOT` 或 CWD 上溯定位）与 `data/` 目录，
两者缺失时面板仍可用，目录接口返回空列表。

## Lua 脚本（技能效果与伤害公式）

`scripts/`（仓库根；定位方式与 share 同）：`skill/<技能ID>.lua` 技能效果脚本 +
`combat/formula.lua` 伤害公式，GM 面板 `reload_scripts` 热重载（不重启）；目录缺失
或脚本出错时自动回退 Java 默认管线，不影响开服。契约见
[docs/skill-script-framework.md](docs/skill-script-framework.md)。

## 数据库
user: root
pwd: 123456

user: nineyin
pwd: nineyin123
db: nineyin



## 协议知识库

GO 工程的 `cmd/` 目录（51 个 S2C opcode 分发表、1548 条属性定义、297 张记录表 schema）
是 Java 端协议层的代码生成输入，九阴协议通过 Summer 的 `ProtocolCustom` 自定义协议接入。

## 构建

```bash
./mvnw clean package    # 无需本地安装 Maven
java -jar nineyin/target/nineyin-server-0.1.0-SNAPSHOT.jar
```

数据导入工具：

```bash
java -jar tools/data-import/target/data-import-0.1.0-SNAPSHOT.jar \
  -dsn "nineyin:nineyin123@tcp(127.0.0.1:3306)/nineyin" apply-schema
```

## 许可证

- 本仓库自有代码（`nineyin/`、`gm-web/`、`tools/` 及根工程配置）以
  [BSD 3-Clause](LICENSE.txt) 许可发布，根 `pom.xml` 同步声明。
- `summer/` 目录为内嵌的 [SwingFrog/Summer](https://github.com/SwingFrog/Summer)
  框架源码，保持上游原样、不做修改，遵循其上游 Apache-2.0 许可证
  （随附原文件 [summer/LICENSE.txt](summer/LICENSE.txt)，模块 `pom.xml` 单独声明，
  不继承根工程的 BSD-3）。

  
<strong>注意：本项目仅限于研究，严禁用于商用。游戏版权归蜗牛游戏公司所有。</strong>