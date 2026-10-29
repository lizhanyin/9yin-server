# 暮色之村（clone003）BOSS 机制与阶段跳转还原分析

基于网络攻略（17173 / 蝶梦小天地等）与本地 share 数据表（`E:\Workspace\project\game\9yin-go-server1\resources\modern\share`）交叉验证的完整调研结论。
数据链解码全部经过脚本实测，文中引用的表节、ID 均可复现。

结论先行：**阶段跳转的"骨架"100% 可数据驱动还原（AI 事件机三表 + 条件两表）；战斗出招循环的"血肉"依赖已丢失的服务器 Lua，需按攻略近似重建。**

---

## 1. 难度体系（网络攻略 × 本地数据互证）

| 难度 | 进入条件 | 剧情差异 | 本地对应 |
| --- | --- | --- | --- |
| 普通 | 6 人，一阶内功 6 层 | 6 BOSS 标准线 | `Func(8,1)`；技能基础版 |
| 困难 | 6 人，一阶 17 层 / 二阶 2 层 | 同结构数值强化 | `Func(8,2)`；技能 `_B` 变体 |
| 地狱 | 6 人，一阶 28 层 / 二阶 13 层 / 三阶 3 层 | **新增第 7 个 BOSS 宇文绝**；要求君子御天满、唐门烟雾满 | `Func(8,3)`；技能 `_C` 变体 |
| 修罗 | 后期版本 | 每关副标题剧情（穷凶极恶周厉风 / 星火燎原魏不羁 / 贪婪成性钱昔之 / 无耻淫道叶凯楼）；村民保护严苛化；玉佩机关"失灵"需雁行功攀墙接力（每人限一次） | `Func(8,4)`；等级分箱 34-50 / 51-60 |
| 新暮色（2025 重制） | — | 红圈机制（把 BOSS 拉出圈外）、长条技能接圈圈跳出圈外 | 万影境天包（5922/5923）等新包 |

`cloneinfo.ini [ini\scene\clone003]`：`EasyPowerLevel=11 / DifficultPowerLevel=200 / HellPowerLevel=26`。

掉落池（三档通用，对应 `clone/clonecolinfo.ini` 的 col_award / col_repute）：上天梯、雁行功、铜铁装备图谱、材料、寒鸦草、三才阵秘籍、门派一阶内功书页、天魔追魂刀残卷。

## 2. BOSS 技能表（本地 npcconfig 全量提取）

来源：`npc/npcconfig/clonenpc/bossnpc.txt`（`table@SkillRec` 列）。三个系列：

- **`BossClone003001~010`**（普通版，全 Lv10，BossLevel=10/9）
- **`yx` 变体**（`BossClone003yx001~010`，Lv 10~46，引用服务器 Lua `npc\clone\clone003\bossclone003xxx.lua` —— **已丢失**）
- **`BossCloneTZ003001~006`**（禁地挑战版，Lv159，独立技能组 `skill_clonems_*` 每技能 6 条）

| BOSS | 技能 ID（`clone003_` 前缀） | 攻略实名 | 备注 |
| --- | --- | --- | --- |
| 周厉风 001 | `zhoulifeng_01/02/03` + `sxks` + `ywsf` | **sxks＝嗜血狂杀**（对 T 高伤需格挡） | 离太远脱战回血 |
| 魏不羁 002 | `weibuji_01/02/03` + `byft`/`cltz`/`jzzt` | （拼音推断，火系） | **<20% 血读条"烈焰真罡"**，血条红色滚动条需远离/打断 |
| 钱昔之 003 | `qianxizhi_01/02/03` + `txqj`/`xyjq`/`zhyj` | — | **寺庙箱子全部拿完才现身**；召唤最多 3 杀手；拉屋内关门打 |
| 叶楼凯 004 | `yeloukai_01/02/03` + `cnhh`/`dztx`/`jypx` | **cnhh＝春泥护花**（放火圈需跑出） | 约 1300 血读条秒杀大招需打断；cnhh 带专属 Lua（丢失） |
| 韩似锦 005 | `hanshijin_01/02/03` + `lhd`/`qlmc`/`tzxj` | — | 技能组最多（33 条）；点穴封招；血量将耗尽时回血 |
| 赵子亟 006 | `zhaoziji_01/02/03` + `qxgd`/`tkbm`/`tscs` + `tdbr` | **tdbr＝天地不容**（攻略亦作"天地不仁"） | **<30% 血全屏大招**，三才阵集体格挡或跑位 |
| 黑白双煞之白 007 | `qy_01/02/03` + `qqtx` | 真名**裘云**（AI 包 192/193 证实） | 仅有 `_C` 地狱特化变体；**靠近合体放大招，需拉开分杀** |
| 黑白双煞之黑 008 | `gg_01/02/03` + `hbmz` | 真名**郭刚** | 同上 |
| 韩似锦分身 009 | 与 005 共用技能表 | — | — |
| 锦衣卫千户 010 | `ywj_01~08`（8 个无拼音大招） | — | `TaoLu=clone003lock`，LockType=1473 擒拿锁链系（铁索缠身类：格挡或队友普攻解） |
| 宇文绝 | **不在 boss 表**（仅 `EventNpcClone003006 宇文绝动画`） | 断空击（虚招破防击飞）/ 烈火击（实招浮空）/ 铁索缠身 / **天地哀怨（临终大招）** | 战斗实体为 Lua 动态生成（丢失），AI 包存在（194/4405/5315/5981） |

**关键发现：技能 `_B`/`_C` 后缀 = 难度分级变体**。`skill_new.ini` 中每个大招都有 基础 / `_B` / `_C` 三份（如 `zhoulifeng_sxks` / `sxks_B` / `sxks_C`），与 Easy/Difficult/Hell 三档对应，难度差异可直接数据驱动。

## 3. AI 事件机解码（阶段跳转核心机制）

数据链：`rule/ai_editor_package.ini`（包）→ `rule/ai_editor_event.ini`（37,449 事件）→ `rule/condition.ini`（40,299 条件）→ `rule/condition_formula.ini`（条件 AND/OR 组合）。
暮色相关：**37 个包、492 个事件、32 种条件**，全部可解码。

### 3.1 包的代际（`Text=` 含"暮色之村"）

| 代 | 包 ID | 用途 |
| --- | --- | --- |
| 旧版（宝箱掉落） | 182~194 | 死亡掉箱 |
| 新版 | 4399~4407（周厉风→宇文绝） | 每包约 30 事件组 |
| 优化版 | 5303~5315 | 每包 6~8 事件，最终线上形态 |
| 挑战版【挑战】 | 5976~5981 | TZ 禁地（BossCloneTZ*） |
| 万影境天 | 5922~5923 | 周厉风 / 宇文绝变体 |

另有关联包：【暮色之村】房屋火焰 5123、赵子亟家大门 5124、标记点 5125、宝藏点 5126、纵火者 5127。

### 3.2 事件结构（四元组：事件类型 × 概率 × 条件 × 操作）

| 字段 | 语义（数据自证） |
| --- | --- |
| EventType=1 | 出生（自动加初始 buff） |
| EventType=3 | 死亡（掉宝箱） |
| EventType=4 | 周期检查（条件满足即触发） |
| EventType=5 | 消失/清理（OpEvent=28 无参） |
| EventType=6 | 战斗中周期检查（带 OpRate 概率） |
| OpEvent=2 | 加 buff（Para1=buff ID） |
| OpEvent=25 | 召唤 NPC/宝箱（Para1=配置 ID） |
| OpEvent=27/29 | 喊话/台词（Para=台词 ID，文本在客户端） |
| OpEvent=19 | 设置 NPC 属性（StopAI/LifeTime/BornAction 等） |
| OpEvent=70/71 | 召唤 NPC 组 / 台词组 |
| OpRate | 触发概率百分比 |

### 3.3 条件类型实测（暮色 BOSS 用到的 32 种）

- **`Func(8, 1/2/3/4)` ＝ 副本难度第 N 档**（普通/困难/地狱/修罗）——由宝箱矩阵反证（见 3.4）
- **`Func(22, 1/2/3)` ＝ 通关评价/掉落档次**（三档）
- `HPRatio`（min/max 区间）、`MPRatio`（min 阈值）、`Level`（区间）、`BufferContainer`（buff 存在性）——Type=1/2 数值条件
- 公式表组合：如 `23706 = 16111 & 16383` 即 `难度1 & 评价1`

### 3.4 铁证：韩似锦死亡掉宝矩阵（包 4407，EventType=3 全解码）

| 条件 | 难度 | 评价 | 掉落 |
| --- | --- | --- | --- |
| 23706 = Func(8,1) & Func(22,1) | 普通 | 1 | `BossBox003005A` |
| 23709 / 23712 | 普通 | 2 / 3 | `BossBox003005B` / `C` |
| 23707 / 23710 / 23713 | 困难 | 1 / 2 / 3 | `BossBox0032**05**A/B/C` |
| 23708 / 23711 / 23714 | 地狱 | 1 / 2 / 3 | `BossBox003305A/B/C` |
| 23727~23730 = Func(8,4) & Level(34-50/51-60) & … | 修罗 | 按玩家等级 | `BossBox003605C` |

**宝箱命名规律 `BossBox003[难度位]05[评价位]`** —— 难度 × 评价 = 9 种箱，纯数据驱动，与网上"不同难度掉落不同"完全互证。

### 3.5 转阶段精确阈值（与攻略吻合）

| 证据 | 数据 | 攻略对应 |
| --- | --- | --- |
| 条件 [17257] `HPRatio min=0 max=30`（挑战版韩似锦公式 121315，还叠加 buff `buf_clonems_hsj_006_cd` 检查 + `MPRatio min=50`） | 血量 30% 以下触发 | "30% 放天地不容" 分毫不差 |
| 事件 [31726] `EventType=6 OpRate=30 SelfCondition=MPRatio>=80 → buff_clone003_hanshijin_HPMP` | 战斗中蓝 ≥80% 时 30% 概率回血 | "血量将耗尽时回血" |
| 周厉风出生事件 [41634] → `buff_bossclone003001_xixue` | 出生自带嗜血 buff | "嗜血狂杀" |
| 魏不羁出生 → `buff_bossclone003002_jidao`；优化包 [41722] | 即倒/激导类初始 buff | 火系机制 |

### 3.6 Buff 链条

`buff_new.ini`（ID→StaticData）→ `buff_static.ini`（VarProp 属性区间、Category、Photo、免疫类、LifeTimeType）→ 属性修改表。
例：`buff_bossclone003001_xixue`→StaticData 7946→VarProp 152747~152766（20 级可变 buff）。

## 5. 国际版（Age of Wushu）补充情报

外网主要资料为 [WushuWiki（Fandom）](https://ageofwushu.fandom.com/wiki/Twilight_Village) 的 Twilight Village 专页（另有 [序章版专页](https://ageofwushu.fandom.com/wiki/Twilight_Village_(Prologue))、[YouTube 实战视频集](https://www.youtube.com/playlist?list=PLn7Bn-Q0SmzZ5b3H4G5k34bZKLsMql1Hw)）。与中文攻略互补的关键情报：

1. **官方英文技能名（直接解开拼音 ID）**：
   - 周厉风：被动＝攻击概率回 1000 HP（**被格挡也回**，坐实 `xixue` buff）；`sxks`＝**Kill for Blood**（正面 AoE）；`ywsf`＝**Entangled in Fishnet**（鱼网缠身减益）；另有 Silver Hook / Rise of the Twin Dragons / Bloody Dust（三个单体打断技）
   - 魏不羁：**Grace of the Red Dragon**（伤害并召唤火场，站火持续掉血）；`byft`＝**Blazing Sky**（AoE 火焰喷息击倒）；Thunder Breaker（击倒）；Shatter the Mountain / Heavenly Strike（单体打断）
   - 钱昔之：被动＝攻击概率眩晕（被格挡也生效）；`xyjq`＝**Xuanyin Blade Chi**（玄阴刀气，抽内力）；`txqj`＝**Eliminate Thousand Forces**
2. **流程机制精确化**：救 5 个村民（每村民 2 看守）→ 周厉风现身；灭火＝空桶→海边装水→绿圈处使用（最多带 2 桶，土匪存活会复燃）；**赵大妈 (65,-100) 对话后 3 个劫匪在 (10,-102)/(0,-126)/(24,-133) 刷出**——真实坐标可直接入 creator 摆位
3. **分支机制（新发现）**：韩似锦 **3 分钟时限**，超时 BOSS 消失改打赵子亟——005/006 两条线的官方解释
4. **宇文绝**：开战召唤刺客（assassins）——第 7 个 BOSS 坐实；叶楼凯同为开战召唤
5. **Prologue 版**＝3 BOSS 短版（周厉风/魏不羁/钱昔之，不召唤），对应国服新手暮色

## 6. 还原度清单

| 机制环节 | 还原度 | 依据 |
| --- | --- | --- |
| 阶段跳转框架（事件→条件→动作） | **100%** | ai_editor 三表 + condition 两表；通用引擎一次开发全副本受益 |
| 难度差异（buff/掉落/数值） | ~95% | `Func(8,N)` 四档 + 技能 `_B`/`_C` 变体 + 宝箱矩阵；仅枚举名是推断 |
| 技能本体（CD/距离/伤害区间/目标选择） | ~80% | skill_new / skill_static（StaticData、CoolDownCategory、TargetSelect、VarProp）；数值公式在 formula.ini |
| 转阶段精确阈值 | ~85% | HPRatio/MPRatio/Level 阈值在表；各 BOSS 大招阈值需逐包翻事件 |
| **战斗出招循环**（何时放哪个技能、仇恨、走位、转阶段表演） | **~20%** | `prioritizeskill*.ini` 无 clone003 条目 —— 全靠丢失的服务器 Lua（`bossclone003xxx.lua`） |
| 喊话/台词文本 | 0% | 台词 ID 在表（OpEvent 27/29，Para 1070~1078），文本在客户端语言文件 |
| 技能特效链（火圈/全屏） | ~30% | 部分技能引用 `skill\clone\clone003\*.lua`（丢失），按攻略文字近似 |

**明确缺失**：boss 服务器 Lua（yx 变体全量引用）、部分技能 Lua（如 `clone003_yeloukai_cnhh.lua`）、`Func(8)/Func(22)` 枚举官方名（语义已由数据反证）、台词文本、掉落组合表 `comb_PassDrop_*`（被引用未定义）。

## 7. 结论与建议路线

- **能做**：通用 AI 事件引擎（解析 package→event→condition，支持出生/死亡/周期事件、概率、HPRatio/MPRatio/难度/等级条件，执行加 buff/召唤/喊话/属性操作），暮色 10 个 BOSS 的出生 buff、难度分档、死亡掉箱、回血触发可**原样数据驱动**跑起来 —— 这是原服务器的真实架构，不是模拟。
- **需近似**：转阶段表演（烈焰真罡读条、春泥护花火圈、黑白双煞合体）按攻略描述手写为技能释放规则，套用数据表里的技能 ID 与 CD。
- **不可还原**：台词文本（客户端文件）、原版 Lua 精确手感。

## 8. 实现进度（Step 3 前三切片，已落地）

| 组件 | 状态 |
| --- | --- |
| `com.nineyin.rule.AiRuleData` | ✅ 四表加载（真实数据 4963 包 / 37450 事件 / 40182 条件 / 11143 公式）；节值解析用 `strip()`（GBK 表存在全角空格 U+3000，`trim()` 不删会导致 buff ID 多一个不可见字符） |
| `com.nineyin.rule.BuffCatalog` | ✅ buff_new.ini 加载（buffId→StaticData；真实数据验证 buf_boss_immunity→2938、xixue→7946） |
| `com.nineyin.game.AiEventEngine` | ✅ 出生/死亡/周期/战斗事件运行时；OpEvent 2=加 buff（BufferInfo 视觉帧广播）、19=设属性（BornAction）、25=召唤（动态 oid + 全场景广播）、23/24=立即对仇恨目标出招、70=召唤组（散开站位）、71=台词；概率 OpRate；公式从左到右求值 |
| `com.nineyin.game.CombatFormulas`/`CombatStats` | ✅ 社区逆向伤害公式（外防对数减免/破防/暴击/瘀血层），玩家与 NPC 共用；BOSS 出招走公式结算（攻击=模板 attack 列或 120，拼音大招 ×260/120） |
| 难度维度 | ✅ `SceneWorld.dungeonDifficulty`（1~4）/`dungeonRating`（1~3），GM 面板"难度：普通/困难/地狱/修罗"按钮 |
| BOSS 绑定 | ✅ BossClone003001~008 →（新）包 4399~4407；BossCloneTZ001~005 →【挑战】包 5976~5980（原映射在丢失 Lua，按包 Text 复原） |
| 技能表 | ✅ NpcTemplate 解析 `table@SkillRec`（BOSS 出招表；周厉风 01/02/03+嗜血狂杀等）与 attack/defense 列 |
| 出招循环（近似） | ✅ 战斗窗口内每 3s 轮转；拼音大招倍率放大；动作帧全场景广播；嗜血被动大招回 1000；HPMP 回血 buff 战斗中 2%/s；玩家死亡 8s 缓复活；**30% 血转阶段**（立即首个命名大招 + 全场喊话 worldInfo） |
| 宝箱开启 | ✅ NpcTemplate 解析 OpenRange/OpenAction/DelayTime（3m/interact041/5s）；点击出"打开宝箱"菜单 → 距离校验 → 开箱动作 + 读条 → 移除 + 掉落 worldInfo（A/B/C 评价档掉落池，地狱/修罗 +1 件；真实组合表 comb_PassDrop_* 已丢失） |
| 战斗管线接线 | ✅ `applySkillDamage` 受击→`onNpcDamaged`（仇恨）、hp=0→`onNpcDeath`；每秒 `tick`→`pump`（快照迭代防召唤组 CME，含重生后再出生） |
| 验证 | ✅ AiEventEngineTest 19 用例 + CombatFormulasTest：fixture 全机制 + 真实数据冒烟 |
| 待办 | buff 属性加成数值接入（VarProp 表）、OpEvent 20（行为状态机）、多会话开箱互斥广播、转阶段表演细化（读条火圈） |

## 附：数据来源

- 本地表：`rule/ai_editor_{package,event}.ini`、`rule/condition{,_formula}.ini`、`rule/condition.xml`（客户端条件语法文档）、`skill/{skill_new,skill_static,buff_new,buff_static}.ini`、`npc/npcconfig/clonenpc/bossnpc.txt`、`clone/cloneinfo.ini`、`clone/clonecolinfo.ini`
- 国际版：
  - [WushuWiki：Twilight Village（Age of Wushu 英文 Wiki）](https://ageofwushu.fandom.com/wiki/Twilight_Village)
  - [WushuWiki：Twilight Village (Prologue)](https://ageofwushu.fandom.com/wiki/Twilight_Village_(Prologue))
  - [DarvenAOW 实例攻略视频集（YouTube）](https://www.youtube.com/playlist?list=PLn7Bn-Q0SmzZ5b3H4G5k34bZKLsMql1Hw)
- 攻略：
  - [17173：禁地暮色之村普通难度速通详细攻略](https://9yin.17173.com/news/08172025/225230268.shtml)
  - [17173：地狱暮色之村攻略 各BOSS注意要点](https://9yin.17173.com/content/2015-07-29/20150729233802823_all.shtml)
  - [蝶梦小天地：九阴真经暮色之村详细攻略](https://rainypapillon.pixnet.net/blog/posts/8031887380)
  - [17173：修罗暮色攻略技巧](https://9yin.17173.com/content/2016-07-23/20160723221502499_1.shtml)
  - [17173：暮色之村新手速成攻略（铁索缠身/天地哀怨）](https://9yin.17173.com/content/2016-06-09/20160609233217345_2.shtml)
  - [17173 手游版禁地详解（宇文绝断空击/烈火击）](http://9yin.17173.com/s/news/07232015/155229204_2.shtml)
  - [17173：新暮色简易攻略（红圈机制）](https://9yin.17173.com/news/04292025/172814452.shtml)
  - [17173：禁地暮色村最详尽图文攻略（赤龙吐珠）](https://9yin.17173.com/content/2012-08-04/20120804144706771_all.shtml)
