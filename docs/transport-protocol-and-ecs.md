# 传输协议与 ECS 架构可行性分析

> 本文整理自对本仓库的两轮架构分析：传输协议（fxnet2 现代协议）的完整剖析，以及在该协议约束下能否引入 ECS（Entity Component System）架构的评估。
>
> - 协议实现代码：`src/transport/`、`include/9yin/transport/`
> - 协议知识库：`spec/`（opcodes / properties / records）

---

## 一、传输协议

本服务端使用**基于 TCP 的自定义二进制协议**（项目内称 "fxnet2 现代协议" / modern FxClient framing），由客户端逆向（IDA 导出 + 官服抓包）还原而来。

### 1. 底层传输

- **TCP socket**，默认同时监听两个端口（见 `src/main.cpp`）：
  - `0.0.0.0:4000` —— 游戏网关端口（对应客户端 server_list.ini 的 Area）
  - `0.0.0.0:19061` —— 游戏服务端口（Game）
- 服务器逐帧读取字节流，单连接内保持有序状态机

### 2. 帧格式（无长度前缀，转义终止分帧）

每帧的编码流程见 `src/transport/frame.cpp` 的 `encode_frame()`：

```
明文 payload
   │ ① XOR 加密：与 4 字节循环 key 异或
   ▼
密文字节流
   │ ② 字节转义：0xEE → 0xEE 0x00
   ▼
   │ ③ 追加帧终止符：0xEE 0xEE
   ▼
线上字节
```

线上没有长度字段，**靠 `0xEE 0xEE` 终止符分帧**：

| 字节序列 | 含义 |
|---|---|
| `EE 00` | 数据中的一个字面 `0xEE` 字节（转义） |
| `EE EE` | 帧结束 |
| `EE xx`（其他） | 非法，抛 `InvalidEscape` |

单帧上限默认 1 MiB（`include/9yin/transport/frame.hpp` 中 `kDefaultMaxFrameSize`），超限抛 `FrameTooLarge`。

### 3. 加密：4 字节循环 XOR

见 `include/9yin/transport/key.hpp`：

- 初始密钥 `0xFA6D3BCC`，按**小端字节序**展开成 4 字节循环使用
- XOR 对称，加密解密同一个操作
- **读写方向密钥独立**（`ConnectionConfig` 中 `read_key` / `write_key` 分开），因为客户端在登录流程中会通过 `on_set_verify`(0x01) / `on_set_encode`(0x02) 消息在不同时机轮换方向密钥

> 这只是混淆级别的编码，不是真正的加密。

### 4. 消息体格式（解密后的明文）

明文 = `u8 opcode + 具体布局的字段`，多字节整数小端。以 `ServerLocation` (0x1F) 为例（见 `spec/opcodes.md`）：

```
u8  opcode        // 消息类型，如 0x1F
u32 object_id
u32 owner_id
f32 x, y, z, orient
```

完整消息目录在 `spec/opcodes.md` / `spec/opcodes.json`：

| 统计项 | 数量 |
|---|---|
| S2C opcode | 51 |
| S2C 已还原布局 | 23 |
| 属性（properties） | 1548 |
| 记录（records） | 297 |
| C2S 方法（仅名字级证据） | 10 |

C2S 的 opcode 编号尚未从客户端发送分发表闭合，目前只有方法名（GetVerify / Login / CreateRole / SelectRole / Ready / MoveTo / Custom 等）。

### 5. 辅助通道

- **GM 面板**：独立的极简 HTTP 服务器，仅绑定 `127.0.0.1:19080`（`include/9yin/gm/http_server.hpp`）
- **更新分发**：`webroot/update` 下的 HTTP 文件下载
- **选服列表**：`docs/server_list.ini` 供客户端登录器使用

### 6. 一句话概括

**TCP 之上的 `XOR(4字节循环key) + 0xEE转义 + EE EE终止符` 分帧协议，帧内是 `opcode + 小端二进制字段` 的自定义消息。**

---

## 二、ECS 架构可行性

**结论：传输协议与内部架构是解耦的——该协议非但不妨碍 ECS，它的同步模型反而和 ECS 天然同构。** 客户端不关心服务端内部怎么组织数据，它只要求收到"属性 ID → 值"的增量消息。

### 1. 协议层是"对象同步模型"

客户端收到的消息本质上是一套"实体—属性—记录—视图"的增量同步协议：

| 协议消息 | 语义 |
|---|---|
| `0x0D ServerAddObject` / `0x0E ServerRemoveObject` | 实体的创建/销毁 |
| `0x10 ServerObjectProperty` | 某实体的**单个属性变化**（增量推送） |
| `0x11/0x12/0x13/0x14 ServerRecord*` | **表格型数据**的增删行/网格/清空 |
| `0x15~0x19 ServerView*` | **AOI/兴趣管理**（视图快照与进出） |
| `0x1F/0x20 ServerLocation/Moving` | 位置/移动同步 |

### 2. 与 ECS 的对应关系

```
Entity (object_id)          ↔ ECS Entity
Property / Record / View    ↔ ECS Component
0x10 属性增量推送            ↔ dirty component 同步
View 进出 (0x18/0x19)       ↔ AOI 兴趣管理 diff
记录表 RecordAddRow...      ↔ 表格组件（背包/技能栏/快捷栏）
```

ECS 的 per-component 脏标记恰好是生成 `0x10` 增量消息的最自然方式——比大杂烩结构体还顺，因为 `0x10` 本来就是按属性粒度推送的。`spec/properties.json`（1548 个属性）和 `spec/records.json`（297 个记录）可以直接当作"组件字段 ↔ 协议属性 ID"的映射表使用。

### 3. 真正的代价在别处

当前代码走的是**相反方向**：`include/9yin/game/player_runtime.hpp` 是一个把 HP/技能/BUFF/称号/背包全塞在一起、一把互斥锁保护的大结构体（god struct）。所以"不能用 ECS"的障碍不是协议，而是：

1. **需要一层同步适配层**：ECS 组件变化 → `0x10/0x11` 协议消息的序列化桥。这是必须新写的，但 `src/game/systems.cpp` 里的消息分发已经在做类似的事。
2. **状态迁移成本**：`PlayerRuntime` 的字段要拆成组件，DB 存档（`include/9yin/db/role_store.hpp`）要跟着改。
3. **Tick 驱动**：ECS 系统调度需要帧循环，现在的代码是收到消息即时处理的模型。

### 4. 迁移路径建议

按 `SYSTEMS.md` 的状态（大量系统还是骨架/未做），当前是换架构相对最便宜的窗口期——已完成的系统越多，迁移成本越高。建议顺序：

1. 先搭 Entity / 组件存储 + 属性 ID 映射层
2. 把 `0x10 ServerObjectProperty` 的下发改成由 dirty 标记驱动
3. 再逐系统（打坐/技能/快捷键）从 `PlayerRuntime` 挪进组件
