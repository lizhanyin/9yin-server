# Go 参考服源码整理（复刻向·帧格式与注意事项）

来源：`G:\pojie\ai\9yin-go-server1`（= 运行中 exe 的源码）的 `cmd/` + `internal/` 全量注释（451 条）与帧构建代码逐文件精读。
目的：为 Java 复刻（9yin-main-new 分支）沉淀**字节级帧格式、协商表、时序、坑清单**。所有断言均以 Go 源码为准，标注 `文件:行`。

配套：`docs/go-replica-登录进世界-一二期.md`（一二期链路）、`docs/combat-skill-211.md`（战斗）、golden 对拍（见 §13）。

---

## 1. 传输层帧格式（internal/transport）

**无长度前缀，转义终止符分帧**（frame.go:36-104）：

```
明文消息 → 4 字节循环 XOR → 逐字节转义 → EE EE 终止
```

- XOR key：初始 `0xFA6D3BCC`（key.go:7），按**小端**拆成 4 字节循环（`CC 3B 6D FA`），对称加解密（key.go:33-39）。
- 转义规则：密文体中每个 `0xEE` 后插 `0x00`；帧尾 `EE EE` 结束。读侧：`EE 00`→一个 `EE` 体字节，`EE EE`→帧结束，`EE xx`(其它)→协议错（frame.go:38-77）。
- 帧大小上限：transport 默认 1MB；protocol-probe 会话用 **64KB**（main.go:188）。
- 消息层：解密后首字节 = opcode，其余字段一律小端。

**坑（frame.go:18-20, main.go:497-501）**：
- `bufio.Reader` 必须跨帧复用一个——每帧新建会丢预读的下一帧字节。
- **不要设读超时**：在转义帧中途超时会丢弃半截体。
- 读循环单 goroutine 独占 `ReadFrame`，完成帧经 channel 与 GM 命令复用（否则安静客户端会永久阻塞 GM）。

## 2. 会话状态机与 C2S 长度规则（internal/session/state.go）

Phase：`Connected → RoleCreation/RoleSelection → EnteringWorld → AwaitingActivity → InWorld`；`ReenterScene()` 从 InWorld 回 EnteringWorld（0x0C 重入同一套 Ready/Activity 屏障，state.go:215-224）。

C2S opcode：`0x02 Login`、`0x03 WorldInfo`、`0x04 ChooseRole`、`0x05 CreateRole`、`0x09 ClientReady`（1 字节整帧）、`0x0A Activity`。

关键宽容规则（state.go:115-133）：
- **0x09 可重复**：进场景后再发是健康流量，必须幂等，不能断连。
- **0x0A 在 ChooseRole 后、ClientReady 前就有多帧**（合法 pre-ready 流量），但不得打开对象屏障。
- 未分类 opcode（实测捕获含 0x18）一律放行给业务层。
- 长度下限：0x02≥5、0x04≥0x54、0x05≥0x49、0x09==1（state.go:140-167）。0x03/0x0A 故意不设布局。

## 3. S2C opcode 总表（internal/protocol/generated/opcodes_gen.go，含客户端 receiver 地址）

| opcode | 名称 | receiver |
|---|---|---|
| 0x0B | ServerEntryScene | sub_1060D810 |
| 0x0C | ServerExitScene | sub_1060DA60 |
| 0x0D | ServerAddObject（0x28 同 handler 别名） | sub_1060DBC0 |
| 0x0E | ServerRemoveObject | sub_1060DEE0 |
| 0x0F | ServerSceneProperty | sub_1060E1D0 |
| 0x10 | ServerObjectProperty | sub_1060E3B0 |
| 0x11 | ServerRecordAddRow（0x29 别名） | sub_1060F980 |
| 0x12 | ServerRecordDelRow | sub_10610660 |
| 0x13 | ServerRecordGrid | sub_106116B0 |
| 0x14 | ServerRecordClear | sub_106122C0 |
| 0x15 | ServerCreateView | sub_1060E8A0 |
| 0x16 | ServerDeleteView | sub_1060EB00 |
| 0x17 | ServerViewProperty | sub_1060EDA0 |
| 0x18 | ServerViewAdd（0x2A 别名） | sub_1060F070 |
| 0x19 | ServerViewRemove | sub_1060F390 |
| 0x1B | （0x1A/0x1B/0x1D 未命名） | sub_10617320 |
| 0x1C | SERVER_MENU | sub_10617490 |
| 0x1E | CustomString/Int 消息壳 | sub_10617A90 |
| 0x1F | ServerLocation | sub_10612CE0 |
| 0x20 | ServerMoving | sub_10612FE0 |
| 0x21 | ServerAllDest（0x2C 别名） | sub_106132F0 |
| 0x24/0x25/0x26 | ServerLinkTo/Unlink/LinkMove | sub_10613730/E50/3AC0 |
| 0x27 | Custom 消息现代别名（**与 0x1E 同 handler**） | sub_10617A90 |
| 0x2B | ServerViewChange | sub_1060F660 |
| 0x2D/0x2E | ServerAllProp | sub_106140E0 |
| 0x2F/0x30 | ServerAddMoreObject | sub_10614610 |
| 0x31 | ServerRemoveMoreObject | sub_10614B60 |
| 0x32 | ServerChargeValidstring | sub_10615050 |
| 0x05 | ServerWorldInfo（旧世界服） | sub_106167B0 |

## 4. 属性表协商 0x09（全连接一次，connection-scoped）

格式（main.go:1483-1492）：`0x09 | u16 count | (C 字符串名\0 | u16 index? 否——见下)`。
实际是 `名字\0 + u16 index + u8 wireType` 每条（`msg = append(name...); append(0, byte(Type))`，名字后跟 NUL 和类型字节；index 隐含为字段序号）。

**WireType 编码（npc_schema.go:12-22）**：`1=byte 2=word(2B) 3=int32 5=float32 7=string 8=widestring 9=object(8B)`。
Go 源码时代 4/6 不入属性表；**真机 F 参考服 2370 项表两者都在**（52905 抓包 0x09 表实测：type4=58 项、type6=33 项），
且 **4 与 6 均按 8B double 解**（CapitalType1=104514.0 的 double 编码；按 4B 解 188 属性面板必错位）。

### 4.1 基础 99 字段（NPC 可见属性，npc_schema.go:95-195；Modern 版仅改 34=NpcType 为 int32）

```
0 Type(b) 1 Sex(b) 2 Photo(s) 3 Camp(i) 4 Race(i) 5 Job(i) 6 Level(i)
7 ConfigID(s) 8 Resource(s) 9 PosiX(f) 10 PosiY(f) 11 PosiZ(f) 12 Orient(f)
13 Hair(s) 14 Face(s) 15 Cloth(s) 16 Pants(s) 17 Shoes(s) 18 ActionSet(s) 19 State(s)
20 FloatingState(i) 21 LogicState(b) 22 JumpSpeed(f) 23 MoveSpeed(f) 24 WalkSpeed(f) 25 RunSpeed(f)
26 SpeedRatio(i) 27 CantJump(b) 28 HPRatio(i) 29 MPRatio(i) 30 MaxHP(i) 31 MaxMP(i) 32 HP(i) 33 MP(i)
34 NpcType(i, Modern; Compat 是 byte) 35 Hat(s) 36 Name(w) 37 Scale(s) 38 SelectState(b) 39 CollideType(i)
40 CantShowHeadInfo(b) 41 NPCCanSelect(word) 42 WeaponMode(s) 43 RotatePara(s) 44 NeedRotate(i) 45 Collide(i)
46 CursorShape(b) 47 CollideRadius(f) 48 LuaScript(s) 49 AITemplate(i) 50 PatrolMode(i) 51 PatrolRange(f)
52 SpringRange(f) 53 School(s) 54 Hometown(s) 55-57 OriginPara1-3(i) 58 NpcTalkRule(s) 59 CanQiecuo(i)
60 PowerNo(i) 61 BossLevel(i) 62 NotPositive(i) 63 CantAttack(i) 64 CantBeAttack(i) 65 CantBeKilled(i)
66 AIFightFreq(i) 67 ChaseRange(f) 68 SkillHoldTime(i) 69 SelfForceID(i) 70 SummonIDBeginFight(i)
71 StopTime(i) 72 Character(i) 73 KarmaLevel(i) 74 Force(i) 75 MotionNoRotate(b) 76 CanPick(b)
77 VisType(i) 78 VisDataInt(i) 79 VisDataStr(w) 80 HeadEffect(i) 81 NpcNickName(w) 82 ExtraInfo(s)
83 RoleHide(i) 84 CKindID(i) 85 EscortFlag(i) 86 SpringEffect(s) 87 Material(s) 88 WeaponName(s)
89 BornAction(s) 90 ActionSoundMode(i) 91 Fixed(i) 92 FixedBeHitAct(s) 93 RefreshTime(i) 94 DirectSpring(i)
95 NpcTalkType(i) 96 PreLoad(i) 97 OnlyCareDistance(i) 98 RefreshAtOnce(i)
```
b=byte s=string w=widestr i=int32 f=float32。**NpcType 必须 int32**：现网数据到 1228，FxGameLogic 与 371/401 比较（npc_schema.go:207-214）。

### 4.2 追加字段（main.go:1494-1618 场景表 = NPC99 + 以下）

| idx | 名称 | 类型 | 说明 |
|---|---|---|---|
| 99 | MaxVisuals | i | 画质档位 |
| 100 | LastObject | obj | 目标选择（非对话属性） |
| 101-111 | ShopID(s) PageNum(i) PageCount(i) ShopType(i) ConfigID(s) Amount(i) SellPrice0/1/2(i) MaxAmount(i) CapitalType1(i) | | form_shop.lua createview 直读，须先协商 |
| 112-116 | HitHP HitHPRatio QingGongPoint MaxQingGongPoint MaxQingGongPointAdd | i | 瘀血层 + 轻功点 |
| 117-136 | RunSpeedAdd JumpSpeedAdd DriftSpeed(+Add) DriftJumpSpeed(+Add) SndJumpSpeed(+Add) ThdJumpSpeed(+Add) AirRushSpeed(+Add) AirRushDist(+Add) LandRushSpeed(+Add) LandRushDist(+Add) ClimbSpeed(+Add) | f | 全 float32；**LandRushDist 缺失按 0 显示并使用** |
| 137-142 | GRushRange(+Add) ARushRange(+Add) WRushRange(+Add) | f | 三系冲刺的范围别名（rush_range.ini 本地判定用） |
| 143 | BufferListStr | s | BuffContainer 快照（见 §10） |
| 144-167 | BufferInfo1..BufferInfo24 | s | BuffStore 视图槽 |
| 168-170 | Gravity(f) GravityAdd(f) DropHeightPub(f) | | **Gravity 仅在协商存在时客户端才应用**（跳跃重力 9.8）；DropHeightPub 缺省回退 5.0（太小→普通下坡进跌倒态） |
| 171-207 | 玩家成长字段（player_progress.go:13-56, 247-284） | | 顺序：CurNeiGong(s) Faculty(i) FacultyState(i) FacultyStyle(i) FacultyName(s) CurFillValue(i) TotalFillValue(i) CurLevel(i) FillSpeed(i) PowerValue(i) MaxPowerValue(i) SP(i) Str Sta Dex Ing Spi MeleePower MagicPower PhyHit MagicHit Dodge MaxParry PhyDef StiDef NegDef MasDef JujDef MinMeleeDamage MaxMeleeDamage MaxHPAdd MaxMPAdd MaxLevel StaticData ItemType MaxParryAdd MinMagicDefAdd（全 i 除注明） |
| 208-212 | NeigongPKStatus Dead CantUseSkill CurSkillID(s) PauseTime(f) | | **NeigongPKStatus 无 HasProp 保护被读**，不协商≠正常值；PauseTime 毫秒 float |
| 213-220 | HPHeartSpeed(+Add) MPHeartSpeed(+Add) HPUpSpeed(+Add) MPUpSpeed(+Add) | i | 心跳=间隔 ms（本地 1000）；UpSpeed=恢复 tooltip 实际量 |
| 221-225 | CurSkillEffectID(s) CurSkillLevel(i) CurSkillTarget(obj) ModifySkillLockTime(i) CanUse(b) | | 211 受理后的完整公开元组；**CanUse 是 View=40 对象字节，缺了图标灰** |
| 226-227 | Force(s) NewSchool(s) | | 门派三选：客户端先 Force 再 NewSchool 再 School，缺则显示"无门派" |

**核心原则**：0x0D/0x10/0x15/0x18 属性档案只带**数字索引不带名**，字段顺序一旦上线不可重排，只能尾部追加（player_actor.go:22-24）。

## 5. 记录表协商 0x0A（S2C，qinggong_records.go:121-143）

格式：`0x0A | u16 count | (名字\0 | u16 列数 | 列类型字节数组)*`

**列类型码（RecvRecordRow sub_1060BC40 取证，qinggong_records.go:43-58）**：
`1`=1字节 `2`=2字节 `3`=4字节整数 `4`=有符号8字节 `7`=长度前缀+NUL 窄字符串。
**6 是 double 不是字符串**——把 "qinggong_*" 声明成 6，客户端会把前 8 字节当数字读掉、行被语义丢弃。

Go 侧协商的 6 张 record（recordIndex 0-5，qinggong_records.go:73-98）：

| idx | 名字 | 列 |
|---|---|---|
| 0 | QingGongRec | (7) 单列 string |
| 1 | ActiveQGSkillRec | (7) |
| 2 | Task_Accepted | 3,3,3,7,7,7,3（**七列兼容前缀**；声明 26 列只发 7 列→第 7 列 Out range 异常） |
| 3 | Task_Completed | 3（32 位 bitset） |
| 4 | shortcut_rec | 3,7,7 |
| 5 | cooldown_rec | 3,4,4,3（start/end 为 int64 毫秒） |

**QingGongRec（记录）≠ QingGongPoint（属性）**：`CheckLearn_QingGong` 查记录，资源走属性，两条独立协议路径（qinggong_records.go:13-16）。

### 5.1 行操作帧

- **0x11 RecordAdd**（qinggong_records.go:148-231）：`0x11 | u8 0(场景对象) | u32 obj | u32 owner | u16 recordIndex | u16 源行起始(+0x0C) | u16 行数(+0x0E)=1 | 按协商类型的单元格`。
  **+0x0C/+0x0E 是"源行 start/count"，不是列区间**；每次恰好一行完整行，count 必须 1。行是**追加**语义，稀疏行需先发前面行。玩家记录的 obj/owner 必须与 0x0D 一致（1-1），否则 GetPlayer()/QueryRecord 看不见。
  单元格编码：int=u32、int64=u64、string=u32 长度(含 NUL)+字节+NUL。
- **0x12 RecordDelRow**（shortcut_records.go:53-65）：**整帧恰 14 字节**，`0x12 | 0 | u32 obj | u32 owner | u16 recordIndex | u16 0 基行号`。删除按**记录行号**，不是可见快捷栏索引。
- **快捷栏变更方言 = 全量重建（menu2 rebuildShortcutRecord，52905 真机抓包实证）**：任何有效 200/201 都**不做增量删行**，而是按变更前行数从 preCount-1 **递减到 0** 逐行 0x12 清空客户端记录，再按服务端列表全量重推 0x11（被替换/新增行落表尾）。抓包 14 个重建 burst（117 行表：dels 116..0 + adds 116/117...），交换四连 = 连续 4 次重建，无操作静默。增量发中段行号 0x12 在真机客户端不可靠——拖拽后图标残留（幽灵）/同槽双行（重叠）/拖出删不掉，2026-11-04 Java 增量方言真机复现。

### 5.2 任务位图（qinggong_records.go:253-265）

`taskCompletedBit(taskID)`：serial=taskID+1（一位基）；row=serial/32，bit=serial%32（bit==0 时回退上行 32）；mask=1<<(bit-1)。任务 1346→row 42 bit 3 (0x4)。
**Task_Completed 只追加**：要置高位必须先把 0..finalRow 全部行发出（含全 0 行）（qinggong_records.go:292-297）。

功能解锁任务族（qinggong_records.go:233-250）：主功能 1346；条件 23614 读 Task_Accepted 列 6 的 1069-1073/1344；条件 23613（演武按钮）读 1140-1147/51002，且嵌套公式 23647 查 completed 位——**两族都要发**（实测只有 completed 位不点亮按钮）。

## 6. 帧布局速查

### 6.1 登录链

- **C2S 0x02 登录身份**（auth/login_identity.go）：25B 头；`u32 accountLen@0x15`；随后 account 槽（长 16-256 且 %16==0）→ password 槽（16-512）→ client-system 槽（不参与身份）→ login string 槽（16-512）→ 末尾 u32 login type，**不允许尾随字节**。服务端账号键 = `sha256("nineyin/login-account/v1" + 0 + len_le + account)` → `acct:v1:<hex>`。
- **S2C 0x04 角色列表**（messages.go:412-424, 45-108）：无角色=0x29 字节基帧（op 0x04, u32 1@0x01, 年@0x09 月@0x0D 日@0x11 时@0x15 分@0x19 秒@0x1D）。有角色：`u32 角色数=1 写 @0x25`，然后追加 **6 值 TVarList**：`(2,int32 0)(2,int32 0)(7,UCS2 名)(7,UCS2 rolePara)(2,int32 0)(5,double 0)`。
- **rolePara**（messages.go:78-93）：36 个逗号分隔字段。要点：f0=性别 f1=门派 f5=照片 f7=发型 f9/10/11=衣/裤/鞋 f21-24=`stand,127.0.0.1,title001,scene_name_017`（**选角列表的场景名链**）f25/f32=内功书 f33=发型资源。
- **S2C 0x05 世界信息**（main.go:636-643）：`0x05 | u16 infoType | UCS-2LE NUL 结尾字符串`（**无长度前缀**）。五个开放书：`"0,book1,0,book2,0,book4,0,book5,0,book8,0,0"`。
- **C2S 0x05 建角**（messages.go:30-43, 12-28）：名 = UTF-16LE @offset 5（≤72 字节 NUL 截断）；外观串 = 从 **0x49** 起扫描 tag=6 的 NUL 结尾字符串序列。

### 6.2 场景链

- **S2C 0x0B EntryScene**（scene_transition.go:17-29）：`0x0B | u32 obj=1 | u32 owner=1 | u32 1 | u16 属性数=3 | (7,"scene.Config") (8,"scene.Resource") (99,u32 1000000 MaxVisuals)`。属性 schema（0x09）是连接级，**0x0C 重入不重发**。
- **S2C 0x0C ExitScene**：整帧 1 字节 `{0x0C}`。
- **S2C 0x0D AddObject**（main.go:1384-1408, player_actor.go:1080-1183；messages_gen.go:76-111）：定长头 **0x3F=63B**：`op | u32 obj@1 | u32 owner@5 | f32 X@9 Y@0xD Z@0x11 Orient@0x15 | 9×f32 运动@0x19..0x3C | u16 属性数@0x3D`，随后属性档案（u16 索引+按协商类型值）。offset 0x09..0x3C 就是 13 值 transform/motion 头；对象种类在 Type 属性（idx 0，玩家=2），**不要在尾部塞猜测字段**。
- **S2C 0x0E RemoveObject**：9B：`op | u32 obj | u32 owner`。
- **S2C 0x10 ObjectProperty**（object_property.go:13-31）：`op | u8 isViewObject | u32 obj | u32 owner | u16 count | 档案`。**值不带类型字节，类型唯一来源是 0x09 表**。
- **属性值编码**：byte=1B；word=2B；int32/float32=4B；string=`u32 长度(含 NUL)+字节+NUL`；widestring=`u32 字节长(含 2B NUL)+UTF16LE+u16 0`；object=8B `u32 obj \| u32 owner<<32`（低字=对象、高字=owner，main.go:1628-1631）。
- **S2C 0x1F ServerLocation**：**恰 25B**：`op | u32 obj | u32 owner | f32 X Y Z Orient`。语义=更新原生 Posi/Dest 并触发 on_location，用于放置**已创建**的异步 Actor2（main.go:1444-1458）。
- **S2C 0x20 ServerMoving**：**恰 45B**：`op | u32 obj | u32 owner | f32 DestX DestY DestZ DestOrient | f32 Motion0..3 | u32 Extra`。后 5 字段动画语义未证实保持 0；后续 0x1F 是权威到达确认（server_moving.go:10-40）。
  **战斗 tick 里 0x20 不要与 0x1F 同拍配对**——角色会瞬移到目的地（combat_ai.go:244-250）。到达确认延后一个移动间隔，用 transform 比较作取消令牌。
- **C2S 0x0A 运动位置更新**（motion.go:8-31）：**恰 67B** 才解析；0x26 起四个 `tag(=4)+f32` 连续 = X/Y/Z/朝向。**更长包含目的地/速度/角度，扫出来的坐标是假元组，绝不能入库**。合理性界：|X|,|Z|≤10000，|Y|≤3000，|Orient|≤20。
- **C2S 0x0A 自定义信封**（custom_c2s.go:90-104）：`0x0A | 16B 会话区 | u32 序列 | TVarList`（共 **21B 头**）。0x0A 还承载非自定义活动流量，解析失败必须 ok=false 静默放行。
- **C2S 0x07 对象点击**（client_object_request.go）：捕获恰 33B；`u32 seq@17 | u32 obj@21 | u32 owner@25 | i32 FuncID@29`（≥33 接受）。FuncID==0 是初次点击选对象；非 0 是菜单项 mark（两段式：点击→SERVER_MENU→选 mark→业务协议）。

### 6.3 View 体系

- **0x15 CreateView**（messages.go:313-358）：`op | u16 viewID | u16 capacity | u16 属性数 | 档案`。空容器视图=7 字节头。
- **0x18 ViewAdd**：`op | u16 viewID | u16 objectIndex(1 基) | u16 属性数 | 档案`（同一套协商编码）。
- View ID 权威=客户端 `share/view_define.lua`（messages.go:395-410）：

| View | ID | Capacity | 用途 |
|---|---|---|---|
| VIEWPORT_EQUIP_TOOL | 121 | 132 | 装备页（36 基础+16×6 扩展） |
| VIEWPORT_TOOL | 2 | 132 | 背包 |
| VIEWPORT_MATERIAL_TOOL | 123 | 132 | 材料 |
| VIEWPORT_TASK_TOOL | 125 | 132 | 任务 |
| 四个 ADDTOOLBOX | 122/3/124/126 | 16 | 对应扩展槽 |
| SkillContainer | 40 | — | 已学技能（按 skill_static.ini 的 TaoLu 分组，套路无独立 view 对象） |
| 内功 | 43 | — | GoodsGrid 直读 StaticData+ItemType（只有 ConfigID 不够） |
| QingGongContainer | 46 | — | 高阶轻功卡（**用 idx7 ConfigID 原始字段，不是 105 兼容字段**） |
| 商店 | 61 | — | 四设置 ShopID/PageNum/PageCount/ShopType 从 createview 回调取，客户端**不自己加载 shop.ini** |
| VIEWPORT_DEPOT | 4 | — | 仓库；无 Lua 打开入口，靠原生 0x15 处理打开 |

### 6.4 记录/属性之外的帧

- **0x1C SERVER_MENU**（messages.go:121-193）：`op | u32 obj | u32 owner | u16 count | 每项(u8 type, u16 mark, u32 UTF16 字节长含 NUL, UTF16LE 内容含 NUL)`。

## 7. 自定义消息（TVarList）

### 7.1 编码（S2C，messages.go:195-311）

外壳 `0x1E`（旧）或 `0x27`（现代别名，同 receiver）。`op | u16 值数 | 值*`。
S2C 值类型标签：`2=int32(4B) 3=int64(8B) 4=float32(4B) 6=string(u32 长含 NUL+NUL) 8=object(u32 obj+u32 owner)`。
C2S 解析器另接受 `5=double(8B) 7=widestr`（custom_c2s.go:106-204）。

- **字符串命令路由**（serverModernCustomStringMessage）：TVarList[0]=命令名字符串（如 `"action"`），注册表槽位**不是** wire ID。
- **数字路由**（serverCustomIntMessage）：TVarList[0]=int32 消息 ID，**合法域 [1,764]**。仅用于已在客户端验证注册与参数契约的 C++ GameMessageHandler 路由；**原生对象状态（如 Buffs）禁止走它**。

### 7.2 数字 ID 换算公式与已证路由

`注册表槽位 slot = 2×messageID + 15`，即 `ID = (slot-15)/2`（player_progress.go:611-619 三处独立锚定：397→191、101→43、781→383）。

| ID | 路由 | 已证参数 |
|---|---|---|
| 20 | 技能动作（sub_129C0330，槽 55/56） | `[2]施法者 obj, [3]技能 ConfigID 字符串, [4]目标 obj, int 1, float 1`（后两个可选，给全可保位移/时序） |
| 18 | 通用 DoAction（与 20 不同的旧路由） | — |
| 414 | SwitchManager 开关（槽 843，sub_11044B98） | `("reset", 开关ID 1223, 1)`。**"sync" 会清空整棵开关树，必须用 "reset"** |
| 191 | faculty（槽 397 → custom_faculty_msg → faculty.on_msg） | `(子命令, arg2)`，如 22 开演武卡页 |
| 43 | NPC 短气泡 on_npc_talk（槽 101） | `("obj-owner" 标识串, 文本ID, stage=0, mode=0 仅气泡)` |
| 383 | 剧章提示 on_begin_drama（槽 781） | `(章号int, 章名串, 探索int, 挑战int, 简介串, 详情串, ...)` |
| 4..8 | 面对面菜单 mark（custom 表从 1=custom_sysinfo 起） | 菜单项 mark **是 4..8，不是对象槽 49..53** |

**字符串 "action" 命令**（sub_129C0E90）：`("action", 目标 obj, 动作名字符串, "1")`；读 TVarList[2]=目标 [3]=动作。

### 7.3 C2S 自定义消息 ID（TVarList[0]，0x0A 信封或 0x1E/0x27 直发）

| ID | 含义 | 参数（类型实证） |
|---|---|---|
| 200 | 设置快捷栏 | (index, kind 字符串, configID 字符串) |
| 201 | 删除快捷栏 | (**可见快捷栏索引**，非行号——shortcut_game.lua remove_shortcut(index) 全调用点 + 52905 真机抓包 `[201, 209]` 实证；Go 源码形参名 row 是误读) |
| 211 | USE_SKILL 请求 | (configID 字符串, f32 X, f32 Y, f32 Z, [f32 朝向], [目标位置...]) |
| 215 | 装备内功 | (configID) 只接受已学 View=43 书 |
| 216 | USE_QINGGONG | (id 字符串, int begin=1/end=0)。**只在 begin 扣点**，end 再扣=一次按放双倍耗 |
| 217 | ACTIVE_QINGGONG | (id 字符串)。ActiveQGSkillRec 行授权高阶轻功，与 216 分工：216 耗资源 |
| 218 | 主动格挡 | (subtype, arg)。枚举未复原；本地服每请求给 600ms 格挡窗 |
| 240 | 打坐开关 | (int 1 开始/0 停止/2 被打断)。**重复 1=按住不是切换**；2 必须当正常停处理否则战斗打断瞬间断连。52905 抓包实证：现代客户端停止恒发 2（全程无 0）且**每次打断连发两帧 2**——首帧回 188 属性全量 0x10（LogicState 为首属性、唯一变更项），第二帧重复已停=零帧静默（Go duplicate ignored 同款）；进入 1 同样回全量 0x10。zs_default_01 恒走 240 不走 211 |
| 307 | 仓库（子消息 4） | — |
| 1006 | 内功面板总线 | 1 选书 / 11 普通内修开始 / 21 演武开始 / 22 关闭 / **23 三选卡（两个 double:0/double:1 枚举值）** / 24 刷新 / 14 升级通知 |
| **30** | **MOVEITEM 移动物品**（穿/脱装备、页间移动） | `(fromView, fromSlot, toView, toSlot)`。脱下=`[1,槽,121,0]`（toSlot=0 自动选格）；穿上=`[121,格,1,身体槽]`。menu2.exe 证据：`MOVEITEM (%d,%d)->(%d,%d)` / `MOVEITEM addtoolbox anchor not found`（扩容包链）；**不承载"使用"语义** |
| **31** | **USEITEM 使用物品**（右键） | **恰好 4 值** `[31, view(int), slot(int), count(int≥1)]`；3/5 值或 count=0 拒（`reject malformed native item use values=%v`）。按物品类型分流：装备→右键穿（链成员 `cmpl $0x1f` 自检 msgId==31）、风物志卡片→收录（`USEITEM fwz card %s (view=%d pos=%d)`）、食物/秘籍/坐骑→各子链 |
| **886-889** | **一键穿 OnestepEquip** | `(uid 字符串)`，uid=ViewAdd 下发 `MD5(roleId:bag:slot)` 前 8 字节 hex；未命中 `onestep equip uid=%s not found in bags` |

**枚举可能是 double**：1006/23 实测发 `double:0, double:1`；接受 int32/int64/float/double 的精确整数值，分数/NaN/越界拒绝（custom_c2s.go:29-57）。

### 7.4 1006 面板时序要点（player_progress.go:418-831）

- FacultyState 只有 **0=idle / 2=converting** 可渲染；发 READY=1 会把控件留在 XML 默认态（曾导致必须先点一次演武）。FacultyStyle 1=普通 2=演武。
- 升级链顺序：先刷 View=43 对象→角色属性→原生 191 子命令 14（on_msg_level_up 读已同步 CurLevel）→**最后**关活动页（exit_flag 才能压掉过期 1006/22 回调）。
- FacultyName 面板绑定晚于 1006/1 回复：重开时先发一次清空帧再回真名（facultyNameResetFrame）。
- 非内功 ConfigID 的 View=43 选择是正常流量，**不能断连**；关闭面板滞留的 SUB_CLIENT_ACT_PAY 是过期业务请求也不是断连理由。

## 8. 登录→进世界完整时序（main.go handle()）

初始链：
```
C 0x02 login ──────────► S 0x04 角色列表(有/无角色)
C 0x03 worldinfo ──────► S 0x05 (五个开放书)
C 0x04 choose ─────────► S 0x09 属性表(99+追加)
                          S 0x0A 记录表(6 张)
                          S 8× 0x15 空背包视图
                          S 0x0B EntryScene
                          S 0x0D 玩家对象(1-1) + 0x1F + 0x10 权威生命帧
                          (NPC 只登记不发送!)
C 0x09 ClientReady(1B) ► [屏障] grant QingGongRec 行+View46+任务位图
                          + 打坐快捷 + View43/View40 + 0x10 vitals
                          + 0x27 msg414 开关 1223
C 0x0A 首个 activity ──► [第二屏障] 物化 NPC + 视口 reconcile + 巡逻启动
```

**屏障原因**：加载场景会清客户端原生对象表，ClientReady 前发的对象**闪现即被移除**（main.go:740-743）；且初始 0x0A 早于原生 stage 构建完成，只有显式 stage_main 0x09 之后 QingGongRec/View46 才存活（main.go:835-837）。

兼容分支：现网客户端有时**省略 1 字节 0x09**，入场链后直接发首个 0x0A——把它当显式就绪屏障提升 Phase，不要当成重入（main.go:905-936）。

## 9. 场景切换 0x0C 重入时序（scene_transition.go:78-165）

```
S 0x0C ExitScene
   …等旧 stage 异步 ClearAll 完成（实测 EntryScene 在 0x0C 后 12ms 到达会丢玩家！
     保持网络顺序但延后 0x0B）
S 0x0B EntryScene（0x09/0x0A 表不重发，连接级）
S 0x0D PlayerAddObject（立刻；目标 stage_main 开场景前需要 1-1 对象）
   ——注意：不发 0x1F！
C 早期 0x0A            ► 忽略（属旧 stage 残留）
C 目标 0x09 ClientReady► S 0x1F ServerLocation + 0x10 vitals（0x1F 推迟到这才不会
                          把目的地投影到旧场景）
                       S 恢复清单：QingGongRec 行 + View46 对象 + 任务位图 +
                         shortcut_rec 行 + View43 + View40 + 快捷栏 + msg414
```

**ServerExitScene 会清原生玩家对象的全部附属**：记录行、View 46、View 43 内容。schema 存活但**行必须逐条重发**；`neigongGranted` 这类旧场景标志不得抑制恢复——没有 View 43 时 faculty.lua 收到 1006/1 也解析不了 FacultyName，普通内修按钮保持禁用（不发 1006/11）（main.go:794-825）。

**同场景瞬移 TeleportWithinScene**：只发 0x1F，无 0x0C/0x0B/0x0D/屏障（scene_transition.go:161-165）。

**传送门**：门是直接对象交互非 NPC 菜单；路线只来自已装的 DoorPackageID/door_data.ini 与 GotoDoor PlayerTransferPosition（**同场景门带精确局部 transform 无目标场景**；剧情/副本门未复原故意落空）。零售门在角色**穿过 MoveTarget/SpringRange 时自动触发**（非仅点击），用 X/Z 判定（坡上 Y 有小偏差）+ 短冷却防连环触发（main.go:1006-1045, scene_lifecycle.go:253-257, door_routes.go:70）。

**场景别名归一**（scene_catalog_registry.go:29-34,148-154）：持久化旧值先归一再发客户端：`clone004_wl_1→clone004_wl`、scene09 配置名有描述后缀但地形是 `res/map/ter/scene09`、主门派资源目录不带门派名而 creator 目录带（数据别名非回退）、雁门关实例 ID 兼容。

## 10. Buff 双通道（player_actor.go:61-106, 651-833）

必须**同时**描述同一批 Buff，缺一不可：
1. **BufferListStr**（idx143，窄串）：BufferModule::RefreshBuffersInfo 保留布局，行格式 `ConfigID,UniqueID,Level,OwnerIdent-OwnerSerial,EndTime,EffectType,OverlayRows|`。
2. **BufferInfo1..24**（idx144-167）：FxGameLogic BuffStore 原生 setter 吃的串，**十六进制**字段 `StaticData,OwnerID,OwnerSerial,Level,EndTimeMs,Count`。

- 两者都由同一服务器权威租约生成，游戏性判定（如 qinggong_3 的 `HasBuff("buf_qg_drift")`）走 BufferListStr——**只发 BufferInfo 图标不够**。
- 槽位分配：系统租约占 1..10（红霸体1/水上漂2/踏水跳3/常驻内功4/花神5-6/太极架7-9/明玉闪避10），常规 Buff 复用其 ConfigID 对应槽或从 11..24 分配。**常驻内功不与临时战斗/轻功槽共用**，spawn 与每次 215 响应续 24h 租。
- 到期清理由精确租约匹配（过期定时器只清自己创建的那笔，后来的刷新不被旧定时器误删）。
- Buff 走普通 0x10 属性，**不是 S->C 0x1E 自定义消息**。
- 已证 StaticData：红霸体 8682、buf_qg_drift 216、buf_qg_dst 5786、花神04 15101（隐式重触 15104/35s）、花神05 15105（5s）、太极 6013/6055/6043/6050。

## 11. 数值权威模型（internal/entity/actor.go + player_actor.go）

- **服务器是唯一权威**：客户端只从 0x10 渲染；HPHeartSpeed 等速率字段仅展示，实际恢复只发生在服务器 tick。
- Max 显示：客户端自算 `Max + MaxAdd`；服务器 Actor.MaxHP/MP 是含内功加成的总帽，线值发 Base+RawAdd（player_actor.go:151,156）。
- 打坐：本地规则 10 秒回满 MaxHP/MaxMP（每秒向上取整，不可除尽也能 10 次结清）；死亡/非坐姿不恢复。
- 瘀血层：HitHP 是受击前次级条，战斗中每秒 -10 只收瘀血不回真血。
- 轻功点：官方配置每技能 Consume；本地 10 点/秒回复且**成功动作后低于 50 才回满**（阈值严格 <50）；消耗乘数 float32 有表示尘埃，-0.2×25 必须结出 20 不是 21（actor.go:264-282）。
- SP 怒气：本地满值 100（零售文档 150），只经 setSP/addSP 变更保证 211 检查读到客户端刚看到的值。
- 闪避判定按攻击者+服务器秒确定性掷点，重连不可重掷已定结局（player_actor.go:282-347）。

## 12. NPC/客户端数据链（internal/clientdata + npc_catalog.go）

- 模板表：`npcconfig/world|clone|battle|life` 子目录的 GBK 引号 TSV（`npc_type.txt` 只是分类字典）。分隔行=全空 TSV 单元格，是版式不是数据。
- creator：每场景 `file.ini` 声明 creator 集；`eventtrigger.xml` 的 `<triggercreator>` **不是** NPC creator（无 id/x/y/z）。file.ini 声明了但包里缺的可选 creator（如 AttackNpc_Creator.xml）只有 stat 失败才算致命。空 id+amount=0 的占位条目跳过。
- **ScriptClass 第一道闸**：AttackNpc/BossNpc/CommonNpc 同发一个 Select 包但处理不同；**EventNpc 无任务归属时整个不物化（不发 0x0D）**——它是事件触发器不是场景居民。
- 交互路由：creator 值覆盖模板值（实例级覆盖共享默认；空串不抹默认配置）。Npc_Func 旧表只留离线审计（成都绑定与现网 ConfigID 不匹配）。
- 菜单 mark 是服务器本地路由 ID，故意不复用旧 FuncID 数字空间。
- 门派切换 factionProperties：原八派→School、江湖势力→Force、后世门派→NewSchool，伴随字段空串清旧选择（player_actor.go:239-244）。
- FxPackage 虚拟名规则：**ASCII 大小写不敏感，/ 与 \ 是不同字节，不做 Clean**；mount 顺序=遮蔽优先级；解包树物理根是 `<name>.package.files\res` 而 `res` 不在虚拟名内（index.go:1-54）。
- shop.ini 指定 section 下**键 "0" 重复**，常规 INI 读取器只剩最后一条——逐行扫（shop_catalog.go:14-71）。
- Lua 块转换（luacodec）：FxCore 对每个 LoadBlock 独立重置 XOR，头不动；对称变换。
- 场景内 NPC 数量控制：视口半径判 X/Z 距离平方；巡逻默认只动 3 个（百对象齐动客户端卡顿）；敌对对象归战斗 tick 管，巡逻定时器不得并发移动它们。

## 13. golden 对拍与工程要点

- **字节级 golden 导出**（golden_dump_test.go:3）：在 `cmd/protocol-probe` 下 `NINEYIN_GOLDEN_OUT=<dir> go test -tags golden -run TestDumpGolden -count=1`。产出登录链/协商表/背包视图/入场场景/固定时钟玩家对象/ready 相 starter 链（含 0x27 技能开关与 ready 尾 vital 同步的**精确写序**）/NPC 物化/登录身份向量/角色 JSON 持久化场景，Java 侧逐字节断言。
- 会话内单写者：GM HTTP 只投递命令，由游戏会话循环执行——两个 goroutine 绝不向同一连接交错写帧；回执 buffered（浏览器断开也会话循环仍能写完）（gm_panel.go:17-72）。
- 未就绪/已就绪流量区分靠 HTTP 侧与游戏侧分离 trace（旧面板页在服务器重启后仍活着）。
- 角色存储：账号域 JSON（开发后端）+ MySQL（生产）；稳定 ID 与 CAS 乐观版本并存；旧单角色 roles.json 兼容读（`Name != ""` 即存在）。

## 14. 坑清单（复刻必读浓缩）

**传输/并发**
1. 每帧新建 bufio.Reader 丢预读字节；帧中途读超时丢半帧。
2. 读循环独占 goroutine + channel 复用 GM；双 goroutine 写同连接=交错帧。

**时序**
3. ClientReady 前发对象=闪现即删；首个 0x0A 可能早于原生 stage 完成，只有显式 0x09 后记录/View46 存活。
4. 0x09 会重复（幂等）；0x0C 后早期 0x0A 是旧场景残留必须忽略。
5. 0x0C→0x0B 间隔：实测 12ms 会丢玩家（旧 stage ClearAll 竞态），先等清理再 EntryScene。
6. 0x0D 在 0x0B 后立刻发（stage_main 要 1-1 对象才开场景），0x1F 压到目标 0x09 后。
7. 场景切换后必须重发：记录行、View46、View43、快捷栏、msg414——ExitScene 全清了。

**编码**
8. 属性档案只有索引无名字——顺序冻结，只可尾部追加。
9. record 列类型 7 是字符串，6 是 double（会把串当数读掉行）。
10. Task_Completed 只追加：目标位所在行之前的所有行（含 0 行）都要发。
11. 0x12 删行恰 14B，删的是 0 基记录行号。
12. 0x11 的 +0x0C/+0x0E 是源行 start/count（恒 0/1），不是列区间。
13. TVarList 枚举值可能是 double（1006/23）；字符串命令的注册槽位不是 wire ID。
14. custom ID 域 [1,764]；数字路由仅用于已验证 GameMessageHandler，Buff 等原生状态不走 0x1E。
15. msg414 用 "reset" 不用 "sync"（sync 清空整棵开关树）。
16. UTF-16 长度前缀是**字节长含 NUL**；0x05 世界信息串**无**长度前缀（NUL 结尾）。
17. object 属性值 8B=低字对象|高字 owner。

**客户端行为依赖（协商缺失≠默认值）**
18. NeigongPKStatus 无 HasProp 保护被读；Gravity 只在协商存在才应用；DropHeightPub 缺省 5.0→普通下坡变跌倒；LandRushDist 缺失按 0；CanUse 缺失图标灰。
19. 门派三选 Force>NewSchool>School，后两者必须协商，否则后期门派显示"无门派"。
20. BufferListStr 与 BufferInfo 必须同源同发。
21. 67B 之外的 C2S 运动包含目的地/速度假元组，不可入库。
22. 216 只在 begin 扣点；240 重复 1 是按住；240,2 是被打断必须正常停。
23. 视口用 X/Z 距离；门触发 X/Z+冷却。
24. 商店 View61 数据全由服务端 ViewAdd 物化（客户端不读 shop.ini）。
25. EventNpc 未绑定任务不物化。

**数据**
26. shop.ini 重复键 "0"；NPC 模板 StopTime 取第二次出现（物理列 87）；NpcType 是 int32；GBK TSV 有引号内换行。
27. 内功书同 QName 双记录：经典可见记录 vs mind_ 隐藏记录（Visible=0），一律用经典可见路（ng_wd_001/SD5、ng_yh_001/SD114、ng_th_001/SD113，勿用 1113 等 mind_ 值）。
28. FxPackage 名字 ASCII 折叠大小写、斜杠方向敏感、mount 序遮蔽。
