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

## 5. 动作清单（2026-11-03）

已实现（白名单+case 双接线，24 个）：
`set_move_speed, set_gravity, set_silver, set_guanyin, set_gold, set_sp, add_sp, fill_sp,
reload_scripts, set_zhenqi_cap, set_power_level, set_alignment, set_vitals, wear_title,
cancel_title, set_faculty, set_fill_speed, fill_current_neigong,
add_livepoint, set_hold_faculty, set_live_speed_power,
set_act_day_limit, clear_team_fatigue, set_team_faculty_last`

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

演武碎银链（客户端语义 lua 钉死）：主面板"可用碎银" = (ActDayValue−ActCostSuiYin)/1000 两
（毫单位）；演武页两标签依赖 switch 2812（本服不推 → 恒显"不限制"）。打卡 1006/23 pay=1
按 `faculty/actfacultyinfo.ini` 卡基价（**文**，原值勿乘 1000——乘错会导致碎银余额判不足全部静默拒绝）扣 CapitalType1 并入账
ActCostSuiYin/ActDayValueUsed；有限额（max>0）超限或碎银不足 → 静默拒绝（零帧零变更）；
pay=2/4（官银/银票）本服未建档，暂免扣只发奖。团练疲劳 = TeamFacultyValue（0..80000，
faculty_team_action.ini [setting] TeamValue），客户端按 (80000−值)×100/80000 反算剩余精力%。

前端 backlog（console.html 有按钮、服务端**未接线**，点击 409，接线时按第 2 节走）：
`learn_all_neigong, learn_all_taolu, forget_all_taolu, grant_all_cards, unequip_card,
set_faction, set_five_attributes, set_jingmai_tier, teleport/switch_scene, spawn_dummy,
spawn_chest, add_item, worldboss_*, bot_*, equip_card, ninjiuzhi_*, set_pk_mode,
set_weather, popup_gm_panel, recast_*, gm_generate_*, story, custom_probe, keybind` 等。

## 6. 测试

- 回环门禁：`nineyin/src/test/java/com/nineyin/server/gm/GmRemoteRoundTripTest.java`
  ——进程内起真实 summer（main+minor.gm），**Summer.hot 整个 JVM 只允许一次**，
  所有断言必须合在单个测试方法内；consumer 线程 tryPop + succeed 模拟游戏侧回执。
- 分发单测：`LoginFlowGmCommandTest`；修炼域：`FacultyProgressTest`（帧序/结算/GM 填满/修速拆分）；
  演武碎银/团练疲劳域：`ActTeamBlockTest`（下发块/扣费/超限拒绝/GM/演武 108↔0 线帧回归）。
- 已知存量失败 7 个（View40 CD 显示修复遗留，与 GM 链路无关）：PlayerActorGoldenTest 2、
  LoginFlowTest 1、SkillCastHandlerFlowTest 3、RoleInventoryStoreIntegrationTest 1（需 MySQL）。
