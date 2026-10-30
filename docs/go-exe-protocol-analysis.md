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
| 0x09 | ClientReady | `ignored duplicate ClientReady in active scene`；现版**严格 length==1**（84B 触发 `invalid session message` 断连，见 §3.14） | ✅ `LoginFlow.onClientReady` |
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
| 0x11~0x14 | ServerRecord*（增删行/网格/清空） | `serverRecordAddCellRows/DelRow/Clear/CooldownRow/...` 十余变体；踢馆计时 `0x11 target=2 Time_Limit_Form_Rec` | ✅ 0x11/0x12；**0x13 单元格更新格式第二十三轮钉死（邮件已读/领件用，见 §3.23）**；🟨 0x14 暂无使用场景 |
| 0x15~0x19 | ServerView*（AOI 视图） | `serverAllDest`、`encodePresenceWaypointSelf`、`scene viewport delta adds=%d removes=%d` | ✅ 0x18 ViewAdd / 0x19 ViewRemove（`InventoryPackets`） |
| 0x1F/0x20 | ServerLocation/Moving | `serverLocation`（`replayed ServerLocation for %d scene objects`）、`serverMoving` | ✅ |
| 0x1E/0x27（S2C 方向） | 定向 custom 帧 | `write qinggong 0x1E-02 idx=%d`、`0x1E-04 idx=205`、`0x1E-06 idx=20`、`0x1E-07 idx=337`（轻功走 0x1E 子命令；**第二十二轮勘误：此处 "0x1E-04" 指 S2C msgId=4 的轻功/UI 帧，与 ready 尾 msgId 414 的 'reset' 子命令是两回事，完整 S2C msgId 语义见 §3.22**） | ✅ `CustomPackets`/轻功槽位 |
| custom 通用 | `serverCustomIntMessage/StringMessage` + Modern/WithOpcode 变体 | `encodeServerCustomValuesWithOpcode` | ✅ `CustomPackets` |

## 2. CustomSend msgId 子协议（0x0A/0x1E/0x27 载荷）

客户端把功能消息塞进 CustomSend 的 `msgId`（GM 探针面板证实范围 **[1,764]**，踢馆 865 走直发路由）。
exe 为每个功能实现一个 `handleXXX`（共 75 个，全量清单见 §2.2）。

### 2.1 编号与参数布局已钉死的消息（可直接对齐实现）

证据为 exe 中的参数校验串 `expects TVarList(...)` / `handle ... NNN`：
（**第七轮起 msgId→handler 权威映射见 §3.9 完整表**，本表只列布局/行为已细化的条目）

| msgId | 功能 | 参数布局 | exe 行为要点 | Java 现状 |
|---|---|---|---|---|
| 1 | 聊天 | `[1, channel(int), chatType(int), text(str), ...]`（按 echo 串反推） | 反汇编：1 → handleChatCustom；`reject chat before player spawn`、`ignore empty chat (channel=%d)`、`chat text is empty`；S2C echo：`chat echo object=%d channel=%d chatType=%d text=%q`（帧格式待钉死） | 🟨 第十二轮：channel/text 校验；echo 待抓包 |
| 2/517 | GM 探针 | `[subType]` | 反汇编：2 → handleGMCustom（517 为旧误传非 Go case）；`GM custom probe msgID=%d subType=%d` | 🟨 第七轮：日志；**第十四轮：布局 `[2, cmd(int), text(str)]`，缺 text 报 `command text is empty`** |
| 19 | 时装套装 FashionSuit | sub（str→invalid / int→unhandled） | `fashion_suit has no sub-message` 等三分支；**第十轮修正：链（Yufeng/GiftBox/EquipItemUse/WuJiBook）各 handler 自检 msgId==31，服务的是 31 不是 19；19 由 FashionSuit 独占（仅查 argc≥2）** | 🟨 校验层（wear/buy 子路由待抓包） |
| 34/71 | 道具动作 ItemAction | — | 反汇编：34/71 → handleItemActionCustom（**71 旧分析误标"商店关闭"**）；`item action %d has invalid view/slot` | 🟨 第六轮：日志（动作子表未接） |
| 30 | 移动物品 | `[fromView, fromSlot, toView, toSlot]` | 卸下 `[1,槽,121,0]`；0x18/0x19 View 帧同步 | ✅（含穿/脱武器后 WeaponMode=1597 模型推送） |
| 61 | 显示装备类型 showequip_type | `[type:int]` | `showequip_type set to %d`；非法值 `invalid value %d`；影响装备外观显示（gear visual 帧） | 🟨 本轮：校验+存储+日志（外观帧待钉死） |
| 64/70 | 商店 打开/购买 | buy=**`[70, shopId(str), page(0基), pos(1基), amount]`（5 值，第十九轮实机钉死）** | 完整商店系统；`shop buy needs 5 values (msg, shopid, page, pos, amount)` → `buy page=%d pos=%d must be positive` → `amount=%d outside 1..%d` → `has no listing` → `stock=%d < requested=%d` → `unsupported capital type %d` → `needs silver=%d, have %d` → `bought %s x%d for %d (capital %d)`（成功购买无日志；71 经反汇编证实是 ItemAction 非商店关闭） | ✅ 第十一轮：70 购买全链；**第十九轮：5 值布局+开店/购买响应帧序（见 §3.19）**；64 打开走 NPC 菜单链 |
| 144 | 副本结算确认 | — | `awaiting C2S 144`（col result 确认） | 🟨 第三轮：确认收下记日志（Java 无待确认 col result） |
| 145 | 副本奖励 View80 拾取 | — | `reject 145 no clone awards view` / `reject 145 bad index %s`、`pickup clone award slot=%d` | 🟨 第四轮：无 View80 奖励视图拒绝 |
| 150/161 | 经脉 JingMai | `[subcommand(int), ...]` | 反汇编：150/161 → handleJingMaiCustom；`jingmai custom requires an int subcommand`、`jingmai toggle active=%v name=%q role=%d` | 🟨 第六轮：subcommand 校验（经脉表未接） |
| 180 | 卡片（activity card） | — | `handle activity card 180` | 🟨 第三轮：sub_command 路由桩（USE/USESKILL/SAVE 子编号待钉死） |
| 195 | 快捷键方案保存 | `[sub(int)]` | 反汇编：195 → handleSaveShortcutScheme；`save shortcut scheme sub=%d len=%d` / `unknown sub=%d` | 🟨 第六轮：sub 校验（方案存储未接）；**第二十二轮：ready 尾 S2C 默认方案 ×2 已对齐（§3.22）** |
| 200/201 | 快捷键 设置/删除 | `[idx, kind(str), id(str)]` / `[idx]` | `shortcut expects int index and two strings`；同 index 先 0x12 删旧行 | ✅ |
| 209 | 任务放弃 | — | `handle task 209: %v`、`task 209 ignored: no active task`；**反汇编互证：209 → handleTaskGiveUpCustom（`task panel gave up task=%d`）** | 🟨 第三轮：无活跃任务忽略（日志对齐） |
| 211 | 技能释放 | `[skillId(str)]` | `reject skill before player spawn id=%s`、`reject unlearned installed skill id=%s` | ✅（学习表+编译目录+计费/CD/动作/区域结算，见 §3.4） |
| 215 | 修炼进度（含内功子命令） | `[configId(str)]`（内功路径） | 反汇编：215 → handlePlayerProgressCustom；`player progress request before scene player exists`、`215 requires a learned neigong ConfigID string`、`handle inner-power msg=%d` | 🟨 第六轮：进度/内功表未接，双拒绝分支 |
| 216/217 | 轻功 基本/主动 | — | `reject basic qinggong hold cost=%d point=%d/%d`；ActiveQGSkillRec 行 | ✅ |
| 218 | 主动招架 | — | `manual parry before scene player exists`、`publish manual parry state`（**第七轮修正：54=ManualParry，218=ActiveParry**） | ✅ |
| 223 / 5223 / 99 | 操作模式切换 | `[mode:int]` | `set operate mode=%d (0=3D 1=2.5D), shortcutKey updated`；**反汇编修正：Go switch 的 case 值是 99，223/5223 在 exe 全 .text 无比较点**（223/5223 为实机抓包有效编号，三者并存接入） | 🟨（见 §3 疑似偏差） |
| 240 | 打坐 | `[start_or_stop:int]` | `sitcross native state %d -> %d via normal 0x10`（1=坐下 0/2=起身） | ✅ |
| 270 | 暮色道具技能 | `[skillId(str)]` | `twilight item skill %s has no active scene` | 🟨 第三轮：无活跃暮色场景拒绝（日志对齐） |
| 291 | PK 模式切换 | `[mode:int]` | `change pk mode expects TVarList(291, mode)`；`change pk mode invalid mode %s`；GM 面板 `set_pk_mode` 同源 | 🟨 本轮实现 |
| 292 | 警告开关 | 无参 | `warning expects TVarList(292) with no extra values`；`change warning %d -> %d via normal 0x10`（toggle 后 0x10 推送，属性 ID 未钉死） | 🟨 本轮实现（存储+日志） |
| 293 | PK 保护开关 | `[type:int, onoff:int]` | `toggle expects TVarList(293, type, onoff)`；`client pk protect toggle type=%d on=%d` | 🟨 本轮实现 |
| 301 | VIP | `[301, cmd(int), ...]` | `VIP message 301 has no command` / `command is not an exact integer: %s`；购买链 `month count %d is outside 1..36`、`gold balance %d is below VIP cost %d`、`completed golden VIP purchase type=%d months=%d cost=%d gold=%d ...` | 🟨 第四轮：头部校验；价格表与购买 cmd 编号待钉死 |
| 326/388 | 组队（含伙伴） | `[op(int), ...]` | 反汇编：326/388 → handleTeamCustomWithCompanion；`reject team op %d before player spawn`、`team: operation %d requires %d values`、`%s joined team %d via captain %d` | 🟨 第六轮：op 校验（队伍系统未接） |
| 353/359/361 | 邮件 | — | 反汇编：353/359/361 → handleMailCustom；`mail selection has invalid fields`、MailDelete/MailSelection；**第二十三轮：家族实为 8 编号 350/353/355/356/357/359/361/365，全链实机钉死并落地（见 §3.23）** | ✅ 第二十三轮：邮箱/已读/领件/删除全链 |
| 380 | 掉落拾取 | `[vpos(int), ...]`（首参假设） | `reject 380 pickup without vpos` / `reject 380 bad vpos %s`、`pickup 380 vpos=%d %s x%d -> %s bag[%v]` | 🟨 第四轮：vpos 校验；无掉落实体 |
| 382 | 关闭掉落 | — | `handle close drop 382`、`closed DropBox via 382` | 🟨 第三轮：日志对齐（Java 无 DropBox 实体） |
| 560 | 充值商店购买 | `[560, cmd, ...]`（buy=`[560,3,charge_id,nums]`） | `buy expects [560,3,charge_id,nums]`、`nums=%d outside 1..%d`、`no goods charge_id=%d`、`price_type=%d not supported`、`needs gold=%d, have %d`、`(%s): bag full`、`bought %s x%d (charge_id=%d) for %d gold, left=%d`；cmd 2/4/5 忽略 | 🟨 第三轮：货品表+完整校验链+金币扣减+入包 grant（页面 S2C 与 capital 映射待钉死） |
| 249 | 充值确认 | — | `Yufeng charge window expired … without C2S 249 (player never charged: no movement); cancelled, NOT complete` | 🟨 第三轮：御风蓄力确认收下记日志（Java 无蓄力窗口） |
| 688 | 珍宝阁 tersure | 全 int `[sub_type, ...]` | `tersure 688 has non-int args: %v` / `unhandled tersure 688 sub_type=%d`；重铸/修复/洗练结果帧 | 🟨 第四轮：sub_type 路由校验（结果帧待钉死） |
| 692 | 面料混合 | — | `attire blend 692 missing args args=[%s]`、`attire: restored blend items=%d show_type=%d` | 🟨 第四轮：缺参校验（attire store 未实装） |
| 700~706 | 出身/起源 origin | `[origin_id(int)]` | 反汇编归属：**700=Active / 701=Get / 702=GetPrize / 705=SetShow / 706=RequireNotify**；`origin 700: missing origin id args=%d` / `bad origin id type %s`；705 送称号+声望（`repute %s +%d -> %d`） | 🟨 第四轮：id 校验（origin 目录未接；归属第六轮反汇编修正） |
| 713 | 礼盒领取 | — | `gift box catalog unavailable: %v` / `gift box %s claimed prizes=%d` | 🟨 第四轮：目录不可用分支 |
| 770 | C 面板体型 BodyScale | — | `handle C-panel BodyScale 770`、`customizing upload too large: %d` | 🟨 第四轮：日志（customizing 上传/恢复未实装） |
| 784 | 乱斗 | `[sub(int)=10 OPEN_LIMITE index, origin_id]` | `luandou 784: encode reply`、`missing sub_msg args=%d`、`bad sub_msg type %s`、`sub=10 OPEN_LIMITE index=%d origin_id=%d` | 🟨 第三轮：sub 路由（reply 格式待钉死） |
| 864 | 生活职业 | `[jobName(str)]`（假设首参） | `unknown life job %q` / `life job %s already learned; republish job_rec`、`apprenticed life job=%s level=%d points=%d` | 🟨 第四轮：未知职业拒绝（职业表与 job_rec 未接） |
| 865 | 踢馆 | sub 6=确认切换 / 7=离场 | `CUSTOM_MSG_TG_SWITCH`（见 audit/tiguan 文档完整线序）、`tiguan 865 bad sub_msg %s` | 🟨 第三轮：sub 6/7 校验+日志（分组场景生命周期待实装） |
| 868 | 踢馆轮盘 | `[action(str)]` | `tiguan roulette 868 bad action %s`、`missing action`；**第七轮：868 非 custom case（Go 在 865 handler 内子路由）** | 🟨 第三轮：action 校验+日志（合法词表待钉死） |
| 924 | 消息延迟 | `[924, tick(i64)]` | `message delay 924: expected [int32:924, int64:tick]` → `message delay 924 response` | 🟨 第三轮：布局钉死+回显 tick；**第十四轮：现版 exe 实际不处理 924**（0x1E unhandled / 0x0A 解析后静默），Java 保留为超集 |
| 943 | 兵录修改 | `[943, sub(int), name(str), num(int)]` | `binglu modify: missing sub message`、`sub message is not an integer`、`unhandled sub=%d name=%q num=%d` | 🟨 第三轮：sub 三分支校验日志（binglu 数据表未接） |
| 961 | 骑乘技能 | `[skillId(str)]`（假设） | 反汇编：961 → handleRideSkillUse；`ride skill (not dismount) id=%s mounted=%v` | 🟨 第六轮：日志（坐骑状态未接） |
| 819 | 用户须知签署 | — | （Java 侧实测闭环；**第七轮：819 非 custom case，Go 在登录/场景流处理**） | ✅ |
| ~~517~~ | GM 探针（旧误传编号） | `[subType]` | `GM custom probe msgID=%d subType=%d`；**第七轮：GM 探针真身是 2（handleGMCustom），517 非 Go case** | 🟨 第七轮：2/517 并存日志 |
| 886~889 | 一键穿 OnestepEquip | `[uid(str)]` | 反汇编区间叶四连号；`onestep equip uid=%s` / `not found in bags` / `wear now requires one wearable equipment item` | ✅ 第九轮：uid 反查+槽位映射（官方 equip.json 存档实测：Hat=1/Cloth=3/Pants=4/Wrist=6/Leg=7/Shoes=8/Earring=11,12/Necklace=13/Fingerring=14,15/Weapon=22，双槽取空槽）+穿装帧序/持久化/词缀加成（与 msgId=30 同构） |
| 938 | 武器重铸总入口 | — | 反汇编：938 → handleWeaponRecastCustom；`recast preview reject: need 5 args, got %d`、`recast lock reject %s has no rand pool`（Preview/Lock/Unlock/Sure 为子命令） | 🟨 第七轮：日志（重铸资源未接） |

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

装备系统仍缺（对照 exe）：~~装备属性加成（equipStrAdd/equipPhyDef 等未接
CombatStats）~~（**第五轮已闭合**，见 §3.7）、
~~右键使用穿戴（EquipItemUse，编号仍未定位）~~（**第八轮定位：无独立编号，是
msgId 19 使用物品链的成员**，见 §3.10）/一键穿（~~编号未钉死~~ **第七轮钉死
886~889**，见 §3.9）、时装外观刷新（FashionCoat=1352 系）、重铸 WeaponRecast
全套（~~编号未钉死~~ **第七轮钉死总入口 938**，Preview/Lock/Unlock/Sure 为其子命令）。

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
  ~~`learned skill count %d exceeds u16 view capacity` 的全量 View 推送~~
  （**第十六轮已实现整推**，见 §3.16）。

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

### 3.6 custom 子协议轮（2026-10-30 第四轮）

把 §3.5 候补清单里编号已钉死的消息全部接入 `dispatchCustom`（12 个新 case 组：
215/19/301/688/692/700+701+702+705+706/713/770/864/145/380），实现为 C2S 校验层 +
Go 同构日志；**default 分支日志对齐**为参考服的
`unhandled custom msg_id=%d args=[%s]`（保留首现 INFO/重复 DEBUG 的防刷屏策略）。

- **本轮新钉死编号**：215 内功切换（`215 requires a learned neigong ConfigID
  string`）、713 礼盒领取（`handle gift box claim 713`）、19 时装套装、692 面料混合、
  145 副本奖励 View80 拾取、380 掉落拾取。
- **各 handler 的 Go 证据串**见 §2.1 表与 §5.2；共同形态：参数缺失/类型不符的拒绝
  分支文本与 exe 完全同构，数据系统（内功表/时装 store/珍宝阁结果帧/VIP 价格表/
  origin 目录/礼盒目录/生活职业表/View80 奖励/掉落实体）未实装的分支带
  pending 标注，不伪造 S2C 帧。
- **jingmai 的 msgId 仍未钉死**（`jingmai msg=%d` 是变量日志，字符串层不可见；
  GM 侧动作名为 `set_jingmai_tier`）；下一批可挖：`wear now requires one wearable
  equipment item`（OnestepEquip）与 `right-click equip %s reject`（EquipItemUse）
  的行为串已找到，但编号未钉死，需反汇编或 GM 探针。

### 3.7 装备词缀加成轮（2026-10-30 第五轮）

闭合 §3.3 记录的装备缺口第一块：**装备属性加成接入 CombatStats**
（`equipStrAdd/equipPhyDef 等未接` → 已接）。

- **数据链**：`share/modifypack/proppack.ini`（GBK，34,711 个有效词缀包节；每节
  `r=,PropName,Value,Flag` 一到多行，Flag 0/1 语义未钉死、累积时忽略）→ 新建
  `com.nineyin.game.PropPackCatalog`（装载日志对齐 `proppack catalog ready packs=%d
  file=%s` / `proppack.ini unavailable (%v); equip PropModify bonuses disabled`）。
  装备的 `propBuilt` 串（"1,id;1,id"，id 即节号，来自 DB 词缀 JSON 的
  `[{pack_rec,prop_built}]` 转换）逐件解析引用词缀包。
- **累积层**（`PlayerActor.equip*Add`，字段名与 Go 累积器同构：equipMaxHPAdd/
  equipDexAdd/equipPhyDef/equipMeleePowerAdd/equipMinMelee/equipMaxMelee…）：
  `recomputeEquipBonuses(catalog, liveEquipped)` 先清零再逐件累积，幂等可反复
  调用（对齐 Go `recomputeCombat`）。
- **应用面**（只取已有文档化换算，未发明公式）：
  PhyVa/MeleePower→攻击、PhyDef→防御、Dex(身法)1:1→命中 + PhyHit→命中、
  Dodge→闪避、Str(臂力)1:1→暴击值、MaxHP/MaxMP→有效上限 `maxHp()/maxMp()`
  （vitalUpdate/tick 恢复/revive/瘀血上限全部改走有效值）。
  五维其余（Ing/Spi/Sta）、MagicHit/MagicVa、内功防御四系、概率%/倍率词缀
  累积但不应用（无对应字段），待内功/经脉系统接入后扩展。
- **接线点**：SceneFlow 构造器装载目录；`sendSceneNpcs`（ClientReady 钩子，
  库存已下发）进场景重算；msgId 30 穿/脱触及装备栏后重算，有效上限变化时
  0x10 推 MaxHP(577)/MaxMP(578) 增量 + vital 同步并打加成摘要日志。
- **装备系统仍缺**（§3.3 剩余）：右键使用穿戴（EquipItemUse）/一键穿
  （OnestepEquip）编号未钉死、时装外观刷新（FashionCoat=1352 系）、
  重铸 WeaponRecast 全套（`recast preview reject: need 5 args` 等串已找到，
  编号未钉死）。

### 3.8 反汇编钉死 msgId 轮（2026-10-30 第六轮）

**突破**：COFF 符号表 + objdump 反汇编成功打开 msgId→handler 映射的提取通道
（§4 已沉淀可复用命令）。custom 分发是 `main.handle` 内 %ecx 上的**二分搜索树**，
出现两次（对应 0x0A 信封与 0x1E/0x27 直发两条路径，与 Java dispatchCustom 同构）。
提取方法：跟随 `cmp $imm,%ecx` + `je <块>`（相等跳转）到块内第一个
`call <main.handleXxx>`；`jl/jg` 是子树边界不作数；区间树末端 case 用 `jne` 落入式。

**本轮钉死并互证**（je-相等 + 字符串证据双向验证）：

| msgId | handler | 互证 |
|---|---|---|
| 1 | Chat | `reject chat before player spawn` |
| 34/71 | ItemAction | `item action %d has invalid view/slot`；**71 旧分析误标"商店关闭"** |
| 99 | SetOperateMode | **223/5223 在全 .text 无比较点**（223 是实机抓包有效值，Go 侧 case 实为 99） |
| 150/161 | JingMai | `jingmai custom requires an int subcommand` |
| 195 | SaveShortcutScheme | `save shortcut scheme sub=%d len=%d` |
| 209 | TaskGiveUp | `handle task 209` + `task panel gave up task=%d` |
| 215 | PlayerProgress | `player progress request before scene player exists`；内功子命令（`215 requires a learned neigong ConfigID string`、`handle inner-power msg=%d`）同属此 handler——修正第四轮"215=纯内功切换"推断 |
| 217 | ActiveQingGong | 既有知识 |
| 249 | YufengHoldPowerEnd | `Yufeng charge window ... without C2S 249` |
| 326/388 | Team(含伙伴) | `team: operation %d requires %d values` |
| 353/359/361 | Mail | `mail selection has invalid fields` |
| 700/702/706 | OriginActive/GetPrize/RequireNotify | `origin 700/702/705: missing origin id`（**修正第四轮 700=Get 推断**；701=Get、705=SetShow 由邻位推断） |
| 961 | RideSkillUse | `ride skill (not dismount) id=%s mounted=%v` |

Java 侧：99 并入操作模式 case（223/5223/99 三编号并存）；新增 1/34/71/150/161/
195/326/388/353/359/361/961 十二个校验层 handler；215 重写为 PlayerProgress
双拒绝分支（出生前 + 内功未学习）。

**待续**（区间树解码器剩余）：OnestepEquip/EquipItemUse/ShopBuy/Skill(211)/
Shortcut(200)/Warning(292)/924/868 等的守卫比较因落入式 case / 参数装配段较长
未读出——case 块经 `jl/jg` 子树进入且与树指令交错，需要完整的区间递归解码器；
反汇编产物已存 `%TEMP%/text.asm`（1.8M 行）与 `/tmp/syms.txt` 可继续。

### 3.9 区间树解码拉全 switch 表（2026-10-30 第七轮）

修复第六轮解码器（objdump 跳转目标带 `<main.handle+0x…>` 注释，正则不可用行尾
锚点），三种提取法并集出**完整 custom switch 表**：

1. **je-相等对扫描**（全 main.handle 区间）：`cmp $imm,%ecx` + `je/jne` 的相等
   分支 → 块内首个 handler 调用，52 个 handler；
2. **区间树递归**（根 0x87dd08 / 0x8836a0，区间 [1,999]）：处理 `jl/jg` 子树与
   **区间叶**（无相等比较、靠 `cmp $lo; jl away` 收敛的 `case A, B:` 连号块，
   如 200/201、886~889）；
3. **全 .text 常量扫描**：证伪非 case 编号。

**完整表**（🎉 = 本轮新钉死；✓ = 与既有字符串/抓包知识互证）：

| msgId | handler | | msgId | handler |
|---|---|---|---|---|
| 1 | Chat ✓ | | 249 | YufengHoldPowerEnd ✓ |
| 2 | GMCustom 🎉（GM 探针真身；517 是旧误传，全 .text 零比较点） | | 266 | NewRide 🎉 |
| 15 | signalAdvancedQingGongEntry 🎉 | | 270 | TwilightItemSkill ✓ |
| 19 | FashionSuit ✓ | | 291/292/293 | PKMode/Warning/PKProtect ✓ |
| 30 | MoveItem ✓ | | 295 | SetTitle 🎉 |
| 31 | 使用物品链（BodyItemUse+右键穿）🎉 | | 301 | VIP ✓ |
| 34/71 | ItemAction 🎉（71 ≠ 商店关闭） | | 326/388/442~445 | Team（6 编号）🎉 |
| 54 | ManualParry 🎉（218=ActiveParry 互证） | | 350/353/355/356/357/359/361/365 | Mail（8 编号，第二十三轮全链）🎉 |
| 61 | ShowEquipType ✓ | | 380 | PickupDrop ✓ |
| 70 | ShopBuy ✓（64 打开未在 custom switch 出现） | | 382 | CloseDrop ✓ |
| 84/112/144/145 | Clone（进入/列表/结算确认/奖励拾取）🎉 | | 560 | ChargeShop ✓ |
| 99 | SetOperateMode ✓（223/5223 零比较点） | | 688 | Tersure ✓ |
| 107 | SaveCustomizing（捏脸）🎉 | | 692 | AttireEquipBlend ✓ |
| 150/161/172 | JingMai（3 编号）🎉 | | 700~706 | Origin：700=Active/701=SetShow/702=GetPrize/705=Get/706=RequireNotify ✓ |
| 180 | Card ✓ | | 713 | GiftBoxClaim ✓ |
| 187 | AttributeMall 🎉 | | 770 | BodyScale ✓ |
| 195 | SaveShortcutScheme ✓ | | 784 | LuanDou ✓ |
| 200/201 | Shortcut（区间叶：`case 200, 201:`）✓ | | 864 | LifeJob ✓ |
| 209 | TaskGiveUp ✓ | | 865 | Tiguan ✓ |
| 211 | Skill ✓ | | 886~889 | OnestepEquip（区间叶四连号）🎉 |
| 215 | PlayerProgress ✓ | | 924 | MessageDelay ✓ |
| 216/217/218 | QingGong/ActiveQG/ActiveParry ✓ | | 938 | WeaponRecast（总入口；Preview/Lock/Unlock/Sure 为子命令）🎉 |
| 240 | Sitcross ✓ | | 943 | Binglu ✓ |
| 243 | Ride 🎉 | | 961 | RideSkillUse ✓ |

**不在 custom switch 的编号**（全 .text 零比较点）：819（条款签署——Go 在登录/
场景流处理，Java 实测编号保留）、868（踢馆轮盘——865 handler 内子路由）、
517（旧误传）、223/5223（操作模式——Go case 为 99）。

**仍未定位**：EquipItemUse（右键穿）、UseItem（道具使用）、CardSkillCast、
QingGong 细分（BasicQingGongStart/End/Landing/GJLandingDetect 等，可能挂 216
子命令）、WeaponRecastPreview/Lock/Unlock/Sure（938 子命令）、64（商店打开）——
这些 handler 在符号表且有两处调用点，但守卫在长参数装配段之后，需要按调用点
逐个回溯块首。

Java 侧：`dispatchCustom` 新增 2/15/31/54/70/84/112/107/145/187/243/266/295/
442~445/886~889/938 十八个 case（54 手动招架翻 `actor.parrying`；145 并入
Clone 族保留精确拒绝文本；70 升级带 stock 校验串引用）；517/868/819 的注释
按"非 Go case"事实修正（case 保留，客户端实测会发）。

### 3.10 handler 链追踪：使用物品链（2026-10-30 第八轮；归属第十轮修正为 31）

第七轮"仍未定位"的谜底：**EquipItemUse 等没有独立 case**。反汇编链追踪发现
Go 把一批"使用类" handler 链在同一个 case 块里，每个返回 bool，未认领落到
下一个（编译形态：`call X; test %al,%al; jne <下一 call 之后>`）：

```
case 19: FashionSuitCustom → YufengItemUse → GiftBoxItemUse
              → EquipItemUseCustom（右键穿）→ WuJiBookUse → …（链尾）
```

- 提取方法：对区域内每对相邻 handler 调用 (X, Y)，若 X 与 Y 之间存在
  `jne T` 且 `T == Y 的 call 指令的下一条地址`，则 X→Y 是链边；并查集取链头。
- **意义**：右键使用穿戴（EquipItemUse，§3.3 装备缺口项）没有自己的 msgId——
  客户端发 19（广义"使用"），服务器按物品类型链式认领。Java 侧 19 的 handler
  已重写为链形（头部 FashionSuit 三分支校验 + 链尾 unclaimed 日志）。
- **仍未定位**（链外孤岛，守卫在更深处的装配段）：UseItemCustom、CardSkillCast、
  BasicQingGong 三段/BigQingGongRelease/GJLandingDetect（疑挂 216 子命令）、
  WeaponRecast 四子命令（疑 938 内部）、MountSummon、NinJiuZhi、ItemBuffUse、
  FightwordDebug、64（商店打开）。

### 3.11 一键穿功能实现轮（2026-10-30 第九轮）

校验桩升级为功能实现的第一例：**msgId 886~889 一键穿全链落地**（uid 认领 →
槽位映射 → 穿装帧序/持久化/词缀加成）。

- **槽位映射的数据来源**：`/e/Workspace/九阴服务端/data/equip.json`（官服存档
  导出，7 角色 33 件已穿装备）聚合 slot↔EquipType 实测对：
  Hat=1 / Cloth=3 / Pants=4 / Wrist=6 / Leg=7 / Shoes=8 / Earring=11,12 /
  Necklace=13 / Fingerring=14,15 / Weapon=22。双槽类型穿第一个空槽，全占用
  替换第一槽；未收录类型（Mask/暗器/内功武器等）按不可穿拒绝
  （`wear now requires one wearable equipment item`），留待存档样本补齐。
- **uid 认领**：参考服 `onestep equip uid=%s`——uid 就是服务器在 ViewAdd 帧下发的
  物品唯一串；Java 的 uid 是 `MD5(roleId:bag:slot)`（`InventoryPackets.uniqueId`），
  客户端回传后按 liveBagged 反查，找不到打 `not found in bags`（与 Go 同构）。
- **穿装路径**：与 msgId=30 穿上分支帧序同构（背包清格+镜像 → 旧装备让位到
  装备页 → 0x18 纸娃娃 ViewAdd → WeaponMode 模型帧 → §3.7 词缀加成重算 +
  MaxHP/MaxMP 增量 → inventoryFrames 缓存重建 → moveBagToEquip/swapEquipSlot
  持久化）。MySQL 后端全程生效；JSON 后端拒绝在"无库存"分支。
- **测试**：槽位映射单测（含双槽/全占用替换/未收录拒绝）+ env-gated 全链测试
  （NINEYIN_SERVER_ROOT 指真实表时：假 uid 拒绝 + helmet_sell_10101 穿上槽 1
  + 0x18 纸娃娃帧断言），已实跑验证。

### 3.12 右键穿与使用链归属修正轮（2026-10-30 第十轮）

**归属修正**：反汇编 handler 头部发现 YufengItemUse/GiftBoxItemUse/EquipItemUse/
WuJiBookUse 四个链成员**各自自检 `msgId==31`**（`cmpl $0x1f,0x4(%r8)`），BodyItemUse
不自检——第八轮的"19 = 使用链"结论修正为：**链服务 msgId 31**（多 msgId 共享
case 块 + handler 自检认领的形态）；19 由 FashionSuit 独占（仅查 argc≥2）。

- **参数布局**（`cmp $0x4,%r9` 自检）：`[31, view(int), slot(int), ...]` 至少 4 值；
  EquipItemUse 右键穿用 view+slot 定位背包物品（`lootBagForView`），槽位映射来自
  Go `inventory.SlotsForEquipType`（与第九轮官方存档实测映射互证）。
- **Java 落地**：穿装机械抽成共享核心 `wearBagItem`（一键穿 886 与右键穿 31 共用，
  帧序/持久化/词缀加成同构）；31 的 handler 重写为使用链形态——参数校验 →
  view+slot 反查 → 可穿校验（`right-click equip %s reject: not wearable`）→
  **首穿绑定**（`bound (first wear)`，会话级 `boundUids`，持久化待接）→ 穿上。
- **测试**：env-gated 全链（参数不足拒绝 / 121 页 slot 3 右键穿上槽 1 / 0x18 帧 /
  绑定只记一次），实跑日志与 Go 同构；151 全绿。


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

### 4.1 反汇编提取 msgId→handler 映射（第六轮打通，无需 IDA）

本机 `objdump`（D:/User/worktools/perl/c/bin）+ COFF 符号表即可；注意 exe 路径含中文时
objdump 读不到，先 cd 进目录；符号值是节相对 RVA，**代码 VMA = 0x401000 + 符号值**：

```bash
cd /e/Workspace/九阴服务端
# 1) 符号表（~3000 个 main.*）：
objdump -t 9yin-game-native-menu.exe | grep -oE "0x[0-9a-f]{16} main\.[A-Za-z0-9_.]+" | sort
#    → main.handle = 0x46acc0 → VMA 0x86bcc0（巨型函数，含全部 C2S 处理与 custom 分发）
# 2) 全量反汇编（一次性，1.8M 行，可复用）：
objdump -d --start-address=0x401000 --stop-address=0xa5b3f5 9yin-game-native-menu.exe > /tmp/text.asm
# 3) 找某 handler 的调用点：
grep -n "call.*<main.handleXxxCustom>" /tmp/text.asm
# 4) custom 分发 = main.handle 内 %ecx 上的二分搜索树（出现两次：0x0A 信封 + 0x1E/0x27
#    直发）。读守卫：跟随 cmp $imm,%ecx + je <块> 到块内第一个 call <main.handleXxx>；
#    jl/jg 是子树边界不作数；区间末端 case 用 jne 落入式；default 分支的格式串
#    ("%s: unhandled custom msg_id=%d args=[%s]" @ rdata VMA 0xb84dcf) 可作树定位锚点。
# 5) 字符串地址反查：文件偏移 → .rdata VMA = 0xa5c000 + (off - 0x65aa00)，
#    再在 text.asm 里搜 "# <vma>"（objdump 注释无 0x 前缀）。
```

局限性：落入式 case（jne 落入）与长参数装配段需要完整的区间递归解码器（见 §3.8 待续）；
0x10 属性 ID 常量同法可挖（0x10 帧构造函数内的立即数）。IDA/Ghidra 或 GM 探针抓包
仍是最后手段；`H:/9yin-go-server2` 源码目录已不存在，若能找回源码则一切细节可直接闭合。

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

### 5.2 第四轮新增摘录（编号钉死批，2026-10-30）

```
215 requires a learned neigong ConfigID string              ← 215 内功切换
%s: handle inner-power msg=%d: %v                           ← inner-power 家族（215）
VIP message 301 has no command                              ← 301 VIP 头部校验
VIP message 301 command is not an exact integer: %s
VIP price query: unsupported type %s                        ← 价格查询（cmd 编号待钉死）
VIP purchase: invalid month count %s
VIP month count %d is outside 1..36
gold balance %d is below VIP cost %d
%s: completed golden VIP purchase type=%d months=%d cost=%d gold=%d valid_until=%d total_seconds=%d
VIP purchase arrived before player creation
%s: tersure 688 has non-int args: %v                        ← 688 珍宝阁（全 int 参数）
%s: unhandled tersure 688 sub_type=%d
send tersure recast/repair/lock refresh/restore taolu result: %w   ← 688 结果帧家族
%s: fashion_suit has no sub-message                         ← 19 时装套装
%s: fashion_suit invalid sub-message %s                     ← str 子消息 → invalid
%s: fashion_suit unhandled sub=%d args=[%s]                 ← int 子消息 → unhandled
%s: fashion_suit worn config=%s (list state) / unworn via prop=%s / buy config=%s added=%v
fashion: pushed FashionSuitRec rows=%d (activated %d, cleared %d old rows)
unknown life job %q                                         ← 864 生活职业
%s: life job %s already learned; republish job_rec
%s: apprenticed life job=%s level=%d points=%d
%s: handle C-panel BodyScale 770: %v                        ← 770 体型
customizing upload too large: %d                            ← SaveCustomizing 上传
%s: handle gift box claim 713: %v                           ← 713 礼盒领取
gift box catalog unavailable: %v / gift box %s claimed prizes=%d
origin 701: missing origin id args=%d / origin 702: … / origin 705: …
%s: origin prize %d: repute %s +%d -> %d (level %d)         ← 705 出身奖励（声望+称号）
origin: loaded %d origins from %s                           ← origin 目录装载
%s: handle pickup 380: %v                                   ← 380 拾取 handler 注册确认
wear now requires one wearable equipment item               ← OnestepEquip（编号未钉死）
%s: right-click equip %s reject: %v / bound (first wear)    ← EquipItemUse（编号未钉死）
%s: unhandled custom msg_id=%d args=[%s]                    ← default 分支（本轮对齐）
custom message %s has unsupported outer opcode %#x          ← 载体 opcode 校验
```

### 5.3 第六轮新增摘录（反汇编钉死批，2026-10-30）

```
%s: reject chat before player spawn                     ← 1 聊天
%s: ignore empty chat (channel=%d)
item action %d has invalid view/slot                    ← 34/71 道具动作
jingmai custom requires an int subcommand               ← 150/161 经脉（子命令 int）
%s: jingmai toggle active=%v name=%q role=%d
save shortcut scheme sub=%d len=%d / unknown sub=%d     ← 195 快捷键方案
%s: reject team op %d before player spawn               ← 326/388 组队
team: operation %d requires %d values
%s: %s accepted invite into team %d / joined team %d via captain %d
mail selection has invalid fields                       ← 353/359/361 邮件
%s: ride skill (not dismount) id=%s mounted=%v          ← 961 骑乘技能
player progress request before scene player exists      ← 215 修炼进度（出生校验）
%s: handle inner-power msg=%d: %v                       ← 215 内功子命令（同 handler）
set operate mode: invalid mode %d                       ← 99/223 操作模式（Go case=99）
%s: task panel gave up task=%d                          ← 209 = TaskGiveUp（反汇编互证）
%s: unhandled custom msg_id=%d args=[%s] @ rdata 0xb84dcf   ← custom 树定位锚点
```

### 5.4 第八轮新增摘录（handler 链追踪，2026-10-30）

```
call <main.handleXxx>; test %al,%al; jne <下一 call 之后>   ← 链式认领的编译形态
%s: right-click equip %s reject: %v                        ← 19 链 EquipItemUse 段
%s: right-click equip %s bound (first wear)                ← 右键穿首次绑定
%s: wuji book used config=%s wuji=%d new=%v role=%d        ← 19 链 WuJiBook 段
%s: gift box %s claimed prizes=%d                          ← 19 链 GiftBox 段
%s: yufeng item use blocked while mounted                  ← 19 链 Yufeng 段
```

### 3.13 商店购买功能实现轮（2026-10-30 第十一轮）

**msgId 70 商店购买全链落地**（NPC 商店从"开店视图"推进到"可购买"）。

- **列语义破译**（shop.ini 挂单行 ↔ MySQL game_shop_items 逐字段互证）：
  `0=ConfigID, 1=amount(库存/限量), 2=capital, 3=price, 5=page, 6=position,
  7=名称ID`；capital **2=碎银**（jhzb 案例价格 500000 互证）、3=兑换（price=0）。
  挂单 key 可重复（同 key 多行按序保留），页内 position 唯一定位。
- **参数布局**（证据串）：`[70, page, pos, amount]`，pos 1 基（视图 objectIndex
  = (page-1)*页大小 + position + 1 的页内位）。
- **链路**：参数校验 → 挂单查找（会话打开的商店快照，`SelectFlow.openShop` 写入
  `LoginSession.openShop`）→ 库存 → capital（2/3 走碎银，其余
  `unsupported capital type %d`）→ 碎银余额（`needs silver=%d, have %d`）→
  game_items 入包（解析失败不扣款）→ 扣碎银 + 0x10 CapitalType1 + ViewAdd 背包帧
  → `shop %s bought %s x%d for %d (capital %d)`。
- **测试**：fixture 商店覆盖全拒绝分支（参数/非正/越界/无挂单/库存/capital/
  无库存后端），断言零帧且碎银分文不动；合法购买在 JSON 后端停在 `no bag store`。
- **第十二轮补齐**：openShop 视图 ViewAdd 补第 4 属性——价格按 Go 自协商索引
  ~~**107=PriceGold / 108=PriceSilver / 109=PriceCard** 下发（挂单 capital 2→Silver、
  3→Card、其余→Gold）~~（**第二十轮勘误：价格槽 = 107+capital 直映射**，capital1→108、
  capital2→109，且三槽恒全量下发，见 §3.20）；
  **库存语义确认为静态挂单 amount**（Go 串层无 per-role
  消耗表/扣减落库痕迹，`stock=%d < requested=%d` 比对的就是挂单限量）。
- **待办**：~~购买成功后的 S2C 库存刷新帧（格式待抓包）~~——**第十九轮闭环：不存在该帧**
  （Go 购买成功只推背包行对 + 0x1E-05 物品提示，无商店视图刷新、无扣款 0x10，见 §3.19）。

### 3.14 模拟登录实机抓包轮（2026-10-30 第十三轮）

用 `tools/smoke/capture_go_server.py`（复用 smoke_client 帧编解码；`--fresh` 随机账号走建角链 /
`--probes` 追加探针 / `--seconds` 空闲推流窗口）对**在用 18.4MB exe**（19061）实跑全链路模拟登录，
38082 帧落盘 `tools/smoke/go_capture_20261030_140759.bin/.txt`（`.bin` 兼容
`decode_ref_capture.py` 离线解析）。

- **0x09 Ready 严格 1 字节**（本轮新钉死）：现版 exe 对会话消息做长度强校验，
  84B 旧格式（F 盘版要求 ≥84B，两端行为相反）触发
  `reject client message: invalid session message: client opcode 0x09 length 84 (want exactly 1)`
  并**立即断连**。Java 侧任意长度都收，如需对齐应同样拒绝非 1 字节。
  其余 C2S 长度均被接受：登录 105B（v2 多槽位）/世界信息 1B/建角 84B/选角 84B。
- **链路与帧量**（复登路径）：login 0x04 → world 0x05 → choose 21383 帧
  （0x09 属性表+0x0A+0x0B+109×0x0D+3444×0x11+17674×0x18 视图+152×0x1F）
  → ready 16402 帧（1681×0x11+14339×0x18+24×0x1E+…）→ 空闲窗口 6s 43 帧
  NPC 巡逻推送（0x1F/0x20 持续）→ ping/探针均有响应。choose 帧量与旧 F 盘版抓包
  （ref_capture 21385 帧）基本一致，同源互证。
- **服务端日志互证**：`C2S opcode=0x09 len=1`、`granted native sitcross shortcut
  index=9 kind=skill id=zs_default_01`、240 打坐 `sitcross native state 0 -> 102 ->
  0 via normal 0x10 (buffs applied=…)`、291 `client change pk mode -> 和平(0)
  changed=false`——探针全部按预期处理。
- **未解项**（**第十四轮全部闭环**，见 §3.15）：ready 尾部两个未收录 opcode
  **0xFB(len=90)/0xD5(len=15)**（单次出现，待抓包钉死语义）；924 探针因 i64 值缺
  TVarList 类型 tag 未被受理；GM 探针(msgId 2)无服务端日志；Go 心跳回包 opcode
  待钉死（Java 用 0x38；抓包中见疑似周期推送 0x1A）。

### 3.15 模拟登录深挖轮（2026-10-30 第十四轮）——§3.14 未解项全部闭环

以 test001（角色**商弘**，`acct:v1:112cd36f…`，带装备/技能/背包）完成全链路抓包：
**38,046+ 帧**（`tools/smoke/go_capture_shanghong.bin`，View1 纸娃娃 11 件已穿装备全
属性、View40 技能 **34,518 对象**、View121 背包物品），另有原始字节级抓取
`go_raw_wire.bin`（3.5MB，解出 41,817 帧零违例）。账号代入方式：blob→SHA-256 单向
不可构造（45 种编码组合离线爆破未命中），经 roles.json identities 临时映射实现
（备份→停服→映射→重启→抓→还原），test001 原映射未动。

- **属性表两套索引体系**（重大）：现版 exe 下发 **726 条**表（`tools/smoke/go_prop_table.txt`
  已提取），与 Java 官服 2424 条表的 index→Name 映射 **723/726 不同**，同名属性还有
  双索引段（如 ConfigID@7/105、LogicState@21/463、PKMode@363/437、CapitalType1@111/417）。
  0x10 帧布局逐字节同构（`op+0+u32 oid@2+u32 owner@6+u16 count@10+u16 idx@12+值`，
  打坐实测 idx=21=LogicState u8 值），**客户端按收到的表解帧，两端各自自洽**。
  Java 无需改动；但**解读 Go 抓包必须过 Go 表换算**（Java 文档中的 436/462/416 等
  编号是 Java 体系，勿混用）。
- **未知 opcode 0xFB/0xD5/0xFC/0xa1/0xf7… = 解码伪影，非协议消息**：全 .text 无任何
  写这些常量的指令；根因是 `smoke_client.decode_frames` 的残片跨块重喂 bug——
  返回**去转义后**的帧体残片，其中字面 0xEE 重喂时被再当转义标记，`EE xx` 误报
  bad escape → 丢缓冲错位重同步 → 伪影帧。触发条件：帧体含 0xEE（线上 `EE 00`）
  且跨 recv 分块，View40 洪泛高发。已重写为**转义形态残片**（记录帧起点返回
  `buf[start:]`），64k/4097/随机三种分块回放 41,710 帧零错误，live 全链路零丢帧
  零伪影（choose 恒 21383 帧完整）。Go 线上转义完全合规，无服务端 bug。
- **心跳**：现版 exe 对 C2S 0x18 ping **无专属回包**（1B/5B/官服 12B 三种布局实测
  均无响应帧、日志零 `write heartbeat reply`）；0x38 timeSync 从未出现。周期帧
  **0x1A = 服务器公告**：111B、逐字节相同、含 GBK 中文与 2012 年代时间戳，约每
  20s 一次，与 ping/时间同步无关。Java 的 per-ping 0x38 回包是客户端 HUD 延迟
  显示所需的增强，保留。
- **TVarList 值类型 tag 表**（日志 `%v` 反推实测）：**0x02=int32、0x03=int64、
  0x04=double、0x08=object(8B)**（0x06=string 既有；01/05/07 未测）。
- **924**：0x1E 直发 → `unhandled custom msg_id=924`；0x0A 信封 → 解析成功
  （`args=[int64:tick]`）但**无响应也无 unhandled**——现版 exe 实际不处理 924，
  "回显载体"无参考行为可对齐，Java 现状（0x1E 回显 tick）保留为超集实现。
- **GM 探针 2**：`handle gm command 2: gm msg 2 command text is empty`——
  布局为 `[2, cmd(int), text(str)]`，缺字符串命令文本即拒绝（此前"无日志"之谜）。

### 3.16 装备词缀与技能目录整推轮（2026-10-30 第十六轮）

基于商弘（满装满技能）+ 新号双份抓包的对照分析，两项落地。

**A. 装备词缀（PropBuilt）线上格式实测钉死**：

- **格式与 Java 完全一致**：`flag,id;flag,id;…`（抓包实测 `1,95021`、`1,95001;1,95011`、
  `1,12592;1,12592;1,12592;1,62995;1,62995;1,62995`）。**同包号可重复叠加**
  （3×12592=体质+117、3×62995=物防+1425），词缀条数 = ColorLevel 档位（≤MaxBuildCount）；
  武器无 PropBuilt（走 EffectPackID 1001/1647）。
- **proppack.ini 交叉验证**：包 12592=`StaAdd,39`、62995=`PhyDef,475`、95011=**双行包**
  （ParryUpSpeedAdd 2/Flag1 + 1/Flag0）；全表 300 种属性名/82,658 行，其中**小数值行 5,174**
  （AirRushDistAdd 0.4 等）。
- **Java 修复两处真缺口**（EquipBonusTest 覆盖）：① 目录层 `Integer.parseInt` 丢小数行
  → 改 double 解析截断保留；② 累积层缺 **`PhyDef`（576 行，实测装备主防御词缀）** 与
  后缀省略变体（MaxHP/MaxMP/Str/Sta/Dex/Dodge/MeleePower，共 ~40 行）→ 并入对应
  case。Java 属性名覆盖率 16 名/49,659 行 → 修复后 +PhyDef 系。
- **Go 数据怪癖（记录不跟进）**：耳环/戒指双槽两件**共享同一 UniqueID**
  （ear obj=11/12 同 `adc282961b7cd4ff`）——886 一键穿按 uid 认领时存在歧义；
  Java 的 uid=MD5(roleId:bag:slot) 恒唯一，保持现状。

**B. 技能目录（View 40）整推结构钉死并实现**：

- **结构**（新号/满技能号 choose 批同构，与账号无关）：choose 阶段
  `0x15 view=40 capacity=17905` + **全量目录 17,633 行**（≈skill_init 17,861 节减不可用），
  每行 10 属性（ConfigID/StaticData/ItemType/Level/MaxLevel/CurFill/TotalFill/WuXing/
  PrepareTime/TargetShapePara2），**Level/MaxLevel 恒 12 为展示值**（skill_init 的
  level 列恒 1；fill=0、TotalFill=文件 total 恒 156550）；ready 阶段**重推已学子集**
  （新号 13,949 行——13,782 门派技@12 + 167 数字 id 基础项@1；满技能号 16,885 行），
  复用 choose 的对象索引（1..17,687），**ready 批不含 view 40 的 0x15**。
  商弘号 View1 纸娃娃 11 件/View40 34,518 帧即两批之和。
- **Java 落地**（§3.4 待决项闭环）：`LoginFlow.sendSkillCatalog`——choose 阶段在
  8 视图/物品帧后整推（skill_init 回退内置集时保持原链路）；帧进程级缓存；
  `skillCatalogRow` 10 属性同 roleSkillViewAdd 布局（Level=12 展示值）；u16 护栏
  65,530；ready 批在目录已推时只重推行不重建容器（对齐参考）。SkillInitTable 的
  LearnedSkill 补 totalFill 列 + `all()` 保序访问器。156 测试全绿。
- **实机验证点（下次真机连 Java 服时重点观察）**：① 技能书/图鉴界面——目录整推后
  应能看到全部门派技能（此前仅 23 技，界面大量空白）；② 穿装备后的防御数值变化
  ——PhyDef 修复后护甲类词缀（如 62995 物防+475）应实际生效于面板防御。

### 3.17 基础技数字 id 与装备特效链轮（2026-10-30 第十七轮）

**C. ready 批数字 id 基础项语义钉死**（§3.16 B 遗留的 167 项）：

- View 40 ready 批中的数字 ConfigID（新号 167 唯一/商弘 186 唯一，各重推 2 次）
  = **jn_* 基础江湖技能按 skill_static 行号下发**：167/167 完美反查
  skill_new.ini `StaticData=<该值>` 的节（128 SkillNormal + 38 SkillLock + 1 个
  ItemType=1006），如 4=jn_aoe_1h_01（单手 AOE 普攻）、1=default_normal_skill。
  下发时 ItemType 统一标 **1010**、Level=1（基础技无成长）、fill=0；数量随账号
  已学集变化。**Java 暂不跟进**（客户端自带基础技动作，服务端重推为参考服冗余），
  若后续实测普攻异常再按此映射补推。

**D. 装备特效（EffectPackID）四跳链路钉死**：

```
装备帧 [325] EffectPackID（helmet=10013 / sword_60702=1647 / 首饰 60702=11151）
 → share/rule/equip_special_effect_pack.ini [N].EquipAddBuff=<buffId>,<等级>[;…]
    （563 节；10013=eqp_ImmStun,1；1647=eqp_WpResParry,6;eqp_wp_06,1 双 buff）
 → share/skill/buff_new.ini [eqp_*]（258 个 eqp_* 节）：script=Buffer + StaticData
 → share/skill/buff_static.ini [行号]：行为标志（Visible=0 隐形、CantCustomRemove=1、
    OffLineClear=1）+ varprop 区间
 → share/skill/buff_varprop.ini [区间]：实际效果（eqp_ImmStun 110221.. = Immunity=33/
    34/35… 按 buff 等级升位——头盔的"免疫眩晕"即此位）
```

- 语义：**装备特效 = 穿上即挂的隐形 buff**（不可手动移除、下线清除），效果经
  varprop 落属性/免疫位；同 EffectivePack 可挂多 buff、buff 带等级（6 级破招架）。
- **Java 缺口**（~~① 装备帧未传输 EffectPackID 属性~~——**第十七轮误记**：传输层
  早已存在（InventoryPackets `P_EFFECT_PACK=1957`，装备/背包两构造器均下发，
  值取 equipment.ini 配置）；② 穿/脱装备的 buff 生命周期与 ③ 免疫位判定——
  **第十八轮已落地 ②**，见 §3.18）。

### 3.18 装备特效 buff 层实现轮（2026-10-30 第十八轮）

§3.17 链路的服务端应用层落地（传输层经查证早已存在，见上修正）：

- **`rule/EquipEffectCatalog`**（四跳链路装载）：equip_special_effect_pack
  （pack→[(buffId,level)]，实测表 **518 包/257 buff**）→ buff_new（buffId→StaticData）
  → buff_static（StaticData→varprop 区间）→ buff_varprop（按 Level 索引行）。
  行取法：精确级优先 → ≤level 最大级（floor）→ 最大级兜底；
  `@PropModifyPackRec` 支持 `;,` 多值（实测 `95021;95061`）。
- **`PlayerActor.applyEquipEffects(effects, packs, packIds)`**：与
  recomputeEquipBonuses 成对调用（词缀先、特效后；三个接线点：ClientReady 进场景、
  msgId 30 穿/脱、一键穿/右键穿共用核心）。属性类特效经 varprop 的
  @PropModifyPackRec **复用词缀累积器**叠入 equip*Add（MaxHP/MaxMP 变化自动走
  增量推送）；Immunity 位收集进 `equipImmunities()`（只读）。
- **修复 `AiRuleData` 共享解析器潜在 bug**：KV 正则 `^(\w+)=` 不认 `@` 键，
  `@PropModifyPackRec`/`@SkillModifyPackRec` 等真实表行被**静默整行丢弃**——
  放宽为 `[\w@.]+`（BuffCatalog/AiRuleData 既有消费不受影响，全量测试绿）。
- **装备特效包号解析**：SceneFlow `equipEffectPackIds`（liveEquipped 逐件经
  EquipmentModels.itemStatic 取 equipment.ini 配置值；**实例级覆盖未接**——实测
  商弘的 sword_60702 抓包为 1647 而配置为 1648，重铸会换特效包，存档链待接）。
- **留待后续**：EventProcess 脚本类效果（302 处，需脚本系统）、
  @SkillModifyPackRec 技能修改（32 处）、Immunity 位在 CombatFormulas 的消费
  （控制效果系统接入后）、buff 的客户端帧（buff_static Visible=0 隐形，无需推送）。
- **测试**：+4（链路解析/等级 floor 回退/actor 叠加+免疫收集/真实表冒烟
  NINEYIN_SERVER_ROOT 下 518 包+实测值抽查），全量 160 绿。

### 3.19 商店购买实机链轮（2026-10-30 第十九轮）

`tools/smoke/shop_probe.py` 对 Go 服实跑全链（khd_146 oid=3580 → shop_khd_ywb →
购买 item_khd_001"阎王币"10000 碎银），三次迭代钉死：

- **购买参数布局勘误（§2.1/§3.13 原记 4 值有误）**：真机 **5 值
  `[70, shopId(str), page(0 基), pos(1 基), amount]`**——Go 拒绝串
  `shop buy needs 5 values (msg, shopid, page, pos, amount)`；发 page=1 被解析为
  第 2 页（`has no listing at page=2`，即线上 0 基），pos=0 被拒（`must be positive`，
  即 1 基）。**成功购买无服务端日志**（Go 只记拒绝分支）。
- **开店帧序**：`0x1E-01(int 8)` 关菜单 → **`0x16`（3 字节：op + u16 view=61）——
  未收录的视图清除 opcode**（0x15 建/0x18 增/0x19 删之外的第四个视图家族成员）
  → `0x15` view=61 带 4 个头属性（GO 表 101=ShopID 串 / 102=页大小 10 / 103=页数 /
  104=?）→ `0x18`×10 商品行（105=ConfigID / 106=数量 / 107 / 108 / **109=价格**，
  实测 `1027`→0x2710=10000 ✓）。
- **购买成功响应（§3.13 待办闭环：库存刷新帧不存在）**：无商店视图刷新（静态库存
  线上二次确认）、无扣款 0x10；仅 **0x19+0x18 ×2 对**（背包页 view=2 + 镜像页
  view=174，同槽移除重加）+ **`0x1E-05` 获得物品提示 `[377, 2, 图标id, 物品名
  (GBK 串), 数量]`**（实测 `[377, 2, 9645, "阎王币", 1]`）。（~~首购追加 View 43
  内功行副作用帧~~——**第二十轮更正**：该帧实为 ready 引导的迟到帧
  `granted View=43 inner-power and View=40 花无缺七 after explicit ClientReady`
  恰好落进购买窗口，与购买无关；购买响应即上述两类帧。）
- **Java 修复（真机购买此前必被拒）**：`handleShopBuy` 双布局——真机 5 值优先
  （shopId 消息内权威解析：repository 命中用之，未命中且与会话快照不符则拒绝）、
  旧 4 值容错；`SceneFlow.setShopRepository` 由 NineyinServer 装配。
  SceneFlowTest +3 组断言，全量 160 绿。
- **Java 超集保留**：Java 购买成功后仍推 0x10 CapitalType1 扣款帧（Go 不推、客户端
  下次全量同步才见余额）——保留 Java 行为（真机 HUD 即时反馈更好）。

### 3.20 商店打开帧序对齐轮（2026-10-30 第二十轮）

用一店双 capital 的判别探针（JobChengD010→Shop_ds_05001：27 件 capital1 金币品 +
2 件 capital2 碎银品）钉死商店视图行布局并完成 Java 对齐。

- **价格槽映射钉死（§3.12 勘误）**：**价格落 107+capital 直映射**——capital1 的
  27 行价格全部在 108（SellPrice1）、capital2 的 2 行全部在 109（SellPrice2）；
  **三价格槽 107/108/109 恒全量下发**（未用槽零填充，非单槽）；**110=MaxAmount
  恒 99**（单次购买上限，与 560 充值商城的 nums 1..99 上界同源）。capital≥2 夹 2
  （与购买侧"3=兑换同走碎银"一致）。
- **GO 表 101-110 商店视图段语义**：101=ShopID(str)/102=PageNum(页大小 10)/
  103=PageCount/104=ShopType/105=ConfigID/106=Amount/107-109=SellPrice0-2/
  110=MaxAmount。
- **Java 对齐**（SelectFlow.openShop）：补 **0x16 视图清除帧**（`SkillPackets.viewClear`
  3 字节，重开前清 View 61）→ 0x15 四头属性（原有，已一致）→ 行从 4 属性单价格槽
  改为 **6 属性全量三槽**（ConfigID/Amount/107/108/109/MaxAmount，此前 capital2
  路由到 108 差一）。SelectFlowTest +1（`shopOpenReferenceFrameSequence`：
  0x16→0x15 顺序、行 6 属性、双 capital 价格槽位断言），全量 161 绿。

### 3.21 仓库线关闭与使用链布局轮（2026-10-30 第二十一轮）

- **仓库（View 4）线关闭**：参考 exe 全字符串**零处"仓库"**、真实 npcconfig（city_commonnpc/
  battlenpc 均查）**无 DepotID 列**（`DepotID` 串只是客户端 schema 反射字段名）——
  参考服没有仓库服务，func=807000000 无处触发，无可探针。Java 的 `openDepot`/
  FUNC_TALK_DEPOT 在真实数据下为不可达超集代码，保留不动。
- **msgId 31 使用链布局钉死**：**恰好 4 值 `[31, view(int), slot(int), count(int≥1)]`**——
  3 值/5 值均 `reject malformed native item use values=…`，第 4 值 0 同拒（计数语义）。
  Java §3.12 的"至少 4 值"与实测吻合（Java 宽容 ≥5，保留无害）。
- **阎王币（item_khd_001，ToolItem ItemType=60/LogicPack=2）使用行为**：合法 4 值
  使用被**静默接受**——无响应帧、无服务端日志、无背包扣减帧；下会话背包 view=2
  已无该物品（疑似静默消耗）。货币型道具在参考服的使用即空操作。
- **§3.19 更正**（View 43/1240B 帧归属）：所谓"首购副作用帧"实为 **ready 引导的
  迟到帧**（`granted View=43 inner-power and View=40 花无缺七 after explicit ClientReady`，
  含 ~~0x1E-04 reset×N~~（**第二十二轮勘误：实为 msgId 414 的 'reset' 开关帧**）、1240B/1314B 全量属性批、BufferListStr 内功
  buff 行）——quiet 退出机制下 ready 尾部帧会落入后续窗口，分析窗口帧时需按时间
  归属核对服务端日志。完整清单见 §3.22。

### 3.23 邮件系统全链轮（2026-10-30 第二十三轮）

反汇编 `main.handleMailCustom` + GM `send_test_mail` 实机造邮件抓帧，§3.9 的邮件
校验桩升级为全链实现。

- **msgId→op 语义（反汇编钉死，8 编号）**：
  | msgId | op | 证据 |
  |---|---|---|
  | 350 | 打开邮箱（Load 重读存储） | → `mailStore.Load`（mapclear+Load） |
  | 353 | `[serial(str)]` 标记已读 | → `mailStore.SetRead` |
  | 355/359 | `[serial(str)]` 领取附件 | → `mailStore.Attachment`（共享分支） |
  | 356/361 | `[serial(str)]` 删除邮件 | → `handleMailDelete` |
  | 357 | `[int,…]` 选择（`mail selection has invalid fields`） | `handleMailSelection`（exactInt32） |
  | 365 | 刷新（Load 重读） | → `mailStore.Load` |

  前置：需玩家 + `main.installedMailStore`；serial 取**最后一个值**且必须 string 型
  （tag 6/7）非空；serial 未命中一律静默无响应（实机复测）。
- **实机帧序**（`tools/smoke/mail_probe.py`，GM `send_test_mail itemConfig=yaopin_001
  amount=2`）：GM 造件即时推 **0x11 记录 17（RecvLetterRec）行**（285B）；
  353 → **0x13**（18B）；355 → **0x19/0x18 ×2**（背包 view 2 + 镜像 view 174 入包对，
  与 560 商城同构；无 0x1E-05 提示帧、无扣款帧）+ **0x13**（26B）；
  356 → **0x12** 删行；350/365/357 无 S2C 响应（Load 不重推行）。
- **RecvLetterRec 15 列布局逐字节钉死**（285B 恰好消费）：
  `[sender_id(str), ""(str), 101(u8), title_id(str), content_id(str), "0", "0",
  附件XML(str，领取后=""), created_at(i64 秒), read(u8), serial(str), "0"(str),
  0(u8), 2592000(i64 有效期 30 天), 0(u8)]`；附件 XML 实测
  `<Mail><Object Config="yaopin_001"><Property Amount="2"></Property><Records>
  </Records></Object></Mail>`；serial 形如 `sys-{roleId}-{nanotime}-0`；
  sender/title/content 为 UI 文本 id（战报三件套 ui_mail_war_order_*）。
- **0x13 单元格更新帧格式钉死**（`main.serverRecordGridCellsTarget` 逐指令 +
  邮件 18B/26B 与轻功 ActiveQGSkillRec 38B 三样本互证，补 §1.2 语义）：
  `0x13 + u8(0) + u32 oid + u32 owner + u16 表索引 + u16 单元格数 +
  (u16 行号 + u8 列号 + 按列型值)*`——**每个单元格自带行号**；值编码与 0x11 行
  一致（string = u32 len(含 NUL) + GBK + NUL）。
- **数据模型**（Go 服 data/mail.json 实读，Java 同构共享同文件）：
  `{version:1, roles:{roleId:[{serial, created_at, read, sender_id, title_id,
  content_id, attachment?{config_id, amount}}]}}`；GM 面板动作
  `POST /api/sessions/{id}/command {action:'send_test_mail', itemConfig, amount}`。
- **Java 落地**：`game/MailStore`（JSON 持久化 + setRead/takeAttachment 一次性领取
  /restoreAttachment 入包失败回滚）；`protocol/MailPackets`（0x11 行/0x13 已读/
  0x13 领件/0x12 删行，金样测试逐字节对照实机 4 帧）；SceneFlow 八编号接入 +
  ready 进场景推全部邮件行（sendSceneNpcs）+ 领件入包与 560 同构 + 删除后余下
  行按新行号重推；GmActions 新增 `send_test_mail`；测试 +9
  （MailStoreTest 3 / MailPacketsTest 5 / SceneFlowTest.mailLifecycle），
  全量 **174 绿**。
- **留待后续**：357 选择态（mailSelected）的消费语义；多邮件行号重排实机验证
  （本轮单邮件）；邮件过期清理（col13 30 天）；多物品附件 XML（多 Object 节点）。

### 3.22 ready 迟到帧家族与 S2C 0x1E msgId 语义轮（2026-10-30 第二十二轮）

对商弘/新号两份 38k 帧抓包的 ready 阶段（#21388-40788）逐帧解码 + exe 字符串/
反汇编/二进制常量三路互证，迟到帧家族全部钉死，"reset 族"语义闭环。

- **勘误**：§1.2/§3.21 旧记 "0x1E-04 reset" 混淆了两件事——exe 串
  `write qinggong 0x1E-04 idx=205` 的 "0x1E-04" 是 **S2C custom msgId=4**（轻功/UI 族，
  与 msg 5/6/7/8 同族：click 阶段实测 `[4]`=关菜单、`[5,'task_khd_146']`=任务菜单、
  `[6,int,'ui_buykhd_146']`=UI 表单、`[8]`=开店关菜单，即 Java 已有的 cinematic 系）；
  而 ready 尾的 reset 帧真身是 **msgId 414 的字符串子命令** `[414,'reset',开关id,1]`。
- **414 家族语义**：`'gamestep'`（九阴志阶段通告，`encode gamestep` +
  `main.ninJiuZhiGameStepFrames`，值=阶段 0..9 与 419 同源，ready 首帧
  `[414,'gamestep',6,1]`→`[419,6]` 成对出现）与 `'reset'`（**VIP/系统开关置位**，
  writer=`main.enableVipSwitches`——反汇编 0x9a8e55 起的 movabs 打包立即数
  `0x46_0000002a`(42,70)、`0xb2d_00000034`(52,2861)、`0xb4c_000000e6`(230,2892)、
  `0x3fd_00000b3e`(1021,2878) 与抓包 11 帧完全对应；`restore vip/tersure switches`、
  重铸 `414 switch-reset` 同族互证）。开关表（两账号恒定）：
  **41/42/52/53/70/230/1021/1223/2861/2878/2892**，值恒 1；发送顺序为 Go map 序
  （1223,53,41,42,70,52,2861,230,2892,2878,1021），客户端无序敏感。
  **1223 即 Java 既有 `skillActionSwitch` 单点发现的"技能动作开关"**，本轮归位。
- **ready 尾部完整帧序**（#40486-40788，两账号结构一致）：

  | 帧 | 语义 | 证据 |
  |---|---|---|
  | 0x18 View40 尾行（…obj=17687） | 技能目录 ready 重推尾部 | §3.16 |
  | 0x1E `[435,8,1]/[435,8,2]/[435,6,0,…]/[435,4,1,…]/[435,4,2,201,203…]/[435,5,3,0,0,1…]` | **faculty/修炼进度恢复批**（215 PlayerProgress 同源 `encodeFacultyMessage`；sub6 含哈希类大数） | 反汇编调用链 |
  | 0x14 清 + 0x11×11 记录 26/27（SecondWearWuJiRec/WuJiActivateRec） | 无极二次穿戴/激活行 | 40 表 schema |
  | 0x10 1315B（174 属性）×2 | 全量属性批（首尾各一次） | `encode player ready vital sync` |
  | 0x15 View45 cap35 + 35 行 | **经脉视图**（jm_shoushaoyin/shoujueyin/shoutaiyin… 35 条，5 属性/行） | 行内容实测 |
  | 0x11 记录 12/13（active_jingmai/PepareJingMai）28 对交错 | 经脉激活/预备行 | 40 表 schema |
  | 0x1E `[140,'1-1','born']` | **首登出生特效** | `sent first-login born effect route=140 target=%s effect=born` + `main.firstLoginBornEffectFrame` |
  | 0x11 记录 4（shortcut_rec）×117 | 快捷键行 | `grantShortcutRows`/`rebuild add shortcut index` |
  | 0x10 677B×3（属性 326/327/328）+ 0x1E `[195,1,scheme]/[195,2,scheme]` | **快捷键方案双通道推送**：串内容 = exe 内嵌默认方案（658 字符，两套仅 3-6 槽顺序不同，Java 常量已逐字节核对）；195 为 C2S 保存方案的 S2C 回推 | exe 字符串常量 + 抓包逐字节一致 |
  | 0x1E `[414,'reset',id,1]`×11 | VIP/系统开关置位 | 见上 |
  | 0x1E `[52,0,0]` | **未钉死**（无日志串；出现 2 次：ready 首部任务追踪行前 + 尾部开关帧后） | — |
  | 0x15 View80 cap16 + 1 行 + 0x1E `[238,1]` | **掉落箱**：行=掉落物品（ConfigID=yaopin_00100×3 + uid 串），238=开启通告（`main.encodeDropOpenFrames` 反汇编 `mov $0xee` + `serverCustomIntMessageWithOpcode(0x1e,0xee)`） | 实测 |

  ready 首部另有：`[414,'gamestep',6,1]`→`[419,6]`（#21388-89，早于一切视图洪泛）、
  `[52,0,0]`、`[209,9,taskId]`×16（**任务追踪窗行**，`initialized native task-trace
  form via custom message=209` + `main.sceneTaskTraceBootstrapFrame`；taskId 为
  227/770/786/789/790/1525/1537/7009/51135/55586/61019/74422/74449/74451/78317/82467）。
  choose 阶段仅存档角色有 `[107,捏脸二进制串]`（`pushCustomizingRaw` /
  `sent customizing restore role=%d raw_len=%d mode=%d`）。探针响应另有
  `[24,'obj(1,1)',17,13/14]`（坐下）与 `[24,'obj(1,1)',7,0]`（起身）——
  打坐动作通告（`encode sitcross state`），与 0x10 LogicState 并发。
- **0x0A 记录表 schema 完整解析**（841B 帧恰好消费完毕）：Go 声明 **40 张表**
  （索引即 0x11 帧的表索引），0-5 与 Java 同名，6=ForceRec、7=TaskTimeLimitRec、
  8=Time_Limit_Form_Rec、9/10=Card/UseCard、11=title、12/13=active_jingmai/PepareJingMai、
  14=NewRide、15=vip_info、16=Weather、17=RecvLetter（15 列）、18/19=rec_box_event(+prize)、
  20=Task_Record（15 列）、21=job、22=CardSkill、23=binglu_rank、24=FashionSuit、
  25=blend_collect、26/27=SecondWearWuJi/WuJiActivate、28/29=Prop/SkillModifyPack、
  30/31=RandomProp/RandomSkill（含 f64 列）、32=TempTersure、33=clone_rec_save、
  **34=team_rec（13 列，首列 oid）**、35-38=Origin 四表、39=Repute_Record。
  列型差异（Go vs Java 0-5）：ActiveQGSkillRec Go=[7,1]、Task_Accepted Go=**28 列**、
  cooldown_rec Go 末列=2（i16）——Java 行推送按自家简化 schema 保持自洽，6-39 按 Go 原始列型接入。
- **Java 落地**：①`CustomPackets` 新增 `ninJiuZhiGameStep`/`firstLoginBornEffect`/
  `shortcutScheme`/`enableVipSwitchFrames`（11 开关表常量）；②`BootstrapPackets`
  内置默认方案 ×2（658 字符逐字节对齐 exe）+ `BORN_EFFECT_TARGET="1-1"`；
  ③`RecordId` 扩展至 40 表（6-39 Go 列型，含 u8/i16/f64/oid 新列型常量）；
  ④`LoginFlow.sendUiBootstrap`：gamestep→419 成对、born 特效（快捷键行前）、
  方案 ×2 + 开关 ×11（替代原单 1223 帧）；`SceneSwitcher` 重进链同步；
  ⑤测试 +4（BootstrapTailFramesTest）+ recordTableEmpty/LoginFlowTest 断言更新
  （0x0A 819B/40 表、ready 0x1E 计数 16），全量 **165 绿**。
- **留待后续**：`[52,0,0]` writer 未钉死；209 任务追踪行（Java 无任务系统）；
  435 faculty 恢复批格式；View45 经脉 35 行 + 记录 12/13（经脉系统未接）；
  View80/[238] 掉落箱（Java 无 DropBox 实体）；1315B 174 属性全量批（Java 0x0D
  已覆盖主体）；0x10 属性 326/327/328 方案串（Go 表专用编号，Java 官服表体系
  不同，需先在 Java 表定位同名属性再推）。
