# GM 命令链路全链探索（含场景生成与战斗假人，2026-11-05）

> 战斗假人（spawn_combat_dummy / clear_combat_dummies）落地时的探索沉淀。
> 全链逐环节实测核对（非抄旧档）；「新动作怎么接线」见 `gm-command-wiring.md`（三步接线指南），
> 本文是其底座链路的深挖 + 场景生成/战斗两条子链 + 战斗假人案例。

## 1. 命令链路总览（浏览器 → 游戏主循环 → 回执）

```
console.html / console-features.js   command({action, ...params})
  ↓ POST /api/sessions/{id}/command（面板 127.0.0.1:19080）
GmRequestHandler.proxyCommand        [gm-web 进程]
  剥 action 键，其余键并入 params；GameServerRpc.call（cluster=Game, StringLine 动态注册）
  ↓ summer RPC（方法返回 JSON 字符串，两模块不共享类）
GmRemote.command(sessionId, action, params)   [游戏服 minor.gm 127.0.0.1:19081]
  ① validateCommand：白名单 + 数值域 → 失败立即 {ok:false}，不入队
  ② GmHub.enqueue：按 sessionId 的 ConcurrentLinkedQueue（会话不在线也入队 = 软接受，
     控制台可在无人在线时演练；tryPop 侧自然丢弃）
  ③ command.awaitReply(2s)：阻塞等游戏侧真实回执；超时/中断折算为失败 Reply
     （文案「游戏会话未在 2 秒内确认发送」——不把「已入队」描述成成功）
  ↓（游戏主循环 1s faculty ticker，与 facultyTick/meridianTick 同线程天然串行）
LoginFlow.drainGmCommands → applyGmCommand
  前置：reload_scripts 特例先行（无需角色）；session.player == null → 统一回执
  「角色尚未进入场景」（各 applyXxx 内部对 selected/world 再各自把关，文案可带动作前缀）
  → switch (action) 分发 applyXxx(...)
       校验 → 改状态 → connection.writeFrame 推客户端 → persist 落库（按域）
       → gmHub.update(id, s -> s.lastAction = 文案) → command.succeed(文案)
  ↓ 回执沿 RPC 原路返回面板
```

环节与文件：

| 环节 | 文件 | 关键点 |
| --- | --- | --- |
| 面板按钮/输入 | `gm-web/src/main/resources/nineyin/gm/console.html` + `static/console-features.js` | JS 函数收口 `command({action,...})`；下拉数据走 gm-web 目录接口 |
| HTTP→RPC 桥 | `gm-web/.../gm/GmRequestHandler.java`（proxyCommand） | 动态注册 ClientMgr；目录接口（/api/cards、/api/combat-dummy/taolus 等）在 `GmCatalog` |
| RPC 入口 | `nineyin/.../gm/GmRemote.java`（@Remote filter=true serverName="gm"） | 仅 GM RPC 端口可达，游戏端口（19061/4000）不可见 |
| 入口校验 | 同上 `validateCommand` | 白名单 switch + `intRange/floatRange/nonNegative/nonEmpty/lengthLimit`（后两者为字符串域） |
| 队列/快照 | `nineyin/.../gm/GmHub.java` | PlayerSnapshot（面板 lastAction 就在这）+ per-session 命令队列 |
| 命令对象 | `nineyin/.../gm/GmCommand.java` | record + CompletableFuture；succeed/fail 完成回执 |
| 消费分发 | `nineyin/.../game/login/LoginFlow.java`（drainGmCommands/applyGmCommand） | 1s ticker 串行消费；未实现动作回执「动作未实现: xxx」 |

**双端接线规则**（漏一半的两种死法）：
只加 applyGmCommand case 不挂白名单 → 入口 `unsupported action` 直接拒；
只挂白名单不加 case → 游戏侧回执「动作未实现」（历史上 set_power_level 漏挂白名单即此坑）。

## 2. 场景生成子链（spawn_* 家族共底座）

```
applySpawnXxx
  → gmSpawnPosition(session)                     玩家实时位（motion pendingLocation 优先）+ 朝向前 2.5m
  → GmNpcFactory.spawnFromTemplate(tableRelative, templateId, x,y,z,orient, overrides)
  │    npcconfig 根 = ServerPaths.projectPath("resources","modern","share","npc","npcconfig")
  │    根解析优先级：-Dnineyin.serverRoot > NINEYIN_SERVER_ROOT 环境变量 > 向上自定位
  │    （resources/modern/share 目录或 pom.xml）；测试 overrideServerRoot 注入
  │    解析链 = NpcResolver.resolve：spawnDefaultsCompatV1 默认层 → 模板行 →
  │    creator 覆盖（overrides，键=NPC schema 属性名，官方方言线型）→ 摆放坐标
  │    （PosiX/Y/Z/Orient 运行时态）；模板表 GBK TSV，按绝对路径进程级缓存
  → NpcSpawn（resolved + 坐标）
  → registerGmSpawn(session, npc, chest, merchant, combatDummy)
  │    objectId = session.nextGmObjectId++（基 900_000；目录 NPC 是下标+2 小号段，永不冲突）
  │    入 session.gmSpawns（LinkedHashMap，LoginSession.GmSpawn 四标志）
  │    + session.world.registerNpc(objectId, npc)
  → session.world.reconcileViewport()            0x0D 对象帧 + 0x1F 定位帧物化（按 ID 序）
```

- **combat 判定在注册期**：`SceneLifecycle.isCombatScriptClass(scriptClass)`——模板
  script 列 ∈ {attacknpc, bossnpc} 才是战斗对象。**scriptClass 恒取模板行，creator
  覆盖改不了**（ResolvedNpc.scriptClass final）→ 想让某造型可战斗，必须选
  attacknpc/bossnpc 表里的同造型模板，别指望在 CommonNpc 模板上强行开战斗。
- **生命周期**：切图 `world.begin` 整体清空 gmSpawns（LoginFlow 两处 begin 站点同步清，
  连带 giftMerchantObjectId 等句柄）；单对象摘除 `removeGmSpawn`（unregister →
  reconcile → 0x0E 消失）。
- **点击路由**（0x07）：combat NPC → `fight.selectTarget`（须 combat+存活，回 0x10
  LastObject）；非 combat → `openNpcDialog` 的 gmSpawns 优先分支（宝箱→openGmChest、
  商人→gm_giftbox 商店、其余无对话）。木桩/战斗假人是 combat 对象，永远走不到对话分支。
- **战斗快照口径**：`CombatNpcInfo` 的 maxHp/name/model 按实体属性 MaxHP/Name/Resource
  取值（MaxHP≤0 兜底 1000）；FightState 首次命中/选中时按它物化 NpcCombat actor。

现存四类 GM 摆放（同一条底座，靠 GmSpawn 标志区分语义）：

| 动作 | 模板（表/ConfigID/script） | 标志 | 特有行为 |
| --- | --- | --- | --- |
| spawn_dummy 训练木桩 | attacknpc.txt `monv010100` AttackNpc | 无 | MaxHP/HP 覆盖 + 移速 0 |
| spawn_chest 世界宝箱 | monsternpc_commonnpc.txt `stormbox_10` CommonNpc | chest | 改名「镀银宝箱」，点击开箱拾取后消失 |
| giftbox_merchant_here 礼包商人 | monsternpc_commonnpc.txt `citywar03_npc_010` CommonNpc | merchant | 改名，点击开 gm_giftbox 商店，重复拉先摘旧 |
| spawn_combat_dummy 战斗假人 | attacknpc.txt `monf022202` AttackNpc | combatDummy | 见 §4 |

## 3. 战斗接入链（GM 摆放对象如何"可打/还手"）

```
选中：0x07 / custom 431 → FightState.selectTarget（combat + 存活才受理）
出招：C2S 211 → SkillCastHandler
  resolveTargets（按技能 targetMode）：
    SELECTED       → livingSelectedTarget（选中对象存活校验）
    CASTER_* 形状  → FightState.shapedTargets：遍历 world.combatNpcs() 几何判定
    GROUND_*       → castPoint（211 长格式落点）为中心
    SELECTED_AREA  → 选中目标位置为中心
  → FightState.ensureLocked：按 CombatNpcInfo.maxHp 物化 NpcCombat（懒建）
  → settleTargets → applyDamage（threat 累计进 0x13 格；死亡不删对象、respawnAt = +15s 回满）
反击：targets 非空 → NpcCombatAi.playerEngaged → 每 NPC 一条 2.2s 自续循环：
  出招两帧  msg337（NPC 位置/朝向）+ msg20（NPC, jn_mon053_02, 目标=玩家）
  命中未格挡  msg24 伤害飘字 + 0x2D HPRatio（玩家血条由它驱动）+ 500ms 被击技能锁
  被格挡     零帧静默挡下
  回怒       RAGE_TAKEN_UNPARRIED(+1)/PARRIED(+2) → SP 帧
  实伤       恒 1 点（DAMAGE_PER_HIT，名义 20 只进 msg24 尾值）
  失效自停   NPC 不存在/非 combat/已死、连接断、玩家 HP≤0、!player.fighting、距离 >6m
             （removeGmSpawn 后 combatNpcInfo=null，下一拍自停，无需显式停 AI）
```

## 4. 战斗假人（spawn_combat_dummy / clear_combat_dummies，2026-11-05）

### 4.1 前端（面板早已就绪，本次只补服务端）

- 区块「添加假人（战斗）」：cd-name（默认「战斗假人」）/ cd-hp（默认 50000）/
  cd-dmg（单段伤害，0=按技能表）/ cd-skills（招式列表）/ cd-preset（预设套路下拉）。
- 套路预设目录：gm-web `GET /api/combat-dummy/taolus` → `GmCatalog.taolus()`
  （school_taolu.ini + taolu_info.ini 编译，面板仅用于填充 cd-skills 文本框）。

### 4.2 参数域（GmRemote.validateCommand）

| 参数 | 域 | 缺省 |
| --- | --- | --- |
| dummyHp | 可选 0..100_000_000 | 0 → 游戏侧取 50_000 |
| dummyName | ≤64 字符 | 空白 → 「战斗假人」 |
| dummyDamage | 可选 0..1_000_000 | 仅记日志不生效（bak 对齐） |
| dummySkills | ≤512 字符 | 同上 |

clear_combat_dummies 无参。

### 4.3 bak → 现架构映射（移植对照）

| bak GmActions（menu2 最早版） | 当前实现 |
| --- | --- |
| TwilightVerify.spawn(monsternpc `monm080701`「机关木人」) + dummy.monster=true | GmNpcFactory 克隆 attacknpc `monf022202`「机关木人」——**同名同模型 npc\monster026**，AttackNpc scriptClass 自动 combat |
| tag "gm_combat_dummy" + world.npcs 过滤 | LoginSession.GmSpawn.combatDummy 标志 + gmSpawns 遍历 |
| sendNpc / despawnTagged | registerGmSpawn + reconcileViewport / removeGmSpawn（0x0E） |
| monster 反击链 | 现有 NpcCombatAi（§3） |
| dummyHp 默认 50000 / 前方 2.5m / damage+skills 仅记日志 | 同款 |

**模板选型要点**：monsternpc_commonnpc 的机关木人（monm080701）script 列是
CommonNpc——在现架构不可战斗；attacknpc 表存在**同名同模型**的 monf022202
（AttackNpc），是零解析器改动的等价替代（res1 与 9yin-go-server1 两个资源根都有）。

### 4.4 服务端行为

- spawn：未进场景回执「生成战斗假人失败：角色尚未进入场景」；overrides =
  Name/MaxHP/HP；`registerGmSpawn(..., combatDummy=true)`；回执
  「已生成战斗假人（对象=N 名字 血量=H），选中攻击后会还手」；log 含 damage/skills。
- clear：按 combatDummy 标志逐个 removeGmSpawn（训练木桩/宝箱/商人隔离不受影响），
  回执「已清除 N 只战斗假人」。
- 死亡走战斗一期语义：不删对象、15s 回满血（FightState.RESPAWN_MS），可反复打。

## 5. 测试模式（GM 动作三层）

| 层 | 文件 | 模式 |
| --- | --- | --- |
| 模板克隆 | `nineyin/src/test/.../world/npc/GmNpcFactoryTest.java` | 真实表克隆断言（scriptClass/覆盖/模型）；参考根不可用 assumeTrue 跳过 |
| 游戏侧分发 | `nineyin/src/test/.../game/login/LoginFlowGmCommandTest.java` | Env 入队 + `command.awaitReply` 断言；失败路径不写帧；资源依赖用 @TempDir 复制表 + `ServerPaths.overrideServerRoot` + finally 还原（mountSkin 同款）；战斗假人用例：combatDummySpawnAndClearRoundTrip（生成→combatNpcs 快照→缺省值→清除隔离）/ combatDummyActionsFailWithoutScene |
| RPC 全链门禁 | `nineyin/src/test/.../gm/GmRemoteRoundTripTest.java` | 进程内起真实 summer（main+minor.gm）；**Summer.hot 整 JVM 仅一次 → 所有断言合在单个测试方法**；consumer 线程 tryPop+succeed 模拟游戏侧 |

## 6. 存量失败口径（2026-11-05 全量实测）

全量 370 用例中 4 个失败为**存量**（stash 往返验证，与本链路无关）：
PlayerPanelRealtimeTest 1（MaxHP 断言）、RoleInventoryStoreIntegrationTest 1（需
真实 MySQL 数据）、MailFlowTest tearDown 2（NPE）。另有 24 个 skip（参考资源根
缺失类）。随在制战斗改动漂移，以当次实测为准。
