# 九阴志全局门控（ninjiuzhi）

九阴志是**全服唯一**的单向阶段刻度：1=1内 … 9=6内（对应客户端
power_guide.ini 的 step 主步骤 1..6）。不同内容按阶段开放，对齐官方
"内容随九阴志进度解锁"的机制。

## 核心组件

`com.nineyin.game.NinJiuZhi`（全服单例，`NineyinServer` 启动时装载）：

- **阶段**：1..9，只增不减（`setStage` 拒绝回退；回退用 `reset` 回到 initial_stage）。
- **事件点**：击杀/任务事件累计（`addEvent`），到阈值自动升阶（可连跳）。
  默认累计阈值 2..9 阶 = 10/30/60/100/150/210/280/360，可被 requirements 覆写。
- **持久化**：`data/ninjiuzhi.json`（stage/active/points/events/revision），
  与 data-import 导入的 `server_ninjiuzhi` 表同构，每次变更即落盘、revision 递增。

## 数据源（均容缺，缺省 = 阶段 1、全部开放）

| 文件 | 作用 |
| --- | --- |
| `data/ninjiuzhi.json` | 运行时状态（装载 + 落盘同一文件） |
| `data/ninjiuzhi_requirements.json` | 五类门控覆写 + `initial_stage` + 可选 `thresholds` |

requirements 格式（与 data-import 的 `game_ninjiuzhi_overrides` 同构）：

```json
{
  "features":     {"smelting": 5},
  "tasks":        {"1346": 2},
  "npcs":         {"npc_locked": 3},
  "scenes":       {"book8": 2},
  "inner_books":  {"ng_wd_001": 4},
  "initial_stage": 1,
  "thresholds":   {"2": 10, "3": 30}
}
```

## 五类门控接入点

| 类别 | 键 | 生效位置 |
| --- | --- | --- |
| scenes | 场景 resource / 剧本 bookN | `LoginFlow.onWorldInfo` 开放剧本列表；`SceneSwitcher.switchScene` 拒绝未开放场景 |
| tasks | 任务号 | `BootstrapPackets.functionUnlockFrames`（主界面/内功面板前置任务） |
| inner_books | 内功 configId | `BootstrapPackets.neigongViewFrames`（View 43 初始内功） |
| npcs | NPC configId | `SceneFlow.visibleNpcs` 过滤（叠加在副本 dungeonProgress 波次之上） |
| features | 功能键 | 查询接口已备（`isOpen(FEATURES, key)`），当前登录链无独立功能开关注册表 |

未配置覆写的键一律视为阶段 1 即开放——无数据文件时行为与旧静态实现完全一致。

## 协议：419 阶段帧

`CustomPackets.ninJiuZhiStage(stage)` = 自定义消息 419（九阴志步数），
payload 为全服阶段 1..9。两处发送：

- 阶段生效（set/event 自动推进）→ `NineyinServer.broadcastNinJiuZhiStage`
  向全服已完成 UI bootstrap 的在线连接广播 419 + worldInfo 提示；
- 登录 UI bootstrap / 0x0C 重进补链（`SceneSwitcher.restorePlayerAttachments`）
  补发当前阶段，保证晚接入一致。

## 事件点来源

- **击杀**：`SceneFlow` 战斗管线 NPC 死亡（hp=0）→ `ninjiuzhi.onKill()`。
- **任务**：任务系统未接入前由 GM 注入。

## GM 动作（gm-web 面板「九阴志全局门控」）

| action | 参数 | 行为 |
| --- | --- | --- |
| `ninjiuzhi_set` | stage 1..9 | 单调设阶；生效即落盘 + 全服广播 |
| `ninjiuzhi_event` | eventKind=kill/task | 事件点 +1（可触发自动升阶） |
| `ninjiuzhi_reset` | - | 回 initial_stage、点数/事件清零 |
| `ninjiuzhi_status` | - | worldInfo 回显当前状态文本 |

## 与既有机制的关系

- 副本 `dungeonProgress`（NpcProgress 波次）是**场景内**刻度，与九阴志
  **全服**刻度相互独立、同时生效（visibleNpcs 两个条件都过闸）。
- MySQL 侧 `server_ninjiuzhi` / `game_ninjiuzhi_overrides` 表结构见
  `docs/data-table-design.sql`；Java 运行时当前直接读写 JSON 文件，
  不经 MySQL。
