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
| 144 | 副本结算确认 | — | `awaiting C2S 144`（col result 确认） | 🟨 第三轮：确认收下记日志（Java 无待确认 col result） |
| 180 | 卡片（activity card） | — | `handle activity card 180` | 🟨 第三轮：sub_command 路由桩（USE/USESKILL/SAVE 子编号待钉死） |
| 200/201 | 快捷键 设置/删除 | `[idx, kind(str), id(str)]` / `[idx]` | `shortcut expects int index and two strings`；同 index 先 0x12 删旧行 | ✅ |
| 209 | 任务 | — | `handle task 209: %v`、`task 209 ignored: no active task` | 🟨 第三轮：无活跃任务忽略（日志对齐） |
| 211 | 技能释放 | `[skillId(str)]` | `reject skill before player spawn id=%s`、`reject unlearned installed skill id=%s` | ✅（学习表+编译目录+计费/CD/动作/区域结算，见 §3.4） |
| 216/217 | 轻功 基本/主动 | — | `reject basic qinggong hold cost=%d point=%d/%d`；ActiveQGSkillRec 行 | ✅ |
| 218 | 主动招架 | — | `manual parry before scene player exists`、`publish manual parry state` | ✅ |
| 223 / 5223 | 操作模式切换 | `[mode:int]` | `set operate mode=%d (0=3D 1=2.5D), shortcutKey updated` | 🟨（见 §3 疑似偏差） |
| 240 | 打坐 | `[start_or_stop:int]` | `sitcross native state %d -> %d via normal 0x10`（1=坐下 0/2=起身） | ✅ |
| 270 | 暮色道具技能 | `[skillId(str)]` | `twilight item skill %s has no active scene` | 🟨 第三轮：无活跃暮色场景拒绝（日志对齐） |
| 291 | PK 模式切换 | `[mode:int]` | `change pk mode expects TVarList(291, mode)`；`change pk mode invalid mode %s`；GM 面板 `set_pk_mode` 同源 | 🟨 本轮实现 |
| 292 | 警告开关 | 无参 | `warning expects TVarList(292) with no extra values`；`change warning %d -> %d via normal 0x10`（toggle 后 0x10 推送，属性 ID 未钉死） | 🟨 本轮实现（存储+日志） |
| 293 | PK 保护开关 | `[type:int, onoff:int]` | `toggle expects TVarList(293, type, onoff)`；`client pk protect toggle type=%d on=%d` | 🟨 本轮实现 |
| 382 | 关闭掉落 | — | `handle close drop 382`、`closed DropBox via 382` | 🟨 第三轮：日志对齐（Java 无 DropBox 实体） |
| 560 | 充值商店购买 | `[560, cmd, ...]`（buy=`[560,3,charge_id,nums]`） | `buy expects [560,3,charge_id,nums]`、`nums=%d outside 1..%d`、`no goods charge_id=%d`、`price_type=%d not supported`、`needs gold=%d, have %d`、`(%s): bag full`、`bought %s x%d (charge_id=%d) for %d gold, left=%d`；cmd 2/4/5 忽略 | 🟨 第三轮：货品表+完整校验链+金币扣减+入包 grant（页面 S2C 与 capital 映射待钉死） |
| 249 | 充值确认 | — | `Yufeng charge window expired … without C2S 249 (player never charged: no movement); cancelled, NOT complete` | 🟨 第三轮：御风蓄力确认收下记日志（Java 无蓄力窗口） |
| 784 | 乱斗 | `[sub(int)=10 OPEN_LIMITE index, origin_id]` | `luandou 784: encode reply`、`missing sub_msg args=%d`、`bad sub_msg type %s`、`sub=10 OPEN_LIMITE index=%d origin_id=%d` | 🟨 第三轮：sub 路由（reply 格式待钉死） |
| 865 | 踢馆 | sub 6=确认切换 / 7=离场 | `CUSTOM_MSG_TG_SWITCH`（见 audit/tiguan 文档完整线序）、`tiguan 865 bad sub_msg %s` | 🟨 第三轮：sub 6/7 校验+日志（分组场景生命周期待实装） |
| 868 | 踢馆轮盘 | `[action(str)]` | `tiguan roulette 868 bad action %s`、`missing action` | 🟨 第三轮：action 校验+日志（合法词表待钉死） |
| 924 | 消息延迟 | `[924, tick(i64)]` | `message delay 924: expected [int32:924, int64:tick]` → `message delay 924 response` | 🟨 第三轮：布局钉死+回显 tick（响应载体假设 0x1E 同 msgId，待抓包） |
| 943 | 兵录修改 | `[943, sub(int), name(str), num(int)]` | `binglu modify: missing sub message`、`sub message is not an integer`、`unhandled sub=%d name=%q num=%d` | 🟨 第三轮：sub 三分支校验日志（binglu 数据表未接） |
| 819 | 用户须知签署 | — | （Java 侧实测闭环） | ✅ |
| 517 | GM 探针默认 msgId | `[subType]` | `GM custom probe msgID=%d subType=%d` | 🟨 第三轮：日志（GM 面板直驱未接） |

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
| 3 | 211 技能 | Go 校验"技能已安装/已学习"（`reject unlearned installed skill id=%s`），Java 无此校验任意技能串都放行 | **已闭环（2026-10-30 技能系统轮）**：学习表 `skill_init.ini` + 编译目录落地，见 §3.4 |
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

### 3.4 技能系统轮（2026-10-30 第二轮）

以 §4 方法在权威 exe（18.4MB）逐条核实后落地。**重要警示**：工作区
`/e/Workspace/project/game/9yin-go-server1/` 存有 Go 源码，但其根目录 exe 为
7 月 26 日 11MB 旧构建（md5 `ddd1eb9f…`），与在用 10 月 16 日 18.4MB exe
（md5 `f0f4356a…`）**不一致**——该源码只能当结构提示，行为以 exe 字符串为准。
本轮 exe 新钉死的演进证据（旧源码没有）：`loaded skill_init.ini skills=%d path=%s`、
`skill_init.ini empty (%s); using built-in starter set`、`[skill-save]` 角色存档
（`.json unavailable; falling back to skill_init.ini`）、`reject skill id=%s
use_limit=%s need_weapon=%v worn=%d (%s)`（skill_use_limit.ini 543 包武器门控）、
`target out of range (%.1fm > %.1fm)` / `requires air` / `while airborne` 三类新校验、
区域技能多跳 `hits=%d split=%d cadence=%s damage_total=%d`、`settled compiled
target debuff`。

Java 侧落地（`com.nineyin.game.SkillInitTable` / `SkillCatalog` / SceneFlow 211 重写）：

- **学习表**：skill_init.ini（GBK，17,855 节）→ 已学习集；缺失/为空回退内置 23 技
  （`SkillPackets.STARTER_SKILLS`：花神七式 / 心佛掌 / 太极拳古谱，staticData/itemType
  与 skill_init.ini 逐一核对一致）。View 40 下发维持 DB 角色技能优先、内置集兜底
  （exe 将全量学习集推入 View 40 并有 u16 容量护栏，17,855 行的整推留待实机抓包后再决策）。
- **编译目录**：skill_new → skill_static →（normal/lock）varprop → consume →
  damage_calculate → attack_hitshape → zhaoshi_player*（动作时间轴）→ buff 三表，
  仅 SkillNormal/SkillLock 入索引，(id,level) 惰性编译缓存。真实数据实测：
  `indexed=17855 executable_level1=3550 skipped={missing_level_varprop=34,
  missing_player_action=14269, missing_static=2} action_files=4`，加载 ~1.7s。
- **211 链路**：malformed / before spawn / unlearned / without compiled level response /
  without living selected target / 锁(500ms) / 个人 CD / 公共 CD(team 回退 -category) /
  dead / MP / SP / 轻功点 全链拒绝，日志与 exe 同构；成功路径发
  cooldown_rec(0x11 表5) 行 + vital(0x10) + State 起手动作（zhaoshi 首段）+ 后续段按
  `前段时长−入融` 排期；伤害效果按目标模式结算（单体/圆/扇/矩形/目标区域 + 高度差），
  伤害掷点仍走 CombatFormulas（倍率 = 编译伤害/100，如 hsqs01 一级 113 → 1.13）；
  击杀掉碎银 MaxHP/100 夹 [1,99] 并 0x10 推 CapitalType1(416)；技能 Buff 按
  首选槽→同 ConfigID 复用→11..24 空闲槽分配（红霸体/架势轮换 overlay 已接）；
  total_action 后清 CurSkill*（仅匹配本次技能，旧定时器不误清）并回 stand。
- **未对齐项（后续轮）**：skill_use_limit 武器门控（包表已装载、按技能的绑定键未钉死）、
  施法距离/空中校验（Java 无 airborne 状态，需先接轻功悬空态）、区域多跳 split/cadence
  （拆分公式未钉死，现单跳 split=1）、[skill-save] 学习进度持久化、
  `learned skill count %d exceeds u16 view capacity` 的全量 View 推送。

### 3.5 custom 子协议轮（2026-10-30 第三轮）

把 §2.1 表中全部 ⬜ 消息推进到 🟨/布局钉死：13 个新 case 进入 `SceneFlow.dispatchCustom`
（924/560/249/144/180/209/270/382/943/784/865/868/517），每个 handler 的校验分支与
日志文本逐条对齐 exe 证据串（本轮新钉死的关键串见 §5 下半段）。

- **924 消息延迟**：布局钉死 `[int32:924, int64:tick]`（`expected [int32:924, int64:tick]`），
  合法即回显 tick（`message delay 924 response`）。回显载体按 0x1E 同 msgId 假设实现，
  精确格式待实机抓包。
- **560 充值商城**：`ChargeShopCatalog` 装载 ini/charge_shop_goods.ini（GBK 9 字段，与
  Go resources 同源；`catalog ready items=%d file=%s`）。校验链完整落地：before role
  selection / cmd 非整数 / cmd 2(present)、4(favorite)、5(favorite remove) 忽略 /
  buy 参数个数 / charge_id、nums 非整数 / nums 越界(1..99，上界待钉死) / no goods /
  price_type≠0 / 金币不足 → 拒绝路径日志全部同构。成功路径：game_items 可解析的货品
  入背包（`RoleInventoryStore.addBagItem`：item_type/落页由统一表驱动，首空格 1..90，
  ViewAdd+镜像帧，`inventoryFrames` 缓存重建）→ 扣金币 → 0x10 推 CapitalType0(415) →
  `charge shop bought %s x%d (charge_id=%d) for %d gold, left=%d`。
  **待钉死项**：页面 S2C（`encode charge shop page`）格式、capital 与金币的精确映射
  （现按 CapitalType0 建模，`PlayerActor.gold` 初始 99999 与 silver 同为私服娱乐值）、
  时装/坐骑/礼包的专用 store grant（未实装，不扣币记 pending）。
- **943/784/865/868/180/209/270/382/144/249/517**：C2S 校验层+日志对齐桩
  （各分支文本与 exe 同构，见 §2.1）；涉及的数据/场景系统（binglu 表、乱斗 reply、
  踢馆分组场景生命周期、卡片子命令编号、任务面板状态机、DropBox、col result、
  御风蓄力窗口）留待后续轮。
- **GM**：`set_gold` 补齐（GM 面板 console.html 已有调用，服务端原本缺动作），
  镜像 set_silver 推 0x10 CapitalType0。
- **建角会话 roleId 回填**（本轮发现的存量缺陷）：`onCreateRole` 存档后原不回填
  `session.roleId`（只有二次登录 onLogin 才有；JSON 后端恒 0），导致按 roleId 记账的
  消息（30/560 等）建角当次会话全部失效。现建角后回读 `roleStore.get()` 回填（MySQL
  自增 id）；560 的"未选角"判定同步改用 `session.hasRole`（对齐 Go 语义，roleId 是
  存储产物不能当选角依据）。
- **本轮新钉死编号（后续轮候补）**：688 tersure、301 VIP、864 生活职业、jingmai
  （`jingmai msg=%d`）、770 C-面板 BodyScale、19 时装套装、692 面料混合(692 缺参校验串)、
  origin 700/701/702/705/706、145 副本奖励 View80 拾取、380 掉落拾取(vpos)。

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
reject skill before player spawn id=%s                      ← 211 未出生拒绝
reject unlearned installed skill id=%s                      ← 211 未学习拒绝（本轮闭环）
reject skill id=%s without living selected target           ← 211 需目标技无选中
accepted skill id=%s mp=%d sp=%d qg=%d damage=%d target=%d targets=%d personal_cd=%s public_cd=%s
loaded skill_init.ini skills=%d path=%s                     ← 学习表装载（17,855）
settled compiled area skill id=%s mode=%d ... hits=%d split=%d cadence=%s damage_total=%d
skill defeated NPCs targets=%d silver_drop=%d               ← 击杀掉碎银（MaxHP/100 夹 [1,99]）
```

### 5.1 第三轮新增摘录（custom 子协议，2026-10-30）

```
message delay 924: expected [int32:924, int64:tick]         ← 924 布局钉死
message delay 924 response: %w                              ← 924 有 S2C 响应
charge shop 560 arrived before role selection               ← 560 早到
charge shop 560 command is not an integer: %s               ← 560 cmd 校验
charge shop buy charge_id is not an integer: %s
charge shop buy nums is not an integer: %s
charge shop buy nums=%d outside 1..%d                       ← 上界待钉死
charge shop has no goods charge_id=%d
charge shop goods %d price_type=%d not supported
charge shop goods %d needs gold=%d, have %d
charge shop goods %d (%s): bag full
charge shop bought %s x%d (charge_id=%d) for %d gold, left=%d
ignored charge shop present (cmd 2) / favorite (cmd 4) / favorite remove (cmd 5) / command %d
charge shop buy has no bag
binglu modify: missing sub message                          ← 943 布局：sub(int)+name(str)+num(int)
binglu modify: sub message is not an integer
binglu modify unhandled sub=%d name=%q num=%d
binglu level up row=%d(%s) level=%d need=%d                 ← 943 升级行为（数据表未接）
luandou 784: missing sub_msg args=%d                        ← 784 布局：sub(int)
luandou 784: bad sub_msg type %s
luandou 784 sub=10 OPEN_LIMITE index=%d origin_id=%d
luandou 784 sub=%d ignored (not PlayerIsInCross)
%s: tiguan 865 bad sub_msg %s                               ← 865 子命令校验
tiguan roulette 868 missing action                          ← 868 布局：action(str)
tiguan roulette 868 bad action %s                           ← 合法词表待反汇编/抓包
%s: task 209 ignored: no active task                        ← 209 无活跃任务
%s: task 209 ignored: no argument matches active tasks %v
twilight item skill %s has no active scene                  ← 270 无暮色场景
%s: closed DropBox via 382                                  ← 382 关闭掉落箱
%s: S2C clone col result route=%d level=%d score=%d use=%ds try_left=%d; awaiting C2S 144
Yufeng charge window expired after %s without C2S 249 (player never charged: no movement); cancelled, NOT completed player=%d-%d
card C2S: unknown sub_command=%d args=[%s]                  ← 180 子命令路由
card C2S: equipped card_id=%d / unequipped all cards / invalid card_id=%q sub=USE
card USESKILL empty skill id values=%v
%s: GM custom probe msgID=%d subType=%d                     ← 517 GM 探针
%s: handle tersure 688: %v                                  ← 新钉死编号（后续轮候补）
VIP message 301 command is not an exact integer: %s         ← 301 VIP
%s: handle life job 864: %v                                 ← 864 生活职业
jingmai custom requires an int subcommand                   ← jingmai 子命令
%s: handle C-panel BodyScale 770: %v                        ← 770 体型
%s: attire blend 692 missing args args=[%s]                 ← 692 面料混合
origin 700: missing origin id args=%d                       ← origin 700~706 家族
%s: reject 145 no clone awards view / reject 145 bad index %d   ← 145 副本奖励拾取
%s: reject 380 pickup without vpos                          ← 380 掉落拾取
```
