# Go 版服务端（protocol-probe / 9yin-game-native-menu.exe）协议实现分析

> 分析日期：2026-10-30。分析对象为 `E:\Workspace\九阴服务端\` 下的 Go 构建产物，
> 目的是为本 Java 重写工程（nineyin-server）提供"参考服到底实现了哪些消息"的权威对照。
> 证据来源：exe 内嵌符号（3822 个 `main.*` 函数名，未剥离）、日志格式串、
> `artifacts/audit/` 逆向结论文档，以及本仓库现有实现的交叉比对。

## 0. exe 身份

| 项 | 值 |
|---|---|
| 文件 | `E:\Workspace\九阴服务端\9yin-game-native-menu.exe`（18.4MB，正在使用）与 `artifacts\local\bin\protocol-probe.exe`（13MB，10-20 快照） |
| 构建 | Go 1.23.5，PE32+ x86-64 控制台程序 |
| 模块 | `github.com/local/9yin-go-server`，命令 `cmd/protocol-probe`（源码原在 `H:/9yin-go-server2/`，本机已不存在） |
| 运行形态 | `-listen`(游戏端口 19061) + `-gm-listen`(GM 网页面板 19062) + `-log-file`，配合 `loopback-lister.ps1`(区服列表 4000) |
| 名称由来 | "native-menu" 指该构建实现了客户端原生菜单（NPC 对话/任务菜单 ui_menu 记录）驱动的里程碑，不是菜单工具 |

两个 exe 是**同一命令的不同时期构建**；`artifacts/audit/9yin-game-native-menu.pre-tiguan-*.exe` 是更早期版本存档。
分析以根目录 18.4MB 版本为准。

## 1. 帧层（FxNet2 opcode）

帧格式：TCP 上的 `XOR(4字节循环key) + 0xEE转义 + EE EE 终止符` 分帧，帧内 `u8 opcode + 小端字段`
（详见 [transport-protocol-and-ecs.md](transport-protocol-and-ecs.md)）。

### 1.1 C2S：exe 响应的 opcode

| opcode | 语义 | exe 证据（日志格式串） | Java 现状 |
|---|---|---|---|
| 0x02 | ClientLogin | `session=%d remote=%s opcode=0x02 decoded_len=%d account=%s` | ✅ `LoginFlow.onLogin` |
| 0x03 | ClientMsgWorldInfo | `write empty world info: %v` / `sent ServerWorldInfo with five open books` | ✅ `LoginFlow.onWorldInfo` |
| 0x04 | 选角 | `choose role without an account-owned role` | ✅ `LoginFlow.onChooseRole` |
| 0x05 | 建角 | `INSERT INTO roles(...)` / `persist created role: %v` | ✅ `LoginFlow.onCreateRole` |
| 0x09 | ClientReady | `ignored duplicate ClientReady in active scene` | ✅ `LoginFlow.onClientReady` |
| 0x0A | Activity CustomSend | `client activity CustomSend opcode=0x0A msg_id=%d args=[%s]` | ✅ `SceneFlow.onActivity` → `dispatchCustom` |
| 0x18 | Ping/心跳 | `write heartbeat reply: %v` | ✅ `SceneFlow.onPing` |
| 0x1E | CustomSend 直发（旧客户端） | `client CustomSend opcode=0x%02X msg_id=%d args=[%s]` | ✅ `SceneFlow.onDirectCustom` |
| 0x27 | CustomSend 直发（新客户端） | 同上 | ✅ 同上（同一 handler） |
| 其他 | 未知 opcode | `unknown C2S opcode=0x%02X len=%d dump=%x`（**打 dump 而非丢弃**，探针行为） | 🟨 本轮补齐：未知 opcode 首现 INFO + hex dump |

另有逐帧 C2S 日志 `%s: C2S opcode=0x%02X len=%d`（对应本工程 `FxWireLog`）。

### 1.2 S2C：exe 会发送的 opcode

| opcode | 消息 | exe 证据 | Java 现状 |
|---|---|---|---|
| 0x01/0x02 | 密钥轮换 set_verify/set_encode | 读写方向密钥独立（登录流程不同时机轮换） | ✅ `FxKey`/`FxFrameEncoder` |
| 0x04 | 角色列表 | `sent ServerPlayerRoles(opcode=0x04, role_exists=%t)` | ✅ |
| 0x05 | 剧本/世界信息 | `sent ServerWorldInfo with five open books` | ✅ |
| 0x08 | （加密协商/验证帧） | transport 文档 | ✅ |
| 0x09 | 属性表（2424 条） | `write property table: %v` | ✅ `OfficialPropertyTable` |
| 0x0A | 记录表 | bootstrap 链 | ✅ `BootstrapPackets` |
| 0x0B | ServerEntryScene | `sent PlayerEntry opcode=0x0B object=1 scene=%s resource=%s` | ✅ |
| 0x0C | ServerExitScene | `encodeExitScene`（换场景 0x0C→等清理→0x0B） | ✅ `SceneSwitcher` |
| 0x0D/0x0E | ServerAddObject/RemoveObject | `serverCreateView(WithProperties)`、`serverDeleteView`、`materialize scene object ... opcode=0x%02X payload_sha256=%x` | ✅ `NpcPackets` 等 |
| 0x10 | ServerObjectProperty（增量） | `change warning %d -> %d via normal 0x10`、`sitcross native state %d -> %d via normal 0x10` | ✅（见 §3 疑似偏差） |
| 0x11~0x14 | ServerRecord*（增删行/网格/清空） | `serverRecordAddCellRows/DelRow/Clear/CooldownRow/...` 十余变体；踢馆计时 `0x11 target=2 Time_Limit_Form_Rec` | ✅ 0x11/0x12；🟨 0x13/0x14 暂无使用场景 |
| 0x15~0x19 | ServerView*（AOI 视图） | `serverAllDest`、`encodePresenceWaypointSelf`、`scene viewport delta adds=%d removes=%d` | ✅ 0x18 ViewAdd / 0x19 ViewRemove（`InventoryPackets`） |
| 0x1F/0x20 | ServerLocation/Moving | `serverLocation`（`replayed ServerLocation for %d scene objects`）、`serverMoving` | ✅ |
| 0x1E/0x27（S2C 方向） | 定向 custom 帧 | `write qinggong 0x1E-02 idx=%d`、`0x1E-04 idx=205`、`0x1E-06 idx=20`、`0x1E-07 idx=337`（轻功槽位走 0x1E 子命令） | ✅ `CustomPackets`/轻功槽位 |
| custom 通用 | `serverCustomIntMessage/StringMessage` + Modern/WithOpcode 变体 | `encodeServerCustomValuesWithOpcode` | ✅ `CustomPackets` |

## 2. CustomSend msgId 子协议（0x0A/0x1E/0x27 载荷）

客户端把功能消息塞进 CustomSend 的 `msgId`（GM 探针面板证实范围 **[1,764]**，踢馆 865 走直发路由）。
exe 为每个功能实现一个 `handleXXX`（共 75 个，全量清单见 §2.2）。

### 2.1 编号与参数布局已钉死的消息（可直接对齐实现）

证据为 exe 中的参数校验串 `expects TVarList(...)` / `handle ... NNN`：

| msgId | 功能 | 参数布局 | exe 行为要点 | Java 现状 |
|---|---|---|---|---|
| 30 | 移动物品 | `[fromView, fromSlot, toView, toSlot]` | 卸下 `[1,槽,121,0]`；0x18/0x19 View 帧同步 | ✅（含穿/脱武器后 WeaponMode=1597 模型推送） |
| 61 | 显示装备类型 showequip_type | `[type:int]` | `showequip_type set to %d`；非法值 `invalid value %d`；影响装备外观显示（gear visual 帧） | 🟨 本轮：校验+存储+日志（外观帧待钉死） |
| 64/70/71 | 商店 打开/购买/关闭 | — | 完整商店系统（view/stock/capital），`shop %s item %s stock=%d < requested=%d`、`operate_type_swap_goods` | 🟨 仅日志（S2C 响应格式待钉死） |
| 144 | 副本结算确认 | — | `awaiting C2S 144`（col result 确认） | ⬜ |
| 180 | 卡片（activity card） | — | `handle activity card 180` | ⬜ |
| 200/201 | 快捷键 设置/删除 | `[idx, kind(str), id(str)]` / `[idx]` | `shortcut expects int index and two strings`；同 index 先 0x12 删旧行 | ✅ |
| 209 | 任务 | — | `handle task 209: %v` | ⬜ |
| 211 | 技能释放 | `[skillId(str)]` | `reject skill before player spawn id=%s`、`reject unlearned installed skill id=%s` | 🟨（未校验"已学习"） |
| 216/217 | 轻功 基本/主动 | — | `reject basic qinggong hold cost=%d point=%d/%d`；ActiveQGSkillRec 行 | ✅ |
| 218 | 主动招架 | — | `manual parry before scene player exists`、`publish manual parry state` | ✅ |
| 223 / 5223 | 操作模式切换 | `[mode:int]` | `set operate mode=%d (0=3D 1=2.5D), shortcutKey updated` | 🟨（见 §3 疑似偏差） |
| 240 | 打坐 | `[start_or_stop:int]` | `sitcross native state %d -> %d via normal 0x10`（1=坐下 0/2=起身） | ✅ |
| 270 | 暮色道具技能 | — | `handle twilight item skill 270` | ⬜ |
| 291 | PK 模式切换 | `[mode:int]` | `change pk mode expects TVarList(291, mode)`；`change pk mode invalid mode %s`；GM 面板 `set_pk_mode` 同源 | 🟨 本轮实现 |
| 292 | 警告开关 | 无参 | `warning expects TVarList(292) with no extra values`；`change warning %d -> %d via normal 0x10`（toggle 后 0x10 推送，属性 ID 未钉死） | 🟨 本轮实现（存储+日志） |
| 293 | PK 保护开关 | `[type:int, onoff:int]` | `toggle expects TVarList(293, type, onoff)`；`client pk protect toggle type=%d on=%d` | 🟨 本轮实现 |
| 382 | 关闭掉落 | — | `handle close drop 382` | ⬜（Java 无掉落实体） |
| 560 | 充值商店购买 | `[560,3,charge_id,nums]` | `buy expects [560,3,charge_id,nums]`；`charge shop 560 arrived before role selection` | ⬜ |
| 249 | 充值确认 | — | `C2S 249 (player never charged: no movement); cancelled, NOT complete` | ⬜ |
| 784 | 乱斗 | `[sub=10 OPEN_LIMITE ...]` | `luandou 784: encode reply` | ⬜ |
| 865 | 踢馆 | sub 6=确认切换 / 7=离场 | `CUSTOM_MSG_TG_SWITCH`（见 audit/tiguan 文档完整线序） | ⬜ |
| 868 | 踢馆轮盘 | `[action]` | `tiguan roulette 868 bad action %s` | ⬜ |
| 924 | 消息延迟 | — | `message delay 924 response` | ⬜ |
| 943 | 兵录修改 | — | `handle binglu modify 943` | ⬜ |
| 819 | 用户须知签署 | — | （Java 侧实测闭环） | ✅ |
| 517 | GM 探针默认 msgId | `[subType]` | `GM custom probe msgID=%d subType=%d` | ⬜（GM 面板未接） |

### 2.2 exe 的 75 个 custom handler 按功能分组

（编号未钉死的只列功能名；将来实装前先用 GM 探针或抓包确认编号）

- **战斗**：Skill、CardSkillCast、ActiveParry、ManualParry、LuanDou(784)、ZhaoShi、FightwordDebug
- **轻功**：QingGong、ActiveQingGong、BasicQingGongStart/End/Landing、BigQingGongRelease、QingGongAirSkill、YufengHoldPowerEnd（御风）、GJLandingDetect
- **踢馆/副本**：Tiguan(865)、Clone（ENTRY_CLONE/OPEN_COL_AWARD/144 确认）、Twilight 四阶段（StageObjectRequest/TaskItemUse/ItemSkill 270）
- **物品/装备**：UseItem、ItemAction、MoveItem(30)、PickupDrop、CloseDrop(382)、EquipItemUse、OnestepEquip、WeaponRecast 全套（Preview/Lock/Unlock/Sure）、BodyItemUse、ItemBuffUse、GiftBoxClaim、GiftBoxItemUse、ReputeItemUse、WuJiBookUse、WuJiSubCommand、YufengItemUse、ModifyBinglu(943)
- **商店/货币**：ShopBuy(70)、ChargeShop(560/249)、AttributeMall、VIP、VIPCustomAt
- **外观/卡片/身份**：Card(180)、FashionSuit、AttireEquipBlend、SaveCustomizing、CPanelBodyScale、ShowEquipType(61)、SetTitle
- **社交/系统**：Chat、Mail（MailDelete/MailSelection）、Team（含 companion）、ChangePKMode(291)/ChangePKProtect(293)/ChangeWarning(292)、Sitcross(240)、Shortcut(200/201)/SaveShortcutScheme、SetOperateMode(223)、PlayerProgress、LifeJob、JingMai、NinJiuZhi（九阴志）、Tersure、GM、MessageDelay(924)、CompanionStory、Origin 全套（Get/SetShow/GetPrize/RequireNotify/Active）、MountSummon、NewRide/Ride/RideSelect/RideSkillUse/RidingStartSkill、ChangeWarning
- **探针**：GMCustom（`GM custom probe msgID=%d subType=%d`）、GM action probe（技能帧/interact268 直驱）

## 3. Java 现状 ↔ exe 对照结论（2026-10-30 比对）

### 3.1 一致项（帧层全闭合，custom 层核心一致）

帧层 8 个 C2S opcode 与 S2C 帧家族两端完全对齐；custom 层 30/200/201/211/216/217/218/223/240/819
的语义与参数布局和 exe 一致（打坐/快捷键/轻功计费/招架等核心链路同构）。

### 3.2 疑似偏差项（需实机验证）

| # | 位置 | 现象 | 风险与建议 |
|---|---|---|---|
| 1 | `LoginFlow:264`、`SceneFlow:393`（223 OperateMode） | 官服属性表声明 OperateMode 为 **i64(type 3)**，Java 用 `objectPropertyInt`（i32 编码）推送，初始值与增量两处一致地"错" | 若客户端按 0x09 表类型解析 0x10 值，i32 会少 4 字节。两处编码一致，说明若链路"能用"可能是客户端容忍或忽略该属性。**建议实机抓包比对 Go 侧 223 推送的宽度**后再改 |
| 2 | 223 处理 | Go 在切模式时会同步更新 ShortcutKey（`shortcutKey updated`），Java 只推 OperateMode | 客户端 3D/2.5D 快捷栏布局可能不同步；待实机验证后补 ShortcutKey 推送 |
| 3 | 211 技能 | Go 校验"技能已安装/已学习"（`reject unlearned installed skill id=%s`），Java 无此校验任意技能串都放行 | 私服可接受；正式对齐需接 `skill_init.ini` 学习表 |
| 4 | 292 警告开关 | exe 确认走 `normal 0x10` 属性推送，但**具体属性 ID 未能从字符串钉死** | 本轮先做状态翻转+存储+日志；后续用 GM 探针（msgId=292）在 Go 服上抓 S2C 帧即可钉死 |

### 3.3 本轮新增对齐项

- **msgId 291**：PK 模式切换 → 校验 int、存会话、**0x10 推送 PKMode(436, short)**（官服属性表 type 1，用 i16 编码，符合"按表类型编码"纪律）
- **msgId 292**：警告开关（无参 toggle，`change warning %d -> %d` 日志对齐）
- **msgId 293**：PK 保护开关（`pk protect toggle type=%d on=%d` 日志对齐）
- **msgId 61**：showequip_type（校验+存储+日志；装备外观帧待钉死）
- 未知 C2S opcode：对齐探针行为——首现 INFO 带 `len + hex dump`（Go：`unknown C2S opcode=0x%02X len=%d dump=%x`）
- **`TVarList` 参数校验器**（`com.nineyin.game.TVarList`）：把参考服的
  `expects TVarList(NNN, ...)` 校验层做成声明式规格（msgId + 参数个数 + 类型/字面值，
  如 `TVarList.check(msg, 293, intArg("type"), intArg("onoff"))`），
  错误文本与参考服同构（`TVarList(293, type, onoff)` + 实际值摘要），
  便于实机日志两边对照；240/291/292/293/61 五个 handler 已迁移到该层。
  后续钉死新 msgId 的参数布局后（§2.1 表），直接按规格实现即可。
- **穿/脱武器视觉推送**（对齐 `pushGearVisual`）：msgId 30 触及装备纸娃娃时 0x10 推送
  WeaponMode(1597) 模型路径（`InventoryPackets.weaponModeFrame`，EquipType=Weapon/ShotWeapon/InnerWeapon
  判定，equipment.ini→itemartstatic.ini 解析、缺失回退 config 原值）；武器帧并入 `inventoryFrames`
  缓存，初始进场景与 0x0C 重进自动恢复手持武器

装备系统仍缺（对照 exe）：装备属性加成（equipStrAdd/equipPhyDef 等未接 CombatStats）、
右键使用穿戴（EquipItemUse）/一键穿（OnestepEquip）两个未钉死编号的 msgId、
时装外观刷新（FashionCoat=1352 系）、重铸 WeaponRecast 全套。

## 4. 后续继续挖掘的方法

exe 未剥符号，`strings + grep` 即可继续钉死协议细节（在 Git Bash 下）：

```bash
EXE="/e/Workspace/九阴服务端/9yin-game-native-menu.exe"
# 1) 某 msgId 的参数校验串（最可靠：编号+参数布局）
strings -n 8 "$EXE" | grep -aoE "TVarList\([0-9]+[^)]*\)[^\"]{0,50}" | sort -u
# 2) 某功能的日志（行为+响应方向）
strings -n 8 "$EXE" | grep -aiE "shop buy|charge shop"
# 3) 全部 handler 清单
strings -n 4 "$EXE" | grep -aoE "main\.handle[A-Za-z0-9_]+" | sort -u
# 4) S2C 发送器
strings -n 4 "$EXE" | grep -aoE "main\.(send|write|encode|publish)[A-Za-z0-9_]+" | sort -u
```

局限性：msgId→handler 的精确映射（switch 跳表）与 0x10 属性 ID 常量在字符串层不可见，
需要反汇编（IDA/Ghidra）或在 Go 服上用 GM 探针抓 S2C 帧；`H:/9yin-go-server2` 源码目录已不存在，
若能找回源码则一切细节可直接闭合。

## 5. 证据字符串摘录（关键几条）

```
change pk mode expects TVarList(291, mode), got %v          ← 291 PK 模式
change pk mode invalid mode %s
warning expects TVarList(292) with no extra values, got %v  ← 292 警告开关（无参）
%s: change warning %d -> %d via normal 0x10                 ← 292 响应走 0x10
toggle expects TVarList(293, type, onoff), got %v           ← 293 PK 保护
%s: client pk protect toggle type=%d on=%d
%s: handle showequip_type 61: %v
showequip_type set to %d / showequip_type invalid value %d
%s: unknown C2S opcode=0x%02X len=%d dump=%x                ← 未知帧打 dump
%s: C2S opcode=0x%02X len=%d                                ← 逐帧日志
sitcross expects TVarList(240, int start_or_stop), got %v
%s: sent ServerPlayerRoles(opcode=0x04, role_exists=%t)
%s: sent PlayerEntry opcode=0x0B object=1 scene=%s resource=%s
buy expects [560,3,charge_id,nums], got %d values           ← 560 充值商店
```
