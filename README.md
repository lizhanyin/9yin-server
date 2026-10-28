# 九阴真经 Java 服务端（nineyin-server）

基于 [Summer 框架](docs/Summer框架手册.md)（JDK 21 / Netty）重新开发的九阴真经服务端。

功能蓝本：`9yin_server_public`（C++ 版，从 Go 反汇编源码移植）——协议、系统实现、
资源加载、数据表均以该工程为参照移植。

## 工程结构

```
src/main/java/
├── com/swingfrog/summer/    # Summer 框架源码（保持原样，不做业务修改）
└── com/nineyin/             # 九阴业务代码（逐步开发）
docs/
├── Summer框架手册.md         # 原 Summer 框架 README（框架用法手册）
└── reference/               # Summer 官方 ECS 游戏服示例（登录/玩家/队伍模块参考）
```

## 端口规划（对齐 C++ 版）

| 端口  | 用途               |
|-------|--------------------|
| 4000  | 区服列表（纯文本 svrlist） |
| 19061 | 游戏主协议（FxNet2 帧：XOR 0xFA6D3BCC + 0xEE 转义 + EE EE 终止） |
| 19080 | GM HTTP 控制台（仅本机） |

## 协议知识库

C++ 工程的 `spec/` 目录（51 个 S2C opcode 分发表、1548 条属性定义、297 张记录表 schema）
是 Java 端协议层的代码生成输入，九阴协议通过 Summer 的 `ProtocolCustom` 自定义协议接入。

## 构建

```bash
./mvnw clean package    # 无需本地安装 Maven
java -jar target/nineyin-server-0.1.0-SNAPSHOT.jar
```