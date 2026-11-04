# GM 命令接线指南（修为/修速数据模型 + 全链路速查）

> 2026-11-02 整理：GM 修为三动作（set_faculty / set_fill_speed / fill_current_neigong）落地时
> 探索沉淀。下次给 GM 面板加新动作，直接按本文「三步接线」走。

## 1. 全链路（浏览器 → 游戏主循环）

```
console.html  command({action, ...params})
  ↓ POST /api/sessions/{id}/command
GmHttpServer（gm-web 进程，面板 127.0.0.1:19080）
  ↓ GmRequestHandler.proxyCommand：剥 action 键，其余键并入 params
SummerGameServerRpc.call → ClientMgr 动态注册（cluster=Game, StringLine）
  ↓ summer RPC
GmRemote.command(sessionId, action, params)      [游戏服 minor.gm 127.0.0.1:19081]
  ① validateCommand 白名单 + 数值域 → 失败立即 ok=false（不入队）
  ② GmHub.enqueue（按 sessionId 的 ConcurrentLinkedQueue）
  ③ command.awaitReply(2s) 阻塞等回执
  ↓ （游戏主循环 1s ticker）
LoginFlow.startFacultyTicker → drainGmCommands（与 facultyTick/meridianTick 同线程，天然串行无并发）
  → applyGmCommand switch 分发 → applyXxx(...)
       校验 → 改状态 → connection.writeFrame 推客户端 → persist 落库
       → gmHub.update(快照/lastAction) → command.succeed(回执文案)
  ↓ 回执沿 RPC 原路返回；游戏侧 ok=false → HTTP 409 {"error"}
```

关键类：

| 环节 | 文件 |
| --- | --- |
| 白名单/数值域 | `nineyin/src/main/java/com/nineyin/server/gm/GmRemote.java` `validateCommand`（~L109） |
| 队列/快照 | `nineyin/src/main/java/com/nineyin/server/gm/GmHub.java` |
| 命令分发+落点 | `nineyin/src/main/java/com/nineyin/server/game/login/LoginFlow.java` `applyGmCommand`（~L489） |
| 前端面板 | `gm-web/src/main/resources/nineyin/gm/console.html` |

## 2. 新动作三步接线

1. **GmRemote.validateCommand** 加 case（动作名 + 参数数值域；`intRange/floatRange/nonNegative`
   辅助方法现成）。不挂会被入口直接 `unsupported action` → 409。
2. **LoginFlow.applyGmCommand** 加 case + 新 `applyXxx` 方法。模板六要素
   （参考 `applyZhenqiCap` / `applyFacultyValue`）：
   1. `command.intParam/floatParam` 解析并二次校验；
   2. 改状态机（PlayerProgress/PlayerMeridian/PanelState…）；
   3. `connection.writeFrame(session.player.vitalUpdate())` 推 0x10 全量（属性按名写，
      索引由官方 0x09 表解析，勿手写索引）；
   4. 落库：修为类 `persistFaculty(session)`（faculty.json + role_faculty 双源）；
   5. `gmHub.update(id, s -> { s.xxx = …; s.lastAction = 文案; })`；
   6. `command.succeed(文案)`（fail 同理）。
3. **console.html**：`<section>` + h2 标题 + input/button；JS 函数调
   `command({action:'xxx', ...})`；把 h2 标题加进 `GM_GROUPS` 对应组 items
   （不加会落「其他」组兜底，不会失联）。面板需要下拉数据时在 gm-web `GmCatalog` 加目录接口。

注意：动作必须「双端接线」——只加 case 不挂白名单 → 入口 409；只挂白名单不加 case →
游戏侧「动作未实现」回执。

## 3. 修为 / 修速数据模型（本次探索结论）

### 两个"修为"，别混

| 概念 | 字段 | 属性下发 | 说明 |
| --- | --- | --- | --- |
| 玩家修炼点总量 | `PlayerProgress.faculty`（int，种子 9_999_999） | `Faculty@798` | 内修每分钟消耗 `ceil(修为增量/2)`；演武不扣 |
| 单本内功本层修炼度 | `Book.fill / Book.total` | `CurFillValue@764 / TotalFillValue@765`，层数 `CurLevel@763` | 攒满 total 升一层 |

官方属性表配套族（`nineyin/src/main/resources/nineyin/protocol/official_property_table.txt`）：
`Faculty@798、MaxFaculty@799、HoldFaculty@800、CurMaxFaculty@802、CurFaculty@2168`——
服务端目前只发 Faculty。

⚠ 代码注释 `PlayerProgress.java` 早期写的 "Faculty(757)" 是错的（757=AvatarWinCount），以官方表 798 为准。

### 修炼速度 = 基础值 × 倍率（2026-11-02 拆分）

- `fillSpeedBase`（默认 200/min = `NORMAL_FACULTY_FILL_PER_MINUTE`）→ 下发 `NorFacultyAdd@774`（演武发 0）
- `fillSpeedMultiplier`（float，默认 1.0，GM 可调 0.1–100）→ 不直接下发
- 实际修速 `fillSpeed = round(base × multiplier)` → 下发 `FillSpeed@773`（客户端按值分档 ui_train_speed_0..5）
- 官服实证 FillSpeed=516（=200×2.58），故倍率必须支持小数
- 生效时机：普通内修态（CONVERT+NORMAL）改速即时重算；停修/演武态只存参数，下次开修生效；
  演武恒 0（单卡奖励 20000 不走分钟）
- 持久化：`role_faculty.snapshot` JSON 的 `fill_speed_base / fill_speed_multiplier` 字段
  （旧档缺省回退 200/1.0；`fill_speed` 本身原样恢复不打折）

### 内修状态机要点

- `advanceFaculty(now)`：整分钟结算，余数留在 lastAdvance；升级曲线当前**硬编码**
  `total += 200、maxPower += 20`/级——`wuxuelevelinfo.ini` 曲线**尚未接入**
  （测试夹具 `wuxuelevelinfo-mini.ini` 已备但无加载器，接线时注意）；
  满级 → fill=0、自动停修（state=NONE、speed=0）
- C2S：`215`=装备内功、`1006` 子命令（1 选书 / 11 普通开修 / 21 演武 / 22,12 停修 /
  23 演武卡 / 24 表单刷新 / 31,32 经脉透传）——`FacultyCustomHandler`
- 存储：`FacultyStore`（`data/faculty.json` 主档 + MySQL `role_faculty` JSON 快照，DB 失败不回滚文件）

## 4. 帧下发速查（修炼域）

| 帧 | 构造 | 时机 |
| --- | --- | --- |
| 0x10 全量对象属性 | `player.vitalUpdate()`（含 progress.properties：Faculty/State/Style/Name/Place/Fill/Total/Level/FillSpeed/NorFacultyAdd/LiveGroove 族/HoldFaculty/TeamFaculty 族/ActDayValue 族…） | 每次 GM 修改/内修操作后 |
| 0x18 View43 书行 | `progress.bookFrame(slot)`（16 属性；**Level 是 byte，视图域 1465，勿用 CurLevel 索引**） | 升级/选书/ticker 变化时，**先 0x18 后 0x10**（面板先重读等级） |
| 0x1E msg191 | `FacultyCustomHandler.facultyMessage(sub, arg2)`：14=升级（arg2=满级标记）/ 22=演武开始 / 23=退出 / 24=续开卡 | 升级必发 14；演武链 22→(14)→23，绝不出 24 |

## 5. 动作清单（2026-11-04）

已实现（白名单+case 双接线，31 个）：
`set_move_speed, set_gravity, set_silver, set_guanyin, set_gold, set_sp, add_sp, fill_sp,
reload_scripts, set_zhenqi_cap, set_power_level, set_alignment, set_vitals, set_five_attributes,
wear_title, cancel_title, set_faculty, set_fill_speed, fill_current_neigong,
add_livepoint, set_hold_faculty, set_live_speed_power,
set_act_day_limit, clear_team_fatigue, set_team_faculty_last,
switch_scene_preset, switch_scene, teleport, grant_item, send_test_mail, giftbox_merchant_here`

修为三动作参数：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_faculty` | `value` 0..2e9 | role_faculty 快照 |
| `set_fill_speed` | `base` 1..1e6 + `multiplier` 0.1..100（float） | role_faculty 快照 |
| `fill_current_neigong` | 无参（当前修炼书本层填满，升一层） | role_faculty 快照 |

演武碎银/团练疲劳三动作（2026-11-03）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_act_day_limit` | `value` 0..999999 两（**0=不限制**；重设即清今日已耗/已用） | role_faculty 快照 |
| `clear_team_fatigue` | 无参（TeamFacultyValue 归零，客户端剩余精力恢复 100%） | role_faculty 快照 |
| `set_team_faculty_last` | `value` ≥0（团练剩余可抵扣修炼度 TeamFacultyLast） | role_faculty 快照 |

完整协议/单位/帧序/坑记录见 `docs/act-faculty-protocol.md`（2026-11-03 权威）。

传送三动作（2026-11-04，Go sceneRuntime 移植）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `switch_scene_preset` | `preset` 键 ∈ GmScenePresets 107 键（Go 表 38 + menu2 exe 提取 105 的并集，覆盖 console.html 全部按钮） | roles 位置（版本沿用当前值） |
| `switch_scene` | `destination` = `config,resource,x,y,z,orient` 免费格式 | 同上 |
| `teleport` | `position` = `x,y,z,orient`（同场景，仅 0x1F 不读图） | 同上 |

弹出游戏 GM 面板（2026-11-04）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `popup_gm_panel` | 无参 | 无（单帧下发） |

单帧 0x27 现代壳**命令名寻址** `close_gmcc_form` msg_id=2 —— 客户端 lua
`custom_handler.lua close_gmcc_form` 分支 msg_id==2 → `form_gmcc_main.open_gmcc_chat()`
显示 GMCC 聊天面板（msg_id=1 为关闭分支）。取证来源：`res1/lua64` 客户端脚本。

洗练六动作（2026-11-04，`RecastCatalog` 四池 + proppack 词缀包）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `recast_slot_prop` | `slot`（随机洗练：整池重掷全词条，保留官方重复权重） | role_equip_items |
| `recast_slot_dire` | `slot`（定向：同属性族档序列内随机重掷数值，不跨族/不跨内功系） | 同上 |
| `recast_slot_polish` | `slot`（抛光：同序列取最高档=行末位） | 同上 |
| `recast_slot_skill` | `slot`（招式开光：仅 senior_weapon 池词条随机重掷，其余保留） | 同上 |
| `recast_add_affix` | `slot` + `packIDs`（1..12 个 proppack 包号）+ `affixKind`(prop/skill) | 同上 |
| `recast_clear_affix` | `slot`（清空普通+招式全部词条） | 同上 |

池选取：装备 ConfigID 在 senior_weapon_* 有精确节 → weapon 池，否则 senior_prop_*；
无精确节按家族前缀（去尾部 `_数字` 段）回退，仍无才兜底整池。词条串 propBuilt
（"1,id;1,id"，PanelState 同方言）；落地走 upsertEquip + pushWearFrames
（装备视图 + 战斗加成 + vital 整层重刷）。`recast_add_affix` 的包号入口即面板
`/api/recast-affixes` 两组列表（prop=普通词条 / senior_weapon=招式词条口径）。

场景生成两动作（2026-11-04，`GmNpcFactory` 模板克隆 + 900000+ 对象号段）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `spawn_dummy` | `dummyHp` 可选（0=默认 10 万，≤1 亿） | 无（会话内实体） |
| `spawn_chest` | 无参 | 无（会话内实体） |

- **木桩** = worldnpc/attacknpc.txt `monv010100`（官方「木桩」AttackNpc）克隆：
  玩家身前 2.5 米（朝向按 0x26 motion orient），MaxHP/HP 覆盖、移速 0；AttackNpc →
  可选中/受击/AoE 命中（FightState 按帧内 MaxHP 物化），死亡走战斗一期语义
  （不删对象、15s 回满血）。
- **宝箱** = worldnpc/monsternpc_commonnpc.txt `stormbox_10`（CommonNpc）克隆改名
  「镀银宝箱」：点击（0x07 FuncID=0，openNpcDialog 的 gmSpawns 分支）→ 随机拾取
  一件入背包（可穿戴 ∪ 开箱类 ItemType 2102/2103）→ unregister + reconcile
  （0x0E 消失）。拾取词缀暂不附（背包行无 propBuilt 通道）。切图随 world.begin
  清空（session.gmSpawns，两处 begin 站点同步清）。

模板生成三动作（2026-11-04，60501 家族基座 + 官方洗练池词条）：

| action | 参数 | 词条构造 | 落库 |
| --- | --- | --- | --- |
| `gm_generate_weapon` | `weaponType` 101-108/122 + `crafter` cook/smith | 厨师 6 条属性 / 铁匠 3 属性+3 招式，全取 `RecastCatalog.randPool/weaponPool`（senior_weapon_rand 按武器家族节）；池缺失回退全池随机并回执注明 | role_equip_items（立即穿戴） |
| `gm_generate_armor` | `armorSlot` helmet/amulet/cloth/pants/leg/shoes/wrist/ear/ring | 固定 5 条红词条（PhyDefAdd+46/+47、StaAdd+29、MaxMPAdd+39、StrAdd+35），按 (属性名,数值) 从 proppack 定位包号（多包命中取最小号，未命中跳过并计数）；饰品（项链/耳环/戒指）也有穿戴槽，同款词条直接穿 | 同上 |
| `gm_generate_bracer` | 无参 | 固定 3 条臂力（StrAdd +33/+35/+35）+ 3 条招式词条（按身上已穿武器 `weaponPool`，未穿则省略并回执注明） | 同上 |

模板挑选 `pickTemplate`：EquipType+性别过滤后 **`_60501` 家族（51 级金色生活技能
基座）优先随机**，无家族候选才回退品质降序头部窗口。词条随装备 propBuilt 落库并
整层重刷（upsertEquip + pushWearFrames）；槽位未证类型兜底入背包（词条不随行）。

配套修复：`EquipAffixCatalog.loadAll` 此前丢弃 `loadIni` 返回值导致 `load()` 恒空表
（recast_add_affix 包号校验与装备词缀加成累积全链失效），已修（解析结果并入实例）。

物品发放一动作（2026-11-04，面板「所有道具与换装」区块；`add_item` 为别名）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `grant_item` | `itemConfig` + `amount` 1..9999 + `wearNow` 可选 0/1 | wearNow=1 穿戴 → role_equip_items；否则/降级 → role_bag_items |

- wearNow=1 且 EquipType 可穿戴 → 直接穿戴（upsertEquip + pushWearFrames 纸娃娃行/外观 0x10/vital）。
  **目标槽被占 → 旧装备让位**（freeBagSlot 装备页首空格 + swapEquipSlot 回收进背包并推
  0x19/0x18 行帧；满包整单拒绝、数据零改动——旧版直接覆盖丢旧装已修）。
  **双槽饰品（耳环 11/12、戒指 14/15）优先空槽**（equipSlotFor，与 wearFromBag 同语义）。
  不可穿戴（EquipType 空/未知槽 0）自动降级入包，回执「已发放」。

礼包与物品两动作（2026-11-04，面板「礼包与物品」区块收尾）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `send_test_mail` | `itemConfig` + `amount` 1..9999（与 add_item 同域） | data/letters.json + role_letters 快照 |
| `giftbox_merchant_here` | 无参（人形商人拉到身前，重复拉先摘旧；切图随 world.begin 清空） | 无（会话内实体） |

- **邮件域速查**：邮箱=玩家记录表 **RecvLetterRec（官方 447 表 idx 319，16 列）**，
  行帧走 `letterRowFrame` 全 16 格。列型：0/3/4 宽串、1/5/6/7/10/11 窄串（金/银/交易金
  是数字**窄串**，客户端按串比较 "0"）、2/9/12/14 i32、8/13 i64（DATE=unix 秒）、15 f64。
  系统信 type=101..199（发件人空串 → 客户端显示「系统邮件」）；附件=窄串 XML
  `<Root><Object Config="id"><Prop Amount="n" ColorLevel="0"/></Object></Root>`。
  C2S：350 开邮箱/358 关窗/357 勾选（零服务端动作，列表=data_binder 绑记录行）、
  353 读信置已读、359 领附件（grantBagItem + 清附件）。行更新=0x12 删行+0x11 尾插
  重推（信件跳列表底部，不碰未验证的 0x13 单格帧）。重登/切图重放走
  `grantOncePerConnectionRows`（lettersGranted 标志，与快捷栏同口径）。
- **列型取证补全**（bin64 `fxnet2.dll` RecvRecordRow @0x1060BC40 跳表九分支）：
  type6=f64、**type8=宽串（u32 字节长含尾宽 NUL + UTF-16LE + 0x0000）**、type9=u64
  object；`RecordMessages.recordAddCells` 已支持全 1..8 型。
- **礼包商人**：CommonNpc 模板 `citywar03_npc_010`（monsternpc_commonnpc.txt 人形）
  克隆改名「礼包测试商人」，点击（0x07 → openNpcDialog gmSpawns.merchant 分支）直达
  **合成商店节 `gm_giftbox`**（shop.ini 之外；商品=ItemUseCatalog BOX 语义且 DropID
  ∈ drops_mvp.json 的真正可开礼包，按 configId 排序，SellPrice2=0 免费领，msg70 购买
  链零改动复用）。

切图链（`applySceneSwitch`）：0x0C 退场 → `world.begin` 世代重置 → **750ms 调度延迟**
（旧场景 on_exit_scene 的 ClearAll 收尾；Go 在会话 goroutine 睡，Java 在 faculty ticker
上不能睡改 `scheduler.schedule`，`LoginFlow.reentryFrameDelayMillis` 测试可注入）→
0x0B 进场 + 0x0D 玩家归档。NPC 目录按目标场景重载（注册不上线）。收尾在目标场景 0x09
（`completeSceneReentry`）：补延迟 0x1F+0x10 → Ready/Activity 推进 + NPC 物化 →
0x0C 清掉的玩家挂接行整链重放（复位 qingGong/neigong/meridian/skins/fashion 五标志走
`grantOncePerConnectionRows`；customizing/keybind 是连接级属性帧不重发）→ 撤屏障。
窗口内残余 0x0A 一律忽略；`reentryFramesSent` 前的 0x09 也是残余（Go 靠阻塞读循环排队，
Java 靠标志位）。表数据再生成：`tools/smoke/extract_gm_presets.py` +
`merge_gm_presets.py` + `gen_gm_presets_java.py`（menu2_preset_full.tsv 中间产物）。

演武碎银链（客户端语义 lua 钉死）：主面板"可用碎银" = (ActDayValue−ActCostSuiYin)/1000 两
（毫单位）；演武页两标签依赖 switch 2812（本服不推 → 恒显"不限制"）。打卡 1006/23 pay=1
按 `faculty/actfacultyinfo.ini` 卡基价（**文**，原值勿乘 1000——乘错会导致碎银余额判不足全部静默拒绝）扣 CapitalType1 并入账
ActCostSuiYin/ActDayValueUsed；有限额（max>0）超限或碎银不足 → 静默拒绝（零帧零变更）；
pay=2/4（官银/银票）本服未建档，暂免扣只发奖。团练疲劳 = TeamFacultyValue（0..80000，
faculty_team_action.ini [setting] TeamValue），客户端按 (80000−值)×100/80000 反算剩余精力%。

前端 backlog（console.html 有按钮、服务端**未接线**，点击 409，接线时按第 2 节走）：
`learn_all_neigong, learn_all_taolu, forget_all_taolu, grant_all_cards, unequip_card,
set_faction, set_jingmai_tier,
worldboss_*, bot_*, equip_card, ninjiuzhi_*, set_pk_mode,
set_weather, story(剧情族剩余), custom_probe, keybind` 等。
（add_item 与 story('giftbox_merchant_here') 已于 2026-11-04 接线。）

五维属性一动作（2026-11-04，面板「五维属性」区块）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_five_attributes` | `attrStr/attrSta/attrDex/attrIng/attrSpi` 各 0..1e6（**0/缺省=跳过该维**，覆盖式非累加） | role_faculty 快照（`base_str..base_spi`） |

- 基值落点 = `PlayerProgress.attributes`（create 种子全 30）；0x10 帧的 Str/Sta/Dex/Ing/Spi
  与 HP/MP 换算族（7×体魄+2×臂力、4×内息+罡气）都从基值派生，改值即 `vitalUpdate()` 全量刷新。
- 快照新增五字段（`base_str/sta/dex/ing/spi`）走 FacultyStore 双源；**旧档缺省 0 不覆盖运行时值**
  （restore 守卫 >0，上限钳 1e6），26 参旧契约构造保留。
- 测试：`LoginFlowGmCommandTest.fiveAttributesApplySkipZeroAndPersistSnapshot`（0 跳过/0x10 派生值
  =基值+书加成/全 0 拒绝）+ `FacultyProgressTest.baseFiveAttributesRoundTripAndLegacySnapshotKeepsSeeds`
  （快照回环/旧档不动基值）。

生存属性一动作（menu2 setVitals，2026-11-04 补落库闭环）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_vitals` | `ene/maxEne/resumeEne/sat/maxSat/pkValue/drunkLevel` 各 ≥0（**均可选**，缺省不动；面板的 maxEneAdd/maxSatAdd 本服表无此名忽略） | role_faculty 快照（嵌套 `vitals` 节） |

- 体力/饱食/杀戮/酒量 = PanelState 身份块七字段；改值 → 0x10 单帧（按名走官方表：
  Ene@705/MaxEne@706/ResumeEne@707/Sat@708/MaxSat@709/PKValue@427/DrunkLevel@723）。
- **2026-11-04 前不落库**（重登回 0）已修：`PlayerActor.facultySnapshot` 把 `panel.vitalsSnapshot()`
  并入快照（`Snapshot.withVitals`），`applyVitals` 改完即 `persistFaculty`；重登
  `restoreFaculty` → `panel.restoreVitals` 回填。旧档无 `vitals` 节 = null 不动 0 种子
  （新角色仍 0/0，官服种子值无抓包证据不臆造）。
- gmHub lastAction 回显「体力 a/b 饱食 c/d 杀戮 e 酒量 f 已更新并落库」。
- 杀戮红名渲染/酒量效果均客户端按 PKValue/DrunkLevel 本地处理，服务端无附加逻辑。

## 6. 测试

- 回环门禁：`nineyin/src/test/java/com/nineyin/server/gm/GmRemoteRoundTripTest.java`
  ——进程内起真实 summer（main+minor.gm），**Summer.hot 整个 JVM 只允许一次**，
  所有断言必须合在单个测试方法内；consumer 线程 tryPop + succeed 模拟游戏侧回执。
- 分发单测：`LoginFlowGmCommandTest`（含 set_vitals 落快照/回填断言）；修炼域：`FacultyProgressTest`（帧序/结算/GM 填满/修速拆分/生存属性 vitals 回环+JSON 落盘回读）；
  演武碎银/团练疲劳域：`ActTeamBlockTest`（下发块/扣费/超限拒绝/GM/演武 108↔0 线帧回归）；
  传送域：`LoginFlowSceneSwitchTest`（0x0C 重入帧序/残余包忽略/0x09 收尾/瞬移单帧/加载中拒绝，
  ManualScheduler 驱动 750ms 延迟任务）+ `GmScenePresetsTest`（107 键/抽样值/免费格式解析）。
  模板生成域：`GenerateEquipFlowTest`（真实 MySQL+参考服 share：60501 家族/官方池词条/
  固定红词条/护腕臂力+招式/饰品穿戴帧序；临时角色 990033 测后清理）。
  邮件域：`MailFlowTest`（真实 MySQL+go-server1 share：发信帧字节/353 已读/359 领附件
  入包/落盘回读；临时角色 990034）+ `RecordMessagesTest`（type8 宽串/6 f64 字节级，
  载体 RecvLetterRec 319 全 16 列）；礼包货架域：`GiftShopFlowTest`（合成节只列可开
  礼包/排序/免费、View61 头+行帧、msg70 零价购买入包；临时角色 990035）；
  物品发放域：`GrantItemFlowTest`（入包行+成对帧/wearNow 穿戴帧序/换装让位旧装回收
  装备页/双槽耳环 11+12 不覆盖/不可穿戴降级入包；临时角色 990036）。
- 已知存量失败 7 个（View40 CD 显示修复遗留，与 GM 链路无关）：PlayerActorGoldenTest 2、
  LoginFlowTest 1、SkillCastHandlerFlowTest 3、RoleInventoryStoreIntegrationTest 1（需 MySQL）。
