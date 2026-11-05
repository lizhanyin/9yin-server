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

## 5. 动作清单（2026-11-05 晚）

已实现（白名单+case 双接线，82 个 action 名）：
`set_move_speed, set_gravity, set_silver, set_guanyin, set_gold, set_sp, add_sp, fill_sp,
reload_scripts, set_zhenqi_cap, set_power_level, set_alignment, set_vitals, set_five_attributes,
wear_title, cancel_title, set_faculty, set_fill_speed, fill_current_neigong,
add_livepoint, set_hold_faculty, set_live_speed_power,
set_act_day_limit, clear_team_fatigue, set_team_faculty_last,
switch_scene_preset, switch_scene, teleport, grant_item(add_item 别名), send_test_mail, send_mail, giftbox_merchant_here,
spawn_combat_dummy, clear_combat_dummies, worldboss_spawn, worldboss_clear, worldboss_list,
set_faction, bot_ensure, bot_pull_here, bot_follow_start, bot_follow_stop, bot_revive,
bot_support_configure, yufeng_add, yufeng_remove, set_weather, repute_apply,
origin_activate_all,
equip_card, equip_hair, unequip_card, grant_all_cards, grant_all_fashion, set_showequip_type, grant_identity_suits,
gm_generate_weapon, gm_generate_armor, gm_generate_bracer,
recast_slot_prop/dire/polish/skill, recast_add_affix, recast_clear_affix,
spawn_dummy, spawn_chest, mount_ride, idle_stance_set, popup_gm_panel,
learn_all_taolu, forget_all_taolu, enter_clone, reenter_clone, destroy_clone,
set_jingmai_tier, learn_all_neigong, grant_title, clear_titles, save_shortcuts, set_pk_mode`
（2026-11-05 追加 worldboss 三动作、set_faction、bot_* 六动作、御风两动作、场景天气、
repute_apply 与 origin_activate_all；同日晚批再接 set_jingmai_tier/learn_all_neigong/
grant_title/clear_titles/save_shortcuts/set_pk_mode 六动作，见下文「晚批六动作」节；
其余批次——洗练/外观坐骑/生成族等见各节）

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
| `switch_scene_preset` | `preset` 键 ∈ GmScenePresets 142 键（Go 表 38 + menu2 exe 提取 105 的并集 107 键，加踢馆 `tiguan_1..35`：27 项 exe 表原值、8 项参考服 creator 入口 NPC 推导；覆盖 console.html 全部按钮） | roles 位置（版本沿用当前值） |
| `switch_scene` | `destination` = `config,resource,x,y,z,orient` 免费格式 | 同上 |
| `teleport` | `position` = `x,y,z,orient`（同场景，仅 0x1F 不读图） | 同上 |

- 面板「自定义切图与场景内瞬移」区块 2026-11-05 加**场景下拉**：gm-web `/api/scenes`
  目录接口（本地 GmCatalog 读 `share/rule/scenes.ini` GBK 全表 + `stringname.idres`
  中文名，语义镜像 nineyin `SceneNames`——纯数字节 + config 键、同 config 去重），
  可按中文名/资源名过滤，选中自动回填 destination 的 `config,resource` 段（坐标尾段
  保留可改，无逐场景出生点数据默认 `0,0,0,0`；精确坐标仍走 142 预设按钮）。

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

战斗假人两动作（2026-11-05，bak GmActions spawn_combat_dummy 最早版移植）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `spawn_combat_dummy` | `dummyHp` 可选（0=默认 5 万，≤1 亿）+ `dummyName` ≤64 + `dummyDamage`/`dummySkills`（仅记日志不生效，bak 对齐） | 无（会话内实体） |
| `clear_combat_dummies` | 无参（按 combatDummy 标志摘除本会话全部假人，木桩/宝箱/商人隔离） | 无 |

- **假人** = attacknpc.txt `monf022202`「机关木人」克隆（与 bak monm080701 同名同模型
  npc\monster026；monsternpc 表的同名是 CommonNpc 不可战斗，故选 attacknpc 版）：
  AttackNpc → combat 链自动接通——玩家命中后 NpcCombatAi 周期还手（2.2s、恒 1 伤、
  6m；removeGmSpawn 后下一拍自停），死亡不删对象 15s 回满。
- 全链深挖（模板克隆/注册物化/战斗接入/测试模式）见 `gm-command-chain.md`（2026-11-05）。

世界 BOSS 三动作（2026-11-05，官方刷新表驱动 + bossnpc 模板）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `worldboss_spawn` | 无参（当前场景强制生成：门派固定点每点 1 只 bosslist 轮选 + 场景池命中的江湖 BOSS 随机点） | 无（会话内实体） |
| `worldboss_clear` | 无参（按 worldBoss 标志摘除本会话全部 BOSS） | 无 |
| `worldboss_list` | 无参（对象号/名字/血量/坐标回执文案） | 无 |

- **数据源** = `share/npc/` 三张 GBK INI（`WorldBossRefreshTable`，ResourceCache 缓存）：
  normalbossrefreshinfo（门派固定点 scene+position+bosslist）、jhscenebossrefreshinfo
  （江湖 BOSS Scene 随机场景池）、jhscenebossrefreshpoint（Point= 重复键点池）；
  场景号匹配经 `SceneNames.sceneId`（rule/scenes.ini config/resource → id）。
- **模板** = worldnpc/bossnpc.txt（BossNpc scriptClass → 战斗快照 + NpcCombatAi 反击），
  真实血量/名字（GBK）；**击杀不重生**：CastContext.npcKillOverride（bossKillOverride）
  认领 worldBoss 摆放 → 跳过 15s 重生、延迟 300ms removeGmSpawn（0x0E 消失）+ 面板回执。
- 会话级摆放（同木桩/假人口径），切图随 world.begin 清空；定时刷新
  （normalbossrefreshtime 时间窗）、掉落/伤害排行、全服公告与跨会话共享留待后续。

伙伴系统六动作（2026-11-05，面板「伙伴系统」区块；预设闭名单 `GmBotPresets`）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `bot_ensure` | `bot` 可选（预设键，缺省 `qinghe`；闭名单拒绝任意字符串） | 无（会话内实体） |
| `bot_pull_here` | 无参（不在场先按预设生成；在场 0x1F 瞬移到玩家身前） | 无 |
| `bot_follow_start` / `bot_follow_stop` | 无参（跟随开关；start 不在场先 ensure） | 无 |
| `bot_revive` | 无参（FightState 回满清租约 + 0x10 NPC 战斗帧整血下发） | 无 |
| `bot_support_configure` | `enabled` 0/1 + `intervalSec` 1..60 + `heal` 0..100000（均可选，缺省保持现值） | 无 |

- **预设** = `GmBotPresets` 闭名单（仿 `GmFactionPresets`）：`qinghe`「青禾」→
  attacknpc.txt `monf022202`「机关木人」（战斗假人同款、唯一全链验证的人形
  AttackNpc 模板），生成期覆盖 Name/MaxHP 5000/RunSpeed 6.0；辅助缺省技
  `jn_mon053_02`、500 点/5s。
- **战斗语义**：AttackNpc 天然 combat——可被玩家选中/受击/AoE 误伤；但
  FightState 伙伴标记使 NpcCombatAi 跳过（**永不反击主人**），死亡由
  killOverride 接管**冻结不重生**（不吃 15s 满血），`bot_revive` 手动复活。
- **跟随引擎**（`LoginFlow.companionTick`，1s faculty ticker 内、与 GM 命令泵
  同线程串行）：玩家实时位取 `session.pendingLocation`（0x0A motion 包更新）；
  XZ 距离 > 2.5m → 0x21 ServerAllDest 单条目（目的地=玩家位、speed=RunSpeed、
  moving=1），服务端权威坐标按速度×1s 步进（`SceneLifecycle.companionStep`，
  同巡逻口径不动 revision）；到点发一次 moving=0 停步 + 面向主人。
  **0x20 依旧不用**（官方抓包从未出现）。
- **跨场景跟随**：切图/重登 gmSpawns 随 world.begin 清空 → 下一拍 companionTick
  检测世代失效自动在玩家身边重生成（新对象号 + FightState 重标记），本拍只落位。
- **辅助治疗**：开启且玩家掉血、伙伴存活、12m 内、到周期 → msg337+msg20 伙伴
  施法帧（NpcCombatAi 出招同型，目标=玩家）+ `player.restore` 回血 + 0x10
  vitalUpdate 面板帧。
- **面板状态**：`/api/sessions` 每会话新增 `bot` 对象（name/hp/maxHp/following/
  support，GmHub.PlayerSnapshot bot 字段族，`refreshBotSnapshot` 维护）；前端
  `#bot-state` 渲染实时状态（console-core.js `refreshBots`，不再读静态
  `/api/bots` 目录——该目录接口保留未动）。

门派声望一动作（2026-11-05，面板「门派声望（自定义）」区块；UI/JS/读接口
`/api/repute/status` 此前已就绪，本次只接 `repute_apply`）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `repute_apply` | `reputeName`（"all" 或 `repute_*` 阵营键，闭名单=repute_level.ini [Repute] 36 项）+ `reputeMode` set/add（缺省 set）+ `reputeValue` 0..9999999 | role_repute 逐阵营 upsert |

- **闭名单两级**：入口（GmRemote）验 name 非空限长/mode/value 域；游戏侧按
  `ReputeLevelTable`（GBK INI + ResourceCache，WorldBossRefreshTable 同款）校验
  阵营键并列出可用项——阵营集随数据表走，不硬编码。
- **客户端两通道**：`Repute_Record`（官方方言 idx 44，列 [窄串,int32,int32]=名/值/
  等级）行整刷——0x12 倒序删旧 + 0x11 逐行（NewRideRec/CardRec 同方言），等级按
  [Level] 默认曲线/阵营自定义段取 ≤值最高档（级号=INI 键）；`PrestigeExp@848`
  汇总单帧（panel.prestigeExp = min(INT_MAX, Σ行值)）。
- **上限**：[repute_max_value] 特例（wanshou=40 万等）否则 [Level] 最大；set 0 摘行。
- **落库/重放**：role_repute upsert best-effort（失败 warn 不回滚，set_faculty
  同口径）；登录 `loadPanelData` 装载行镜像（prestigeExp 同源求和，不再单独
  reputeTotal），`grantOncePerConnectionRows` 按 letters 口径整表重放（空表零帧，
  golden 无扰动），切图收尾复位 `reputesGranted` 重放。
- 消费方：condition.ini 1706 条 `Name=Repute_Record` 条件（身份/门派内功声望门槛）
  与声望面板；等级列语义与 PrestigeExp=SUM 汇总口径同列「真机待验」。

身份系统一动作 + C2S 三口（2026-11-05，面板「身份系统（一键激活全部）」区块；
origin = 官方「出身/身份」成长体系，1197 个身份，bak SceneFlow 700-706 stub 交棒）：

| action / C2S | 参数 | 落库 |
| --- | --- | --- |
| `origin_activate_all` | 无参（origin.ini 全量入【已激活】集；【未领取】状态不动） | role_origins INSERT IGNORE（0012 迁移） |
| C2S 700 `[700, 身份 id]` | 激活单身份（测试服跳过 ActiveConditions 门；int/窄串两口径） | 同上 |
| C2S 702 `[702, 身份 id]` | 领奖：prize_item 物品入包（grantBagItem，**全部成功才标记**，满包/失败不标记下次补发；config 缺失跳过不阻断）+ prize_title 称号所有权（role_titles INSERT IGNORE，grantTitle 新方法）+ prize_prop `repute_*` 阵营叠加（Repute 行整刷 + PrestigeExp 单帧，repute_apply 同口径）→ Origin_Prized 行 | role_origins status→2 + role_titles + role_repute |
| C2S 706 `[706]` | 请求刷新：按内存镜像重推 Origin_Active/Origin_Prized 全部行 | 无 |

- **数据源** = `share/task/origin/` 四表（`OriginCatalog`，ISO-8859-1 + ResourceCache，
  WorldBossRefreshTable 同款）：origin.ini（数字节名=身份 id，1197 个升序）+
  prize_item（`r=configId,数量` 重复键最多 4 条）+ prize_title（`r=称号 id`）+
  prize_prop（`r=prop,值`，本期只消费 repute_* 阵营）。
- **记录行通道** = 官方 447 表内建：`Origin_Active[3]`/`Origin_Prized[3]`（单窄串列，
  行键=身份 id 数字串，`RecordMessages.recordAddString`）；登录装载 + grantOnce
  重放门 `originsGranted`（letters/reputes 同口径，切图收尾复位，空表零帧）。
- **镜像/落库**：`session.origins`（LinkedHashMap，id→1 激活/2 已领奖）+
  role_origins（0012：role_id+origin_id 主键，status 只进不退）双源 best-effort。
- 701 展示 / 705 领取查询留待（S2C lua 回调 custom_refresh_origin/
  custom_can_get_side_origin 无抓包证据）；originnew 东海身份树/Origin_Record
  等级列语义同留待。/api/origin/status 读接口（gm-web）既有未动。
- 测试：`OriginCatalogTest`（手写四表：数字节过滤/多 r 收集/坏行丢弃/缺根
  present=false）+ `LoginFlowGmCommandTest.originActivateAllAndPrizeRoundTrip`
  （@TempDir 手写最小表全链：全量激活 0x11×N/702 三帧序（Repute 行 0x11+
  PrestigeExp 0x10+Prized 0x11）/重复 702 幂等零帧/706 重推 3+1/700 int 口径
  单激活/目录外拒绝零帧）+ `GmRemoteRoundTripTest` origin_activate_all 透传。

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
| `send_mail` | `roleName` 可选（留空=本会话角色）+ `title` 可选 ≤64（默认「系统邮件」）+ `content` ≤1024 + `itemConfig`/`amount` 可选（与 content 至少填一项） | data/letters.json + role_letters 快照（目标角色键） |
| `giftbox_merchant_here` | 无参（人形商人拉到身前，重复拉先摘旧；切图随 world.begin 清空） | 无（会话内实体） |

**GM 邮件 send_mail（2026-11-05，面板「GM 邮件」区块）**：发自定义标题/正文的系统信，
可带物品附件（领取链复用 359）。投递三档路由（`LoginFlow.applySendMail`）：
① `roleName` 留空或=本会话角色 → 直接 append + lettersGranted 则推 0x11 行帧；
② 在线他人（`findOnlineSessionByName` 遍历 activeSessions，player!=null 即信箱活档已载）
→ 入目标会话 `pendingGmLetters` 暂存，由其自身 1s ticker `drainPendingGmLetters`
合并推帧（避免跨线程直写他人连接）；
③ 离线 → `RoleStoreFacade.findRoleByName`（**接口 2026-11-05 新增**，MySQL 按名查 roles /
JSON 扫 accounts；已删除角色明确拒绝）→ LetterStore load→append→save 直写主档，
下次登录收信。序列号=`gm+毫秒+AtomicLong`（跨目标去重，与 send_test_mail 的 size+1 方案独立）。

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

御风神水两动作（2026-11-05，面板「御风神水（大轻功）」区块收尾）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `yufeng_add` | 无参（挂 buf_ride_yufeng 10 分钟租约；重复点同槽续期） | 无（PlayerActor 内存租约，重登清零） |
| `yufeng_remove` | 无参（msg169 摘除 + 空槽帧；未持有时仍补 msg169 重同步） | 无 |

- **语义**：大轻功（百鸟凌云系列）一/二/三段冲刺的客户端条件包要求 BuffStore 持有
  buf_ride_yufeng。表实证（运行根 `share/skill/`）：buff_new[buf_ride_yufeng]
  StaticData=**10293**（0x2835）、buff_static[10293] **Category=0**（对照缓降
  218→2048 同口径）；官方每 10s 耗 1% 内力、进战斗即摘由客户端 lua
  （buf\ride\buf_ride_yufeng.lua）处理，GM 版不模拟消耗。
- **双通道**（QingGongHandler 缓降同款蓝本）：挂/摘各两帧——msg169 九值
  （`FightMessages.timedBuff`，v0=0 挂/1 摘，v7=到期 Unix 毫秒**原值**）+
  0x10 单槽帧 `buffSlotFrame(slot, …)`（BufferInfoN 槽串 StaticData,1,1,1,
  **到期+11644473600000（1601 纪元）**,1，空串=清槽；两口径勿混）。
- **槽位**：`reuseBuffSlot`(同 configId 续期) → 否则 `freeBuffSlot()`（12..24，
  1..11 系统租约区）；满槽整单拒绝。到期自动摘由 scheduler 延迟任务补发摘除
  双帧（租约槽位+到期一致才动作，续期/手动摘后旧任务空转；worldboss 移除同款守卫）。
- 切图不摘（buff_static LeaveSceneClear=0）：重入场景 0x10 全量面板自带
  BufferInfo1..24，租约天然恢复，无需重放标志。配套四个「发放御风神水
  (3/7/10/14 天)」按钮走 `add_item` 发 ride_windrunner_002/004/003/005 入包
  （官服真实道具路线：右键使用蓄力后才加状态），与本直挂动作互补。
- 测试：`LoginFlowGmCommandTest.yufengAddRemoveRoundTrip`（msg169 九值字节级
  v0/169/窄串、槽串 2835 首字段、续期不换槽、空槽帧、无租约重同步、到期任务
  补发且旧任务空转）+ `GmRemoteRoundTripTest` yufeng 两动作透传。

场景天气一动作（2026-11-05，面板「场景天气」区块；修正旧注释"场景对象 WeatherRec
驱动"的臆测——那是载图期 Weather@1448 属性，仅 scene_weather.ini 5 个主城夜景段）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_weather` | `weather` 键 ∈ GmWeatherPresets 49 键（= 客户端 `share/rule/weather/sceneparastore/` 天气渲染包目录名）+ `fadeSec` 可选 0..600（缺省 10） | 无（会话级单帧） |

- **通道** = S2C 0x27 命令名寻址 `on_weather_sky`（popup_gm_panel 同款），sub_type=4
  → 原生 `weather_manager:ChangeWeatherEffect(目录, 过渡秒, 特效串, 4)`（现代客户端
  weatherconfig.ini [GlobleRule] **IsLua=0** 原生模式；Lua 模式 weather_set.lua
  start_weather 同参数收敛——arg1=TTID 时段目录、arg2=过渡秒、arg3=TXID 特效串空）。
- **闭名单**（`GmWeatherPresets`，仿 GmFactionPresets）：49 键与 sceneparastore 目录
  逐一对齐；`_001`=昼/`_501`=夜变体（SunHeight 实证）；中文标签对齐官方 17 类
  （interface.idres tianqi_1..17）。入口与游戏侧双重校验，不收任意路径字符串。
- **面板下拉** = gm-web `/api/weather` 改读 sceneparastore **目录列举**（旧源
  weathereffect.ini 是粒子定义表、域不匹配已弃用），Label 与九阴侧 GmWeatherPresets
  同口径派生（双端镜像 scenes.ini 先例）。
- 雨/雪粒子需客户端画面设置开天气 + 高/极高画质；天色/雾不受限。**恢复默认 =
  切图重进**（客户端载图重初始化天气；服务端无干净恢复通道——sub_type=5 原生
  语义不可证，不做）。
- 测试：`GmWeatherPresetsTest`（49 键与目录集逐一对齐/标签非空/未知键拒绝）+
  `LoginFlowGmCommandTest.weatherSwitchSendsOnWeatherSkyFrame`（0x27 五值字节级：
  命令名/sub 4/目录/过渡秒/空特效串、缺省 fadeSec、未知键零帧失败）+
  `GmRemoteRoundTripTest` set_weather 透传与参数域拒绝。

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
`keybind,
ninjiuzhi_set/event/reset/status,
scene_task_start/submit_ready/remove, twilight_mechanism_start/remove,
twilight_npc_move_start/remove, twilight_stages_start/remove, twilight_difficulty,
custom_probe, fightword_probe, chat_broadcast, speech_nul, speech_len,
npc_bubble, drama_prompt, red_super_armor, action_state_sgmd/hsqs05/stop` 共 26 个。
（add_item 与 story('giftbox_merchant_here') 已于 2026-11-04 接线；
equip_card/unequip_card/grant_all_cards、equip_hair、mount_ride、idle_stance_set
已于 2026-11-05 随「外观与坐骑」六区块补全接线，详见 docs/坐骑与待机站姿.md；
binglu 已于 2026-11-05 接线，见下文「兵录一动作」节；
worldboss_spawn/clear/list 已于 2026-11-05 接线，见上文世界 BOSS 三动作节；
set_faction 已于 2026-11-05 接线，见下文门派一动作节；
bot_* 六动作已于 2026-11-05 接线，见上文伙伴系统六动作节；
yufeng_add/yufeng_remove 已于 2026-11-05 接线，见上文御风神水两动作节；
set_weather 已于 2026-11-05 接线，见下文场景天气一动作节；
origin_activate_all 已于 2026-11-05 接线，见上文身份系统节；
learn_all_taolu/forget_all_taolu、enter/reenter/destroy_clone、spawn_combat_dummy/
clear_combat_dummies、repute_apply 已于 2026-11-05 接线，见上文各节；
learn_all_neigong、set_jingmai_tier、set_pk_mode、grant_title、clear_titles、
save_shortcuts 已于 2026-11-05 晚接线，见下文「晚批六动作」节。）

晚批六动作（2026-11-05）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_jingmai_tier` | `jingMaiId` + `jingMaiTier` 1..216（游戏侧钳 1..目录上限、fill 归零；帧=View45 levelUp 行帧 + CurJingMai/JingMaiTotalLevel 属性帧） | role_meridian 快照 |
| `learn_all_neigong` | 无参（neigong.ini QName 全目录补书 level 1/fill 0，total/power 取 wuxuelevelinfo r=1 曲线（缺档回退 700/120），varprop 效果 applyModernEffect 回填；已有书不动；View43 整视图重建 0x16→0x15→0x18） | role_faculty 快照 |
| `grant_title` | `titleId`（role_titles INSERT IGNORE 幂等、不动佩戴位；身份奖励链 prize_title 同款，客户端无行帧） | role_titles |
| `clear_titles` | 无参（DELETE 全行 + 佩戴位复位帧 TitleID=0） | role_titles |
| `save_shortcuts` | 无参（内存快捷栏对账落库：补写全部行 + 删内存已无的 DB 行；平时 C2S 200/201 已逐行写，此为兜底修复） | role_shortcuts |
| `set_pk_mode` | `pkMode` 0..8（PKMode@437 单帧；会话态不落库——服务端攻击门控未接，C2S 291 custom_set_pkmode 玩法链接通后改同源状态机） | 无 |

兵录一动作（2026-11-05，独立记录帧域 binglu_rank_rec）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `binglu` | `bingluArgs` = `set <row> <cur> <fill> [max]` / `max [row]`（row 0-11 槽位、cur/max 0-15、fill 0-65535=u16 列宽；max 缺省=不改，无上限行升满按默认 10 补写上限、进度清零） | BingluStore：data/binglu.json 主档 + role_binglu 双源（MountStore 同款） |

- **独立帧域**：`binglu_rank_rec`（官方下标 178，列 [2,2,2]=3×u16：CurLevel/FillValue/
  MaxLevel）。客户端 form_binglu.lua 按**密集行号=槽位** QueryRecord(rows,0/1/2) → 任何
  变更=整域刷新（0x12 逆序删旧行 + 0x11×12 重推，mountRefreshFrames 同款方言，
  `session.bingluRows` 记账）+ 瘦 0x10 MaxBingLuLevel（=各槽 cur 最大值；面板域
  maxBingLuLevel 同步，防后续全量 0x10 回退）。
- **槽位序** = binglubaseinfo.ini 节序（"勿改配置顺序"）：0 徒手/1 单刀/2 单剑/3 匕首/
  4 双刀/5 双剑/6 双刺/7 长棍/8 短棍/**9 ditze(笛，客户端 UI 跳过但行保留)**/10 暗器/
  11 奇门。
- **登录/重入口径（官服对拍）**：零进度角色 ClientReady **0 帧不推**（全部官方捕获均无
  binglu 行，golden ready_chain 1775 帧基线不动）；有进度（DB/JSON 存档）才整域重放。
  0x0C 重入 bingluPushed/bingluRows 复位（玩家挂接行已清，重入纯增推）。
- 未接：943 提管升层 / 984 查看他人兵录 custom 链、binglulimit/expand 配置上限钳制、
  BingLuType 数据源（仍发 0，另案）。

门派一动作（2026-11-05，Go gm_factions.go 闭名单移植）：

| action | 参数 | 落库 |
| --- | --- | --- |
| `set_faction` | `faction` 面板键 ∈ GmFactionPresets 34 键（八大门派 8 + 后续门派 5 + 江湖势力 8 + 宗门 13） | roles appearance[8]（CAS） |

- **闭名单**（`GmFactionPresets`，镜像 Go gm_factions.go 同表）：面板裸键
  （shaolin/yihuagong/huashan…）→ 规范编码（school_/force_/newschool_ 前缀，即
  appearance[8] 与 0x10 School/Force/NewSchool 属性的存储形式），与 modern 客户端
  form_main_player.ini [school_name] 段一致（`FactionNames` 显示名同源）。
  **绝不接受任意字符串**（入口与游戏侧双重校验；原始编码 school_wudang 也不收，只收面板键）。
- **落序**（Go main.go 同序）：saveFaction CAS 落库（失败即回执不动作内存）→
  内存 `player.faction` + 专用单帧 0x10（`PlayerActor.factionUpdate`，仅
  School/Force/NewSchool 三属性，镜像 form_main_player.refresh_role_changed 消费口径）
  → 面板快照 faction + lastAction。
- 不自动赠送套路/身份装/任务线（身份装走 grant_identity_suits 或登录期自动激活；
  重登后 `activateIdentitySuits` 按新 faction 幂等激活）。
- 面板下拉同步补 5 个后续门派（明教/天山/昆仑/刑天城/无虚门）并修正宗门标签
  （神机汇/王回洲/达摩派/念萝坝/青衣阁，与预设表一致）。
- 测试：`GmFactionPresetsTest`（34 键/三段前缀分布/面板选项全覆盖/未知键拒绝）+
  `LoginFlowGmCommandTest.factionSetPersistsSnapshotAndPushesFrame`
  （JsonRoleRepository 真实落库回读/单帧 0x10/快照同步/未知预设零帧）。

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
- 分发单测：`LoginFlowGmCommandTest`（含 set_vitals 落快照/回填断言、称号/快捷栏无库拒绝、
  set_pk_mode 单帧）；晚批六动作域：`LoginFlowGmJingmaiNeigongTest`（周天钳制/帧序、
  未收录拒绝、内功全书 269 本收录/View43 重建/幂等；依赖 F 盘 runtime share，缺则整类跳过）；战斗假人域：
  `GmNpcFactoryTest`（真实表模板克隆）+ `LoginFlowGmCommandTest.combatDummySpawnAndClearRoundTrip`
  （@TempDir 复制表 + overrideServerRoot 全链生成/缺省/清除隔离）；修炼域：`FacultyProgressTest`（帧序/结算/GM 填满/修速拆分/生存属性 vitals 回环+JSON 落盘回读）；
  演武碎银/团练疲劳域：`ActTeamBlockTest`（下发块/扣费/超限拒绝/GM/演武 108↔0 线帧回归）；
  传送域：`LoginFlowSceneSwitchTest`（0x0C 重入帧序/残余包忽略/0x09 收尾/瞬移单帧/加载中拒绝，
  ManualScheduler 驱动 750ms 延迟任务）+ `GmScenePresetsTest`（142 键/抽样值/踢馆 35 键专项/免费格式解析）。
  模板生成域：`GenerateEquipFlowTest`（真实 MySQL+参考服 share：60501 家族/官方池词条/
  固定红词条/护腕臂力+招式/饰品穿戴帧序；临时角色 990033 测后清理）。
  邮件域：`MailFlowTest`（真实 MySQL+go-server1 share：发信帧字节/353 已读/359 领附件
  入包/落盘回读；临时角色 990034）+ `RecordMessagesTest`（type8 宽串/6 f64 字节级，
  载体 RecvLetterRec 319 全 16 列）；礼包货架域：`GiftShopFlowTest`（合成节只列可开
  礼包/排序/免费、View61 头+行帧、msg70 零价购买入包；临时角色 990035）；
  物品发放域：`GrantItemFlowTest`（入包行+成对帧/wearNow 穿戴帧序/换装让位旧装回收
  装备页/双槽耳环 11+12 不覆盖/不可穿戴降级入包；临时角色 990036）。
  伙伴域：`LoginFlowGmCommandTest.botEnsureFollowSupportReviveRoundTrip`
  （@TempDir 真实 attacknpc 表全链：ensure 幂等/非法键拒绝/0x21 追击 moving=1 与
  停步 moving=0 的条目尾 u32 断言/pull 0x1F/世代失效重生换对象号/辅助回满+间隔闸/
  FightState 打死→revive 回满）+ `GmRemoteRoundTripTest` bot_* 六动作透传与参数域
  拒绝分支。
  声望域：`ReputeLevelTableTest`（合成 repute_level.ini：[Repute] 表序/[Level] 非
  连续键级号/[repute_max_value] 特例/自定义段优先）+ `LoginFlowGmCommandTest.
  reputeApplySetAddAllRoundTrip`（@TempDir 真实表全链：set 单阵营 0x11 idx44 名/值
  LE 断言 + PrestigeExp 0x10/add 叠加 0x12+0x11 整刷/all 全量计数/set 0 摘行/未知
  阵营零帧）+ `GmRemoteRoundTripTest` repute_apply 参数域拒绝与透传分支。
- 已知存量失败随在制改动漂移，以当次实测为准（2026-11-05 全量 370 例实测 4 个存量失败：
  PlayerPanelRealtimeTest 1、RoleInventoryStoreIntegrationTest 1（需 MySQL）、MailFlowTest
  tearDown 2；stash 往返验证与 GM 链路无关。更早口径：View40 CD 修复遗留 7 个）。
