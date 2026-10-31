# Roadmap 工作项清单与完成度评估

> **数据来源**：Vagrant 私服路线图 <https://vagrant.pw/roadmap>（数据接口 `/api/roadmap`，抓取日期 2026-10-29，Vagrant 侧数据更新日期同日）。
> Vagrant 以**官方青龙（NA）服为 100% 基准**，共 **12 大类 / 85 个系统**，总体进度 **78%**（done 12 / mostly 47 / progress 26 / planned 0 / not_started 0）。
> 本文档将这 85 个系统作为本仓库（nineyin-server）的工作项清单，逐项给出当前完成度评估。**每完成一个功能后请回来更新对应条目与文末更新日志。**

---

## 使用与维护说明

- **本项目状态图例**
  - ✅ 已完成：功能闭环且有测试覆盖
  - 🟨 部分完成：核心链路可跑，存在明确缺口（缺口写在「现状与缺口」列）
  - ⬜ 未开始：无实现；若数据 / 表设计 / GM 目录已就绪，会在备注注明（这算"数据就绪"，不算实现）
- **本项目完成度**为粗略百分数（对照 Vagrant 同名系统的定义范围估算），更新时直接改数字。
- 每次更新条目后，在文末「[更新日志](#更新日志)」追加一行：日期 / 条目 / 变化 / 关联提交。
- 「现状与缺口」列中的类名即代码入口，更新时请一并补上新增的类名，方便回溯。

## Vagrant 状态图例（对照用）

| Vagrant 状态 | 含义 |
|---|---|
| done | 已完成 |
| mostly | 大部分完成 |
| progress | 进行中 |
| planned / not_started | 计划中 / 未开始（当前均为 0） |

---

## 总览

| # | 大类 | Vagrant 进度 | 本项目粗估 | 差距要点 |
|---|---|---|---|---|
| 1 | Core & world 核心与世界（8 项） | 84% | **~30%** | 登录/轻功/场景链路可跑；缺坐骑、多人可见、聊天 |
| 2 | Combat & skills 战斗与武学（9 项） | 82% | **~20%** | 伤害公式与招架闭环；缺技能倍率/冷却/耗蓝、buff 数值 |
| 3 | Schools & factions 门派与阵营（7 项） | 81% | **0%** | 完全未开始 |
| 4 | Quests & story 任务与剧情（7 项） | 79% | **~10%** | 仅九阴志阶段门控；无任何任务执行系统 |
| 5 | PvP（7 项） | 72% | **0%** | 完全未开始 |
| 6 | Guilds 帮会（5 项） | 75% | **0%** | 仅菜单 mark 与表设计 |
| 7 | Economy & trade 经济与交易（9 项） | 79% | **~10%** | 商店/背包只有视图骨架；购买、物品实体、交易全缺 |
| 8 | Life skills 生活技能（6 项） | 77% | **0%** | 完全未开始 |
| 9 | Instances & bosses 副本与 BOSS（7 项） | 64% | **~20%** | 世界 BOSS 较完整；暮色副本为 GM 验证态，掉落无实体 |
| 10 | Events & welfare 活动与福利（8 项） | 77% | **~1%** | 仅打坐恢复，无任何活动系统 |
| 11 | Social 社交（6 项） | 80% | **0%** | 组队/好友/聊天全无 |
| 12 | Website & tools 网站与工具（6 项） | 86% | **~15%** | GM 面板即内部工具；无网站/启动器（研究项目可不追） |
| | **合计（85 项简单平均）** | **78%** | **≈10%** | |

**Vagrant 里程碑（phases）与本项目位置**

| 里程碑 | Vagrant 状态 | 本项目对应 |
|---|---|---|
| Solo foundation 单机基础 | done | **当前位置**：登录→建角→进场景→移动→打怪→轻功/打坐 单机闭环可跑 |
| The big systems 大系统 | mostly | 未进入（物品/任务/成长三大支柱缺位） |
| Multiplayer testing 多人测试 | progress | 未进入（玩家互相不可见） |
| Closed beta 封测 | planned | — |
| Open launch 公测 | planned | — |

---

## 1. Core & world 核心与世界（Vagrant 84%，8 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `login-characters` 登录、角色与存档 | done 95% | 🟨 | 70% | ✅ svrlist→登录→建角→选角→进场景全链路（`LoginFlow`/`SpawnPoints`/`LoginFlowTest`）；MySQL 四表 + JSON 双写存档（`MySqlRoleStore`，C++ 原生格式兼容）。缺：仅持久化建角数据与位置（等级/技能/货币/buff 不落库，重登回到硬编码种子）；单角色架构、无删角；`AccountKeys` 仅 SHA-256 账号键派生，无密码校验 |
| `movement-qinggong` 轻功与身法 | done 90% | 🟨 | 75% | ✅ `QinggongCatalog` 加载 qgdefine.ini，msg216/217 计费（Consume 轻功点）+ buff 槽 + ActiveQGSkillRec 激活行，轻功点每秒回 10。缺：等级差异化轻功、更精细的官服时序对齐 |
| `mounts` 坐骑 | mostly 85% | ⬜ | 0% | 全库无 mount/horse 实现；GM 面板坐骑目录（tool_item.ini script=Mount）仅展示 |
| `world-maps` 世界、地图与旅行 | mostly 85% | 🟨 | 40% | ✅ 场景加载（`SceneManager`/`SceneCreatorLoader`）、跨图切换与重进链（`SceneSwitcher`）、传送门点击+proximity 双触发（`DoorRoutes`）。缺：马车/渡船、地表高度/可行走面、大地图 UI 数据 |
| `npcs-monsters` NPC 与怪物 | mostly 75% | 🟨 | 45% | ✅ NPC 下发与增量同步（`NpcPackets`/`SceneFlow.visibleNpcs`）、巡逻（`PatrolPaths`）、野外刷怪点展开与重生（`respawnDeadNpcs`，RefreshTime 优先）、帧对话菜单。缺：普通怪无主动 AI（不追击/不脱战/无守卫），只有 13 个副本 BOSS 会反击；商店视图可开但买不了 |
| `multiplayer` 多人同屏 | progress 70% | ⬜ | 5% | **玩家互相不可见**：所有玩家 objectId 恒为 `PLAYER_OBJECT_ID=1`，会话间只广播 NPC 帧。多人是后续所有社交/组队/PvP 的前提 |
| `chat-titles-looks` 聊天、称号、风物志与时装 | done 92% | ⬜ | 5% | 聊天零实现；称号/时装仅有 GM 目录展示与导入表（`game_titles`/`game_identity_suits`/card_models.json），无穿戴/收集玩法 |
| `mail` 邮件 | mostly 85% | ⬜ | 0% | 无实现；`role_mails` 表设计 + mails 导入器已就绪，GM 面板 send_test_mail 按钮无服务端 case |

## 2. Combat & skills 战斗与武学（Vagrant 82%，9 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `damage` 武学与伤害 | mostly 85% | 🟨 | 70% | ✅ `CombatFormulas` 社区逆向公式全套（防御对数曲线/命中闪避/暴击暴伤/破防/招架/平减/瘀血双层），玩家打怪 + BOSS/普通怪打玩家双链路，`CombatFormulasTest` 16 项锚定；**多跳波次**（varprop HitCount，等间隔铺满动作时长、总伤害守恒、castSeq 作废旧波）。缺：`skillRatio(skillId)` 恒 1.0（红/蓝/绿倍率参数未接）、无装备/内功/等级加成 |
| `skill-effects` 技能效果、buff 与 debuff | mostly 84% | 🟨 | 30% | ✅ 玩家 24 个原生 buff 槽 + 到期修剪（`PlayerActor.activateBuff/pruneBuffs`），技能 buff 激活/到期发 event-169 add/remove 配对帧；施法距离门（TargetShapePkg，20% 容差）；NPC 侧 `BuffCatalog` 发视觉帧。缺：**buff 无任何数值效果**（BuffCatalog 注释自认"属性加成后续接入"），无 DoT/定身/减速等技能效果 |
| `inners` 内功 | mostly 88% | 🟨 | 10% | 仅静态假种子面板（BootstrapCurNeigong `ng_wd_001` 太极心法30级，FACULTY=9999999）；无学习/修炼/运功/进阶。修为属性（797）只同步不产生 |
| `meridians` 经脉 | mostly 83% | ⬜ | 0% | 无实现；`game_meridians` 表 + 经脉.json GM 目录就绪 |
| `parry-cc` 招架、闪避与控制 | mostly 80% | 🟨 | 55% | ✅ 招架开关/耐力消耗/破招/公式减免（`handleActiveParry`/`drainParry`/`parryMultiplier`），命中闪避在公式内；控制链已接（TARGET_CONTROL 按命中落账 + 纯控制技独立解析 + NPC→玩家 `ControlSink` 接线 + 受控拒施放/跳过出招）。缺：控制表现帧（客户端视觉）、免疫位仅钉死眩晕族、无怒气（SP）技能消耗闭环 |
| `arrays` 阵法 | mostly 70% | ⬜ | 0% | 无实现 |
| `manuals-dolls` 兵录与玉偶 | mostly 80% | ⬜ | 0% | 无实现；`role_binglu`（兵录 12 槽）表设计与导入器就绪 |
| `pvp-rules` PvP 规则（PK 值/狂/杀） | mostly 80% | ⬜ | 0% | 无实现；PK 值仅在存档 extra 原键透传 |
| `boss-mechanics` BOSS 机制 | progress 75% | 🟨 | 55% | ✅ `AiEventEngine` 数据驱动事件机（四表 ini）、BOSS 出招轮转（3 秒一招）、拼音大招 260/120 加倍、30% 血阶段喊话+大招、嗜血回血、召唤/掉宝箱（OpEvent 25/70）、难度与评价档条件、`lastAttacker` 仇恨；普通 monster 已有普攻（战斗窗口 2s 一击、攻击缺省 20 对齐参考服、贴身 4m 距离门、受控/招架/闪避复用通用结算）+ **追击/脱战**（超距 3m/s 开移动腿、拖出 home 50m 清仇恨回位、战斗窗口冻结巡逻、移动帧广播对无路线怪放行）。缺：无仇恨表（单目标覆盖）、副本波次进度只能 GM 推进、原服务器 Lua 已丢失全靠攻略近似 |

## 3. Schools & factions 门派与阵营（Vagrant 81%，7 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `joining` 入门与退出门派 | mostly 94% | ⬜ | 0% | 建角时门派仅决定出生点与外观（`SpawnPoints.forBook`/`Appearance`）；GM `set_faction` 可改 34 个门派白名单，无入门/叛门玩法 |
| `school-board` 门派公告板 | progress 75% | ⬜ | 0% | 无实现 |
| `school-activities` 门派活动（刺探/巡逻/大厅挑战） | mostly 88% | ⬜ | 0% | 无实现 |
| `school-instruction` 门派演练与传功 | mostly 72% | ⬜ | 0% | 无实现 |
| `teacher-pupil` 师徒 | progress 78% | ⬜ | 0% | 无实现 |
| `alignment` 江湖声望与正邪 | mostly 80% | ⬜ | 0% | 无实现；`role_repute`/`game_origins` 表与 repute GM 目录就绪 |
| `school-wars` 门派战 | progress 72% | ⬜ | 0% | 无实现 |

## 4. Quests & story 任务与剧情（Vagrant 79%，7 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `tutorial` 新手引导 | done 95% | ⬜ | 0% | 无实现 |
| `main-story` 主线剧情 | mostly 88% | ⬜ | 0% | 无实现（暮色副本剧情验证是 GM 工具，非任务系统） |
| `school-faction-quests` 门派任务 | mostly 88% | ⬜ | 0% | 无实现 |
| `side-quests` 支线任务 | mostly 81% | ⬜ | 0% | 无实现 |
| `daily-ring-quests` 日常与环式任务 | mostly 84% | ⬜ | 0% | 无实现 |
| `instance-war-quests` 副本与战场引导任务 | progress 19% | ⬜ | 0% | 无实现 |
| `wushu-record` 九阴志 | mostly 80% | 🟨 | 65% | ✅ `NinJiuZhi` 全服阶段 1..9、事件点自动升阶、五类门控（FEATURES/TASKS/NPCS/SCENES/INNER_BOOKS）、落盘 data/ninjiuzhi.json、全服广播（msg419）、切图/NPC 可见/解锁帧三处生效、`NinJiuZhiTest` 全覆盖、GM 操控指令。缺：无章节剧情内容展示，阶段仅由击杀/任务事件点驱动 |

## 5. PvP（Vagrant 72%，7 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `duels` 切磋 | mostly 80% | ⬜ | 0% | 无实现 |
| `wounded` 受伤状态 | mostly 70% | ⬜ | 0% | 无实现（瘀血是血量分层，非受伤玩法） |
| `infamy` 恶人值与红名 | mostly 72% | ⬜ | 2% | PK 模式/警告/PK 保护开关（msgId 291/292/293，PKMode=436 属性推送）已对齐参考服；PKValue/红名逻辑未开始 |
| `sky-ladder` 天梯擂台 | progress 75% | ⬜ | 0% | 无实现 |
| `mount-hua` 华山论剑 | progress 80% | ⬜ | 0% | 无实现 |
| `random-arena` 随机比武 | progress 70% | ⬜ | 0% | 无实现 |
| `battlefields` 大型战场 | progress 65% | ⬜ | 0% | 无实现 |

## 6. Guilds 帮会（Vagrant 75%，5 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `guild-basics` 建帮与帮会运营 | mostly 75% | ⬜ | 0% | 无实现；仅 `MenuPackets.MARK_GUILD=0x1006` 菜单映射（Vagrant 定义：建帮/职位/帮会聊天/升级/维护费/解散投票/金库） |
| `guild-buildings` 帮会建筑、图纸与制服 | progress 65% | ⬜ | 0% | 无实现 |
| `territory-war` 领地与领地战 | progress 75% | ⬜ | 0% | 无实现 |
| `peak-war` 帮会巅峰之战 | mostly 85% | ⬜ | 0% | 无实现 |
| `guild-extras` 联赛、帮会金库与繁荣活动 | mostly 75% | ⬜ | 0% | 无实现 |

## 7. Economy & trade 经济与交易（Vagrant 79%，9 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `npc-shops` NPC 商店 | done 96% | 🟨 | 35% | ✅ `ShopRepository` 加载 trade/shop.ini，`openShop` 发 View61 商品行（分页）。缺：**购买 msg70 只记日志**（S2C 响应待抓包），无扣款、无卖出、价格属性未发；碎银仅 GM set_silver 与显示 |
| `storage` 背包与仓库 | done 90% | 🟨 | 15% | ✅ 8 个背包 View（121/123/125 各 132 格 + 122/124/126 各 16 格）+ 仓库 View4 创建帧下发。缺：**全部是空容器**——无物品实体、无发放/移动/拆分/存取 |
| `cash-shop` 商城 | mostly 85% | ⬜ | 0% | 无实现 |
| `stalls` 摆摊 | mostly 70% | ⬜ | 0% | 无实现 |
| `auctions` 拍卖行 | progress 80% | ⬜ | 0% | 无实现 |
| `enhancement` 开光与强化 | mostly 75% | ⬜ | 0% | 无实现；洗练词条数据（senior_prop_dire.ini）GM 目录已就绪 |
| `treasure-maps` 藏宝图 | mostly 70% | ⬜ | 0% | 无实现 |
| `world-chests` 世界宝箱 | progress 55% | 🟨 | 25% | `CloneLoot` 开箱流程（菜单 0x2103→距离校验→OpenAction 动画→读条→移除+worldInfo 文本）可用于副本宝箱；缺：世界刷新点、无物品产出（仅文本） |
| `player-trade` 玩家交易 | mostly 75% | ⬜ | 0% | 无实现 |

## 8. Life skills 生活技能（Vagrant 77%，6 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `gathering` 采集与钓鱼 | mostly 78% | ⬜ | 0% | 无实现 |
| `crafting` 制造 | progress 87% | ⬜ | 0% | 无实现；`role_lifejob_*` 表设计与导入器就绪 |
| `minigames` 生活技能小游戏（琴棋书画/锻造） | progress 66% | ⬜ | 0% | 仅 GM 协议探针 `action_state_fishing`/`action_native_fishing`，非玩法 |
| `offline` 离线打工与离线收益 | mostly 75% | ⬜ | 0% | 无实现 |
| `pets` 宠物 | mostly 75% | ⬜ | 0% | 无实现 |
| `home` 家园 | progress 68% | ⬜ | 0% | 无实现；`role_homepoints` 表设计就绪（回城点相关） |

## 9. Instances & bosses 副本与 BOSS（Vagrant 64%，7 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `forbidden-instances` 禁地副本 | mostly 72% | 🟨 | 35% | ✅ clone003 暮色村摆位加载（`SceneCreatorLoader` clone 模式 + 波次展开）、dungeonProgress/difficulty(1-4)/rating(1-3) 字段、`TwilightVerify` 三段验证（拿钥匙/村民护送/波次揭示）、13 个 BOSS 绑定 AI 包。缺：**进度只能 GM 推进**（无击杀驱动波次）、无组队进入/人数限制/次数记录（`role_clone_usage` 表已设计） |
| `world-bosses` 世界 BOSS | mostly 83% | 🟨 | 70% | ✅ `WorldBossManager` 四表 + 时间档（日期区间/星期集合/每日窗口）、固定点 + 江湖随机、生成防重、击杀回收、存在期到点消失、全服 worldInfo 通告、GM 强刷/清空/列表、`WorldBossManagerTest`。缺：30/15 分钟预警、伤害统计与排行、奖励掉落（真实掉落表丢失）、**世界 BOSS 本体不会还手**（不在 BOSS_PACKAGES） |
| `jianghu-legends` 江湖传奇 | mostly 70% | ⬜ | 0% | 无实现 |
| `heart-devil` 心魔 | mostly 80% | ⬜ | 0% | 无实现 |
| `random-encounters` 随机奇遇 | progress 70% | ⬜ | 0% | 无实现 |
| `drops` 怪物掉落 | progress 40% | 🟨 | 15% | `CloneLoot.rollLoot` 硬编码 7 珍稀 + 6 常规池（configId 尾字母定件数，地狱修罗+1），**掉落仅为聊天文本**；真实掉落表 comb_PassDrop_* 已丢失；无物品实体入包 |
| `boxes` 宝箱与礼包 | progress 45% | 🟨 | 20% | 副本宝箱开箱流程完整（见 `world-chests`）；giftbox 目录（322 掉落包）+ `game_gift_boxes` 表 + 导入器就绪，无开启逻辑 |

## 10. Events & welfare 活动与福利（Vagrant 77%，8 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `cultivate-often` 经常修炼（日/周活跃） | done 92% | ⬜ | 0% | 无实现 |
| `cultivation` 修炼与团练 | mostly 80% | ⬜ | 5% | 打坐恢复已有（msg240，10 秒回满血蓝、瘀血消退），但这是恢复机制；修炼转化/团练玩法无 |
| `welfare-panel` 福利面板（签到/英雄成长等） | mostly 75% | ⬜ | 0% | 无实现 |
| `daily-activities` 日常活动 | mostly 70% | ⬜ | 0% | 无实现 |
| `cart-escort` 押镖 | progress 80% | ⬜ | 0% | 无实现 |
| `shop-events` 团购/砸蛋/消费返利 | progress 60% | ⬜ | 0% | 无实现 |
| `schedules` 活动日历 | done 90% | ⬜ | 5% | 世界 BOSS 已有时间档机制（TimeRule 每日窗口）可作底座；无日历 UI 与通用调度框架 |
| `seasonal` 节日活动 | progress 70% | ⬜ | 0% | 无实现 |

## 11. Social 社交（Vagrant 80%，6 项）

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `teams` 组队（含队伍拾取模式） | mostly 89% | ⬜ | 0% | 无实现；teams/team_members 表设计就绪（导入器当前仅清表） |
| `friends` 好友与仇人 | mostly 78% | ⬜ | 0% | 无实现 |
| `bonds` 结拜/结义/婚姻 | mostly 82% | ⬜ | 0% | 无实现 |
| `companion` 侠侣 | progress 70% | ⬜ | 0% | 无实现 |
| `rankings` 排行榜与角色强度 | mostly 75% | ⬜ | 0% | 无实现 |
| `chat-actions` 聊天动作与战斗结算 | mostly 75% | ⬜ | 0% | 无实现；聊天系统整体为零（仅 worldInfo 单向系统公告） |

## 12. Website & tools 网站与工具（Vagrant 86%，6 项）

> 本项目为研究型私服，网站/启动器/3D 模型浏览器属运营侧设施，可长期低优先或标记"不适用"。

| 工作项 | Vagrant | 本项目 | 完成度 | 现状与缺口 |
|---|---|---|---|---|
| `accounts` 网站与账号 | done 95% | ⬜ | 0% | 不适用倾向：无网站；游戏侧 `AccountKeys` 仅账号键派生，无注册/密码/找回 |
| `databases` 指南/技能浏览器/物品库/地图 | done 93% | 🟨 | 40% | GM 面板 16 个目录接口（道具/卡牌/经脉/称号/坐骑/时装/礼包/天气/禁地/声望/身份/词条/套路/站姿/bots/origin）≈ 内部版数据库；无对外网站 |
| `models3d` 3D 角色查看器与 Lore 库 | progress 83% | ⬜ | 0% | 无（不适用倾向） |
| `support` 工单与封测候补 | done 90% | ⬜ | 0% | 无（不适用倾向） |
| `launcher` 启动器与客户端更新 | progress 60% | ⬜ | 0% | 无（不适用倾向）；当前为手动部署 + `tools/smoke/smoke_client.py` 冒烟 |
| `gm-tools` GM 工具 | done 93% | 🟨 | 50% | ✅ GM HTTP 面板（19080 本机）：会话快照、16 目录、命令队列（`GmHub`/`GmActions` 34 个动作：传送/门派/资源/世界 BOSS/暮色验证/九阴志操控），`GmRequestHandlerTest`。缺：前端约 70 个按钮中约 40 个无服务端 case（发道具/称号/卡牌/洗练/假人/天气等返回"动作未实现"）、无物品/金币流向追踪 |

---

## 建议推进顺序（依据当前缺口）

按"解锁后续系统的前提"排序：

1. **玩家互相可见**（objectId 分配 + 玩家帧互播）——多人、组队、PvP、交易、聊天的前提
2. **物品实体 + 背包 + 掉落入包**——经济系统与成长系统的共同前提（当前 8 个背包 View 全是空壳）
3. **商店购买闭环**（msg70 响应格式抓包钉死 + 扣款 + 卖出）——经济闭环的最小可用切片
4. **怪物主动 AI**（仇恨表、追击、脱战回位、守卫）+ 世界 BOSS 接入 BOSS_PACKAGES 还手
5. **技能倍率/冷却/耗蓝表接入**（`skillRatio` 恒 1.0 是当前战斗最大失真点；cooldown_rec 表已建）
6. **buff 数值效果**（BuffCatalog 属性加成接入）
7. **任务系统骨架**（复用 Task_Accepted/Completed 记录表 + role_story_tasks 表设计）
8. **等级/经验与运行时状态持久化**（当前重登回硬编码种子、Level 恒 1）
9. **聊天频道**（最小编队/世界频道，C2S opcode 待抓包）
10. **副本击杀驱动波次推进**（替换 GM 手推 dungeonProgress）

> 相关决策：**暂不接入脚本系统**（LuaJ/GraalJS 等），理由与重新评估的触发条件见
> [script-system-assessment.md](script-system-assessment.md)——推进顺序前 10 项均为
> 协议对齐与系统骨架工程，脚本层属"内容生产期"问题。

---

## 更新日志

| 日期 | 条目 | 变化 | 关联提交 |
|---|---|---|---|
| 2026-10-29 | 全部 85 项 | 初版：从 vagrant.pw/roadmap 拉取清单并完成首次评估 | — |
| 2026-10-29 | 推进顺序 | 新增 [script-system-assessment.md](script-system-assessment.md)：评估并决策暂不接入脚本系统，延后至内容生产期 | — |
| 2026-10-30 | 协议基线 | 新增 [go-exe-protocol-analysis.md](go-exe-protocol-analysis.md)：逆向分析 Go 参考服（9yin-game-native-menu.exe）的帧层/CustomSend msgId 全集，建立 Java↔参考服对照矩阵（含 4 项疑似偏差） | — |
| 2026-10-30 | 协议基线（infamy 前置） | 对齐参考服实现 msgId 291/292/293（PK 模式/警告/PK 保护开关，PKMode=436 属性推送）与 61 显示装备类型（`SceneFlow`）；未知 C2S opcode 首现 INFO+hexdump（`FxOpcodeRouter`），测试 `SceneFlowTest.pkAndClientTogglesMatchGoServer` | — |
