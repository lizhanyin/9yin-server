# Go 复刻一/二期：登录链与进世界人物数据传输

> 分支 `9yin-main-new`。一期 `688f458`（登录链至选角初始帧），二期 `ab6f715`（ClientReady
> starter 链）+ `8b229cb`（NPC 物化）。复刻基准是 `G:\pojie\ai\9yin-go-server1`
> （=运行中 exe 的源码，非 git 仓库——**Java 仓库的 golden 资源才是持久字节真值**）。
> 验证状态：86/86 测试绿 + 活体冒烟与 Go golden manifest 完全一致。

---

## 1. 代码地图

```
nineyin/src/main/java/com/nineyin/
├── transport/          帧协议（XOR+EE 转义）：FrameCodec/FrameDecoder/FrameEncoder/FrameAccumulator/XorKey/Connection
├── server/             Netty 装配与登录链：NineyinProtocolHandler/GameServerHandler/LoginFlow/LoginSession
│                       LoginFlow.parseMotionPosition（二期移动包）
├── session/            SessionMachine 相位状态机
├── auth/               LoginIdentity（0x02 账号身份摘要）
├── role/               角色存档：Roles(域模型)/RoleRepository/RoleStoreFacade
│                       JsonRoleRepository(data/roles.json)↔MySqlRoleRepository(DSN)，CAS 版本号
├── game/
│   ├── avatar/         PlayerActor（0x0D/0x10 + 快捷栏状态）/Actor/RoleVisual
│   │   └── neigong/    PlayerProgress（View43/修炼结算）/FacultyStore/NeiGongCatalog
│   ├── starter/        二期 starter 链：StarterQingGong/StarterSkills/StarterGrants（编排）
│   ├── npc/            二期 NPC 数据层：NpcTemplateTable/NpcCreator/NpcResolver/NpcSpawn/SceneNpcRegistry
│   ├── scene/          二期场景核心：WorldScene/WorldViewport/SceneLifecycle/NpcViewport
│   └── equipment/      EquipmentResolver（外观→模型）
├── protocol/           帧构造器：PacketWriter/LoginMessages/SceneMessages/RecordMessages(0x11/0x12)
│                       CustomMessages(0x1E/0x27)/NpcMessages(0x0D/0x20/0x0E/0x1F)/NpcSchema/PropertyIndex
├── cache/              ResourceCache（唯一读盘层：text/ini/of 缓存）
└── gm/                 GM 面板（HTTP 19080）：GmHub/GmHttpServer/GmCatalog(Db)
```

端口：游戏 `19061`（Summer Custom 协议接管管线）、区服列表 `4000`（纯文本 svrlist）、
GM 面板 `19080`（默认仅 127.0.0.1）。

---

## 2. 传输与分发底座（一期）

### 2.1 帧协议（FxNet2）

- 明文按 4 字节循环 XOR 加密：key `0xFA6D3BCC` 小端（`XorKey.INITIAL_VALUE`），读写独立密钥。
- 密文字节 `0xEE` 转义为 `EE 00`；帧以 `EE EE` 终止（`FrameAccumulator` 流式状态机，非法转义即断连）。
- 单帧上限 64KB。抓包/重放工具统一走 `FrameCodec.encode/decode`。

### 2.2 Netty 管线

```
NineyinProtocolHandler.onHandle
 ├─ 端口 4000 → ServerListHandler（svrlist 文本协议）
 └─ 其余端口 → FrameDecoder → FrameEncoder → GameServerHandler
                                              └─ LoginFlow.dispatch(opcode)
```

`LoginFlow.dispatch` 是全部路由：`0x02/0x03/0x05/0x04/0x09/0x0A` 显式分支，其余
opcode 放行（三期业务层的接入口）。

### 2.3 相位状态机（`SessionMachine`）

```
Connected ─loginCompleted→ RoleCreation(无角色)/RoleSelection(有角色)
RoleSelection ─roleChosen(0x04)→ EnteringWorld ─ready(0x09/兼容0x0A)→ AwaitingActivity
AwaitingActivity ─activity(0x0A)→ InWorld
```

每 opcode 有长度校验（`validateMessage`：0x02≥5、0x04≥0x54、0x05≥0x49、0x09=1 字节）；
未知 opcode 放行。`reenterScene()`（0x0C 重入）已预置，三期接。

---

## 3. 一期登录链（逐 opcode）

### 3.1 帧序总览

| 客户端 | 服务端响应 | 说明 |
|---|---|---|
| 0x02 登录（抓包首飞明文） | `0x04` 无角色定长 0x29 / 带角色 | `LoginIdentity.parse` 账号摘要 → `roleStore.login` |
| 0x03 世界信息 | `0x05` worldInfo(0, "0,book1,…") | |
| 0x05 创角 | `0x04` 带新角色 | `roleStore.create`；名字空→"本地角色" |
| 0x04 选角（双击角色） | 0x09→0x0A→8×0x15→0x0B→0x0D→0x1F→0x10 | 见 3.3 |
| 0x09 就绪 | （二期）1748 帧 starter 链 | 见 §4 |
| 0x0A 活动 | （二期）NPC 物化 | 见 §5 |

### 3.2 0x04 rolePara（36 逗号分隔字段）

关键位：`f7/f9/f10/f11` = 经 `EquipmentResolver`（item/equipment.ini ArtPack +
itemartstatic.ini 模型）解析的头发/衣/裤/鞋；`f21="stand"`；`f23` 实力称号键
（`roleListTitle(151)→"title027"` 返璞归真）；`f24` 所在场景中文名
（`SceneNames`：scenes.ini GBK + stringname.idres，回退 `scene_name_NNN`）；
`f25` 剧本介绍键（`bookIntroKey`→`introduction_*01_title`）；`f32` 剧本原值。

### 3.3 选角初始帧（0x04 的响应，顺序固定）

| # | 帧 | 内容 |
|---|---|---|
| 1 | `0x09` 属性表 | 228 协商字段（`NpcSchema.sceneVisiblePropertyFields`：99 NPC 基础 + 玩家扩展 99–227），布局 `0x09\|u16 count\|(C名+NUL+u8 wireType)*` |
| 2 | `0x0A` 记录表 | 6 schema：QingGongRec(7)/ActiveQGSkillRec(7)/Task_Accepted(3,3,3,7,7,7,3)/Task_Completed(3)/shortcut_rec(3,7,7)/cooldown_rec(3,4,4,3)，列型 3=int32/4=int64/7=窄字符串 |
| 3-10 | `0x15`×8 背包视图 | (121,132)(2,132)(123,132)(125,132)(122,16)(3,16)(124,16)(126,16)，7 字节空容器头 |
| 11 | `0x0B` 入场 | object=1/owner=1 + 属性 7=场景 Config（如 `ini\scene\school09_YiHuaGong`）、8=Resource、99=MaxVisuals(1000000) |
| 12 | `0x0D` 玩家对象 | 0x3f 定长头（objID@1/owner@5/XYZ@0x09 起/Orient@0x15，count 回填@0x3d）+ ~110 属性归档 |
| 13 | `0x1F` 定位 | 25 字节：object/owner + XYZO 四 float |
| 14 | `0x10` 权威 vital | `PlayerActor.vitalUpdate()`（HP/MP 比率、技能元组、BufferListStr+BufferInfo1..24、门派、进度属性 171-207） |

选角同时：`FacultyStore` 读档（无则写初始）、`attachGmSnapshot` 挂 GM 面板会话。
场景别名修正 `normalizeClientScene`（scene09_SaiBeiCaoYuan→scene09、
scene07_MeiHuaLing→scene07、clone004_wl_1→clone004_wl）。

### 3.4 角色存档（双后端）

- `JsonRoleRepository`：单文档 `data/roles.json`（Go 原生格式，`GoJson` 逐字节同型输出，原子写）。
- `MySqlRoleRepository`：DSN（env `NINEYIN_MYSQL_DSN` > 根目录 `mysql.env` > JSON）；
  表 `accounts/roles(+role_ext)/role_appearances/role_locations`，`saveAppearance/saveLocation` CAS。
- 默认出生：`ini\scene\school09_YiHuaGong`/`school09` @ (340.160, 47.979, 657.284, 2.588)。
- `FacultyStore`：`data/faculty.json` 内功修炼快照（书等级/修为/内力），断连持久化。

---

## 4. 二期(上)：ClientReady starter 链

### 4.1 触发与守卫（照抄 Go main.go 显式 0x09 分支）

```
onClientReady(0x09):
  ① 若未发过轻功 → grantStarterQingGong（含功能解锁）        [每连接一次]
  ② grantSitcrossShortcut（idx9 已占用则跳过）                 [幂等]
  ③ 若未发过内功 → grantStarterProgress（View43/40/快捷栏/vital）[每连接一次]
  ④ enableSkillActionSwitch（0x27 msg414）                     [每次 0x09 都重发]
  ⑤ 相位已 InWorld → 忽略重复就绪（不再 ready()），不断连
  ⑥ world.clientReady()（armed 置位，无帧）
  ⑦ machine.ready() → 0x10 vital 终帧
```

**0x0A 兼容提升**：客户端省略 0x09 直发 0x0A 时，若相位仍在 EnteringWorld，则
`world.clientReady + ready() + vital + 0x27 开关`（**不发** starter 链——只在显式
stage_main 0x09 后发）。

### 4.2 1748 帧明细（golden `ready_chain_manifest.json` 为帧序权威）

| 段 | 帧 | 数量 | 内容 |
|---|---|---|---|
| 轻功记录 | 0x11 | 18 | QingGongRec 行 `qinggong_1..12,14..17,26,gaojie` |
| 轻功容器 | 0x15 | 1 | View46 cap=**70** |
| 轻功条目 | 0x18 | 70 | 18 类目 + **52** 个 QG_JH_* 技能实例（ConfigID=属性7） |
| 任务接受 | 0x11 | 15 | Task_Accepted 七列 `(taskID,9,0,"","","",0)`；1069-1073,1344,1140-1147,51002 |
| 任务位图 | 0x11 | 1594 | Task_Completed 行 0..1593（append-only 含前导零行；`taskCompletedBit`: serial=taskID+1, row=serial/32, bit=serial%32(0→32)；51002→row1593 bit26=1<<26） |
| 打坐快捷 | 0x11 | 1 | shortcut_rec(9,"skill","zs_default_01") |
| 内功书 | 0x15+0x18 | 1+6 | View43 cap=6；bookFrame 九属性（ConfigID/StaticData/ItemType/level(6)/MaxLevel/CurFill/TotalFill/Power/MaxPower） |
| 已学技能 | 0x15+0x18 | 1+23 | View40：zs_default_01 + 花神七式7 + 心佛掌7 + 太极拳(古谱)8；九属性含 PauseTime(212,float)+SkillCanUse(225,byte=1) |
| 战斗快捷栏 | 0x11 | 15 | 心佛掌 idx10-16（第二数字页）+ 太极拳 idx20-27（第三页）；同索引不同值先 0x12 删行再加 |
| 收尾 | 0x10 + 0x27 + 0x10 | 3 | 进度 vital → 0x27 msg414("reset",1223,1) → 就绪 vital |

合计 `{0x11:1643, 0x18:99, 0x15:3, 0x10:2, 0x27:1}`。

### 4.3 新增编码器

- **`RecordMessages`**：`recordAddString/Int32/Cells`（0x11：`0x11|0|u32 obj|u32 owner|u16 记录|u16 行起0|u16 行数1|按 schema 列编码`）；`recordDelRow`（0x12：14 字节，末 u16=记录内零基行号）。
- **`CustomMessages`**：TVarList `0x1E`(旧壳)/`0x27`(现代壳)：`opcode|u16 参数数|参数*`，参数=`u8 型+载荷`（2=int32/3=int64/4=float32/6=窄字符串/8=object）；数字路由首参=消息 ID，合法区间 **[1,764]**。
- 快捷栏状态在 `PlayerActor`（`setShortcut` 同索引同值幂等/异值原位替换，`synchronized` 对齐 Go mutex）。

---

## 5. 二期(下)：NPC 物化

### 5.1 数据层（`game.npc`，对齐 Go clientdata + scene_catalog_registry）

| 类 | 职责 / 关键语义 |
|---|---|
| `NpcTemplateTable` | `resources/modern/share/npc/npcconfig/**.txt`（**根目录 .txt 是字典要跳过**）。GBK + 带引号 TSV（字段可含嵌入换行，必须 CSV 语义）；前 3 行=注释/类型/列名；全空行=排版跳过；`ID` 列唯一、`script` 列可缺 |
| `NpcCreator` | creator 目录 `funcnpc.xml` 等。UTF-8/GBK 按字节有效性判定；**常带 UTF-8 BOM，解析前必须剥离**；`<creator>` 随机容器内 `<item>` 无 x 时按 `<position>` 列表或 amount×(radius*0.65 圆环) 展开，`No="creator:item:i"` |
| `NpcResolver` | 分层：defaults（`spawnDefaultsCompatV1` 44 项兼容层）→ 模板列（别名 ConfigID→ID、Sex→Gender、StopTime 取第二次出现否则唯一）→ creator 属性 → 摆放坐标 PosiX/Y/Z(+Orient=ay)。列名重复未审阅**绝不猜**首末 |
| `SceneNpcRegistry` | 启动共享全部模板表；`creator/npc_creator/<场景>/file.ini` 清单（缺文件容错、EventTrigger.xml 排除）懒加载每场景目录；`resolveFolder` 候选链：config 别名表 → resource/资源名直查 → `primarySceneFolder` 34 条别名（school09→school09_yihuagong…）→ `候选_` 前缀唯一匹配 |

### 5.2 场景核心（`game.scene`，对齐 Go internal/world + scene_lifecycle 子集）

- **`WorldScene`**：世代(generation)/版本(revision) 实体库；`begin` 换代清实体。
- **`WorldViewport`**：`plan/commit` 两段式——同代按 revision 差分 add/update/remove；
  **换代时 removes 不发 0x0E**（旧 ID 可能被新世代复用，由 ExitScene 语义清除）。
- **`SceneLifecycle`**：
  - `begin`（换代清场）/`registerNpc`（预编码 0x0D 载荷，不上线）；
  - `clientReady` 仅置 `armed`（**就绪屏障**：客户端 RegisterCallBack 前发对象会闪没）；
  - `clientActivity` 首个活动包物化一次：按对象 ID 升序逐个 `0x0D(payload)+0x1F`，
    移动过的发 `0x10` 四属性紧凑坐标（索引 9/10/11/12），随后调度 **0x1F 重放五批次**
    （+250/750/1500ms/3s/5s，Actor2 视觉回调竞态的补偿）；
  - `moveNpc`：替换实体+Posi 属性 → `0x20`(45 字节九槽) → 对账 → 1.5s 后到点 `0x1F`；
  - `startPatrol(3)`：非战斗（ScriptClass attacknpc/bossnpc）实体按 ID 序取前 N，
    每 5s X±1.5 翻转摆动；
  - 定时器全部走注入的 `ScheduledExecutorService`（测试用 ManualScheduler 记录不执行）。
- **`NpcViewport.synchronizeNpcViewport`**：半径 120（DX²+DZ²≤r²）筛选目录；
  **EventNpc 一律不上线**（任务归属接入前的唯一放行点）；**对象 ID = 目录下标+2**（玩家恒 1-1）。

### 5.3 接线（LoginFlow）

- 选角（0x04）：`world.begin`（views 之后、entryScene 之前）→ `catalogFor(场景)`（失败仅告警空目录）→ `synchronizeNpcViewport` **只注册不发帧**。
- 0x09：grants 之后 `world.clientReady()`。
- 0x0A：兼容提升 → `world.clientActivity()`（物化）→ `startPatrol(3)` → `machine.activity()` → 移动包处理。
- **移动包**（`parseMotionPosition`）：仅 67 字节包，0x26 起四段 `(tag=4,float)` 为 X/Y/Z/朝向；
  命中后视口增删（变化则 `reconcileViewport`）+ **≥2s 节流** `saveLocation`；长目的地包/越界（|x|>10000,|y|>3000,|z|>10000,|orient|>20）/非有限值一律不落盘。
- 断连：pendingLocation 补存 → faculty 持久化 → `world.close()`（停定时器+epoch 失效）→ GM 摘除。

---

## 6. 验证体系

### 6.1 golden（字节真值）

- 生成于 **Go 侧** `cmd/protocol-probe/golden_dump_test.go`（build tag `golden`，固定时钟
  2026-01-02T15:04:05Z）：

  ```bash
  cd G:\pojie\ai\9yin-go-server1\cmd\protocol-probe
  NINEYIN_GOLDEN_OUT=G:\pojie\ai\9yin-server\nineyin\src\test\resources\golden \
    go test -tags golden -run TestDumpGolden -count=1
  ```

- 覆盖 56 个文件（42 .bin + 14 .json）：登录链、协商表、选角帧、**ready 链 12 个样本帧 +
  `ready_chain_manifest.json`（1748 项 opcode/length 全序列）**、NPC 帧
  （`npc_add_object.bin` 452 字节 / `server_moving` / `scene_object_transform` /
  `scene_remove_object` / `server_location_npc`）+ `npc_catalog_stats.json`。
- `.bin` = 单帧明文（无 XOR/转义）；唯一例外 `connect_first_flight.bin` 是 wire 原样。

### 6.2 测试矩阵（16 类 86 例，`./mvnw -pl nineyin test`）

| 层 | 测试 | 手段 |
|---|---|---|
| 构造器 | ProtocolGoldenTest(16)/PlayerActorGoldenTest(4)/NpcGoldenTest(4) | 与 golden 逐字节；NpcGoldenTest 用 Chengdu 目录统计兜底 TSV/XML/Resolver 分歧 |
| 编解码 | FrameCodecTest/XorKeyTest/LoginIdentityTest | wire 往返 |
| 全链路 | LoginFlowTest(6) | EmbeddedChannel 真管线：0x09 后按 manifest 断言全序列（0x10 只比 opcode，见 §7）+ 兼容提升 + 重复 0x09 + 移动向量为 + NPC 物化（0x0D/0x1F 成对升序） |
| 生命周期 | SceneLifecycleTest(5) | 合成 NPC + ManualScheduler 手动推进：物化顺序/世代语义/巡逻翻转/0x20+0x10/0x0E |
| 存储 | JsonRoleRepositoryTest/MySqlRoleRepositoryIntegrationTest(自跳过) | Go 原生 JSON 逐字节 |
| 其它 | SessionMachine/GmCatalog(Dm)/GmHttpServer/MySqlEnv/ResourceCache/SceneNames | |

### 6.3 活体冒烟

```bash
# 构建后启动（SERVER_ROOT 指向含 resources 符号链接与 data 的目录；JDK24 必须加）
java --enable-native-access=ALL-UNNAMED -jar nineyin/target/nineyin-server-*-shaded.jar
python -I scripts/login_capture.py 127.0.0.1 <port> out.json
```

二期实测（端口 19065，避开 Go exe 的 19061）：
ready 1748 帧直方图 `{0x11:1643,0x18:99,0x15:3,0x10:2,0x27:1}` 与 manifest 完全一致；
activity 物化 **409 NPC**（409 对 0x0D/0x1F + 5 批重放 2045×0x1F + 巡逻 12×0x20/0x10），
日志零 WARN。`login_capture.py` 已支持帧洪流（`full_limit` 之后只记 opcode/length）。

---

## 7. 已知语义坑（三期前必读）

1. **PauseTime 双精度**：Go 表达式是无类型常量（双精度求值再转 float32）。Java 必须
   `(float)(N*1000.0/30)`，逐 float 运算差 1 ulp（太极拳 08 实测字节不等）。
2. **golden fixture vital 820 vs 运行时 890**：fixture 玩家的 24h 内功 Buff 在 golden
   生成时墙钟下已过期（BufferListStr 空），运行时新角色 Buff 活跃 → e2e 对 0x10 帧只比
   opcode 不比长度。View43 书帧同理（fixture 跑过 60min advanceFaculty，fill 不同）。
3. **重复 0x09 语义**：活动场景内的后期 UI 回调会重发 0x09——starter 链幂等跳过，但
   0x27 开关**必须重发**；不断连。
4. **Task_Completed 前导零行**：位图 append-only，必须发 0..finalRow 全部行（1594 行）。
5. **View46 容量 70**：18 类目 + 52 技能实例（52 不是 51，以 manifest 为准）。
6. **creator XML BOM**：UTF-8 BOM 会让 StAX 报"前言中不允许有内容"，解析前剥离。
7. **对象 ID 分配**：NPC=目录下标+2；玩家恒 1-1；0x0D 的 owner 字段=场景身份 1。
8. **armed 屏障**：ClientReady 之前物化的对象会被客户端清表闪没——注册（选角时）与
   上线（首个 0x0A）分离是刻意设计。
9. **0x1F 重放五批次**：不是冗余——Actor2 视觉回调与物化有竞态，官服行为如此。
10. **端口**：Go exe 占 19061/4000；本服 jar 内 server.properties 固定端口，并行调试需
    patch 构建产物（源码属性文件勿改）或换 SERVER_ROOT 部署。

---

## 8. 三期路线（遗留清单）

- 0x0C 场景切换重入链（ExitScene→EntryScene→0x09 稳定重入屏障 + 记录行/View43 恢复，`reenterScene` 已预置）；
- 传送门（`rule/door_data.ini` 路由 + 移动越界触发 + 同场景 Teleport）；
- 0x07 对象选择（LastObject + 0x1C 菜单 + 商店/仓库 View 61/4）；
- 战斗（211 技能链、cooldown_rec、combat tick、0x20 敌对巡逻互斥）；
- 自定义消息信封（0x0A 内嵌 TVarList：200/201 快捷栏、240 打坐、216/217 轻功、218 格挡、215/1006 内功修炼）；
- 1s ticker（修炼结算/打坐恢复/淤伤恢复/Buff 到期）；
- 心跳 0x38 回显（本期确认 Go 源码无此帧，如需对齐旧 Java 分支另议）。

## 附：二期提交清单

- `ab6f715` 二期(上)：RecordMessages/CustomMessages/starter 包/PlayerProgress.viewFrames/
  PlayerActor 快捷栏/LoginFlow ready 链/ready_chain_manifest + 12 样本 golden/75→86 测试
- `8b229cb` 二期(下)：game.npc 五类/game.scene 四类/NpcMessages/移动包/冒烟与脚本

---

# 三期：内修闭环 + 装备/背包/内功 DB 下发（`a1219d9` `05272c7` `c27f3b0`）

## 决策记录（用户确认）

1. **协商属性表换官方 2424 项整表**（`official_property_table.txt`，bak 从官服提取）：
   verbatim 透传 0x09 帧；编码按名查表（`PropertyTable`/`Props`），Go 复刻方言里官方
   表没有的 36 名（PosiX/Orient/BufferListStr/Race/AITemplate…）从属性帧剔除——位置走
   0x0D 头 + 0x1F、Buff 走 BufferInfo 槽。带属性帧 golden 改 Java 官方快照
   （`*_official.*`，regen：`-Dnineyin.golden.regen=true`）；无属性帧仍对齐 Go golden。
2. **MaxVisuals 官方语义=画质档位** u16=100（capture-fxgame-op2 官服 0x0B 实证）；
   Go 复刻的 1000000 是私设，已纠正。
3. **bak 17 视图容器**、**DB 优先 + starter 回退**、**含扩容锚点 + DB 快捷栏**。

## 内修闭环（1006/21 演武 + 1s ticker）

- `CustomC2S`：0x1E/0x27 直达 + 0x0A 信封（21 字节头含 opcode）TVarList 解析，
  `exactInt32` 容忍 double 整值；白名单 {200,201,240,211,215,216,217,218,1006}，
  本期实现 215/1006，其余受理记日志。
- `FacultyCustomHandler` 回复帧序（Go 逐行）：215→[0x10]；1006/1 选书 [0x18,0x10]
  未知静默；/11 普通恒 LogicState0；/21 演武 [0x10, 191/(22,0)] LogicState=108；
  /22,/12 停 [0x10]；/23 演武卡 [0x18,0x10]→升级 191/14→191/23（绝不出 24）/
  否则 191/24、陈旧吞掉；/24 FacultyName 置空+全量；/31,32 受理；未知断连。
- 1s ticker（`scheduleWithFixedDelay`）：整分钟结算（200/min、cost=ceil(fill/2)、
  满级/枯竭自动停），变化 [0x18 书帧,0x10]+持久化，静默秒零帧；断连取消。
- `FacultyStore.attachDatabase`：role_faculty 快照读优先/写 UPSERT best-effort，
  JSON 主档。View43 经 DB 恢复自动真书化（role 618 = 48 本）。

## 物品域 DB 下发

- `world.db.RoleInventoryStore`（只读池，GmCatalogDb 同策略）：equips/bagItems
  （game_items 200 一批富化）/skills（JOIN game_skills）/shortcuts/bagpacks。
- `protocol.EquipmentModels`：equipment.ini 静态（Photo 链 itemartstatic.ini）。
- `protocol.InventoryMessages`：17 视图（8 主页 cap90+BaseCap18/8 扩展/View1 cap48）、
  锚点（页号→扩展视图 {1:3,2:122,3:124,4:126}，BeginPos=19+Σboost，永久=720h）、
  背包成对 `0x19(view,slot)→0x18→0x19(镜像,slot)`（真实槽位防误删）、纸娃娃行、
  UniqueID=MD5(roleId:bag|equip:slot)[:16hex]、WeaponMode 0x10。
- LoginFlow：选角 0x0B 前建 17 视图+锚点，vitals 后 DB 模式物品整推；ready 段
  View40=role_skills（十属性行）+快捷栏=role_shortcuts，空则 starter 回退。
- DB 数据基线（mysql-26）：role 618 = 8 穿戴/27 背包/190 武学/48 内功/117 快捷栏/4 锚点。

## 验证

- 109/109 绿：FacultyProgressTest 11 例（Go 向量）、InventoryMessagesTest 6 例
  （成对存活/锚点数学）、RoleInventoryStore/FacultyStore DB 集成（自跳过）、
  LoginFlowTest 0x1E 直达 e2e、官方快照 golden ×13。
- **真机待验**：role 618 账号登录目检（背包 27 格/纸娃娃 8 件/内功 48 书/技能页/
  演武开卡/内修涨条）——冒烟账号是自动新角色走 starter 回退，DB 模式需真实账号。

## 四期候选

msg30 穿脱/使用（0x19/0x18 双向同步+WeaponMode 重推）、0x07 对象选择/菜单/商店、
战斗（211/cooldown/combat tick）、0x0C 切图重入恢复链、传送门、200/201/240/216-218 信封。
