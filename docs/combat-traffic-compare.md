# 战斗流量对拍：参考服 exe vs Java 服（2026-11-04 晚场）

数据源（**均不含 bak**，wire 级抓包逐帧解码）：

| 服 | 会话 | 时间 | 帧 c2s/s2c | 出招 | 技能 |
|---|---|---|---|---|---|
| 参考服 | `9yin_server1\captures\local\127-0-0-1-55866` | 11-04 20:55–20:57 | 490 / 4448 | 211×9 → 受理 8 | myjf01/02/03/08（君子堂明玉剑） |
| Java | `9yin_server_new\captures\local\127-0-0-1-58278` | 11-04 20:59–21:02 | 939 / 7062 | 211×54 → 受理 29 | myjf01–05/08 |

两服玩家打的是**同一套套路**（CS_jh_myjf 族，UseLimit=2 单手剑），目标同为 1000HP 普怪，可直接逐项对齐。
工具：`tools/smoke/combat_compare.py`（census / casts / c2s / detail，复用 capture_deep 解码）。
日志佐证：参考服 `logs\game-19061.current.log`、Java `logs\nineyin-java.log`（21:00 段 accepted/reject 行）。

## 1. 会话战斗帧型普查

| 帧/消息 | 参考服 55866 | Java 58278 | 说明 |
|---|---|---|---|
| C2S 211 出招 | 9 | 54 | Java 侧大量射程拒绝（log: `out of range 11m > 8m`） |
| C2S 431 选目标 | 8 | **164** | Java 侧反复点目标（0x07×158 vs 参考×7） |
| C2S 54 格挡 | 31 | 32 | 基本一致 |
| C2S custom 15 坐标 | 127 | 99 | 一致（8/9/11 值形态相同） |
| 0x11 cooldown_rec | 16 = 8×2 | 58 = 29×2 | **每招恒两行（个人+GCD），两服一致** |
| 0x11 active_skill_rec | +8 | +29 | 一致 |
| 0x12 删 active_skill 行 | **8（全配对）** | **18（缺 11）** | 见 §5-D8 |
| 0x13 fight_relation_rec | 19 | 66 | 累计伤害格子，两服同构（21B 布局一致） |
| 0x1E msg337 位置回显 | 38 = 8玩家+30NPC | 29 = 全玩家 | 参考 NPC 出招也发 337 |
| 0x1E msg20 动作 | 38 同上 | 29 | 同上 |
| 0x1E msg24 伤害飘字 | 30（三种形态） | 66（仅玩家形态） | 见 §3 |
| 0x1E msg205 命中确认 | 13（< 玩家击 19） | **66（与 24 恒配对）** | 见 §5-D14 |
| 0x1E msg18 hurt_half | 19（全 NPC 受害者） | 66 | NPC 打玩家不出 msg18（参考服行为） |
| 0x2D 受击特效 | 26 | 66 | 布局一致（2/28/112 常量同） |
| 0x1E msg169 buff | 46（6 种） | 32（仅 BuffInParry） | 见 §5-D4 |
| 0x1E msg294 对钟 | 123（~1s） | 170（~1s） | 一致 |
| 0x1E/0x27 msg414 | reset×5+sync(204)+gamestep | reset×2 | 见 §5-D12 |
| View40 CanUse 行 | 1632 行全 =1 | 920×1 + 691×0 | 见 §5-D7 |
| 0x10 面板 | 2034 | 2325 | 出招面板 186 vs 185 项 |
| 0x1F location | 5 | **1141** | 见 §5-D16 |
| 0x21 ServerAllDest | 172 | 495 | 批量移动帧两服都在用 |
| 0x38 心跳 | **115** | **0** | 见 §5-D11 |

## 2. 受理出招的 S2C 帧序（两服模板）

**参考服**（窗口#0，myjf03，#1969–1998）：

```
+active_skill_rec(技能ID) → +cooldown_rec(GCD 50000) → panel 玩家 n=186(出招瘦面板)
→ msg337(施法者坐标/朝向回显 211 原值) → msg20(技能动作, 目标=aim 对象)
→ 每跳每目标: panel NPC n=6(HP/MaxHP/HPRatio/LogicState/Dead+Posi/Orient)
   → 0x13 fight_relation(累计伤害) → msg24(17,伤害,…) → 0x2D → msg18(hurt_half) → [msg169]
   → [msg205] → (1s 后下一跳)
→ 0x12 删 active_skill 行 → panel n=6(技能态清除: CurSkillID 清空+CanNoLimit/CantBe*Locked 归零)
→ panel n=1 State="stand" → panel n=186 复位
→ 每 ~2s: View40 17 行 CanUse=1 批
```

**Java**（窗口#0，myjf02，#4202–4260）：

```
+active_skill_rec → +cooldown_rec(GCD) → View40 行 CanUse=0 → panel n=185
→ msg337 → msg20 → panel n=1(LogicState=1 首击) → [同款每跳五连: panel n=5 → 13 → 24 → 2D → 18 → 205]
→ 0x12 删行(11/29 缺失) → panel n=6 清除帧(六项同款) → (无 State=stand, 已知故意停发)
→ CanUse 0/1 混合批(在 CD 行=0, CD 尽=1)
```

**结论：出招链主干（CD 双行→active→面板→337→20→五连命中→删行→清除帧）两服同构同序**，差异全在细节值与缺席帧（§5）。

## 3. msg24 三种形态（参考服全有，Java 只有第一种）

| 形态 | 布局 | 参考 | Java |
|---|---|---|---|
| 玩家→NPC（c1=17, 10 值） | `[24, obj(受害NPC), 17, 伤害, 0, 0, 技能ID, script名, 受害者模型, 宽串攻击者名, 0]` | ×19 | ×66 |
| NPC→玩家（c1=7, 9 值） | `[24, obj(玩家), 7, 1, obj(NPC), NPC模型, 宽串玩家名, 宽串NPC名, 20]` | ×3 | **×0** |
| 武技连击段（c1=71, 10 值） | `[24, obj(受害), 71, obj(第二目标), '', 1, obj(攻击者), 30.0, 0.0, wuji技能名]` | ×8 | **×0** |

- NPC→玩家形态实伤 1（面板 20 名义值经防御折算），受害玩家照样有 0x2D，但**无 msg18**（两服 msg18 受害者全为 NPC）。
- c1=71 只在 myjf08（`LevelupRelevance=wuji_CS_jh`）出招时出现：先空名段，后 `wuji_CS_jh_myjf08_2/_3/_4`，可跨目标（#2622 受害 378 目标 379）。

## 4. CD / 怒气 / 伤害数值对拍

### 4.1 CD（0x11 cooldown_rec，两服完全一致）

| 技能 | 个人类别 | 个人时长 | GCD | GCD team |
|---|---|---|---|---|
| myjf01 | 11021 | 10000ms | 7000ms | 50250(0xC48A, i16 显 -15286) |
| myjf02 | 11022 | 10000ms | 7000ms | 同上 |
| myjf03 | 11023 | 6000ms | 7000ms | 同上 |
| myjf04 | 11024 | 15000ms | 7000ms | 同上 |
| myjf05 | 11025 | 2000ms | 7000ms | 同上 |
| myjf08 | 11028 | 10000ms | 7000ms | 同上 |

个人行 team 恒 0xFFFF(i16 显 -1)。行首时戳=绝对 Unix 毫秒，客户端靠 msg294 对钟。
**注意：两服都未把 7s GCD 当硬闸**——参考服最短受理间隔 3.4s、Java 最短 1.0s（GCD 行照发 7000ms）。参考服 8 招间隔 [13.1,10.6,7.6,19.1,5.7,3.4,17.5]s，Java 29 招多为 1–3.5s。Java 的实际拒绝原因是射程（8m 闸）。

### 4.2 怒气 SP 经济（**语义不同**；2026-11-06 勘误，详见 docs/rage-recovery-frames.md）

| | 参考服 | Java |
|---|---|---|
| myjf08 消耗 | **扣 50**（65→15、55→5，走事件触发 vitals 大批帧；旧读"清空"实为格挡清怒误判） | 扣 50（100→50）✓语义同 |
| 格挡清怒 | **msg54 格挡 ON → 任意值清 0**（18B 单帧 SP=0 + InParry/InManualParry 小批 + BuffInParry 169 删/加） | 缺 SP=0 单帧 |
| 恢复 | **时间回复 +5/s**（0→100 恒 20 tick，锁 msg294 秒栅格，与战斗态无关；满值停推；帧形=n186/188 vitals 大批） | 命中回怒 +2/击（无时间回复） |
| 被击 | `msg24 [24,玩家,10,n]` 4值短形态 + 单帧 SP+2；NPC c1=7 实伤不给 SP | 无 |
| 证据 | SP 轨迹 `18107:0 19107:5 … 38107:100`（每秒+5，与命中无关） | `137748:50 →52,54,56…`（每次命中+2） |

### 4.3 伤害

| | 参考服 | Java |
|---|---|---|
| 数值 | 每技能**恒定**：myjf01=121、myjf03=142、myjf08=437×4 跳 | 每次掷骰：myjf02=16–22、myjf03=51–72、myjf01=18–24、myjf08=88 |
| 飘字 vs 实扣 | 飘字 142，NPC 实扣 129（有折减差） | 飘字=实扣（22/22） |
| fight_relation | 每跳累加同飘字值（142→284） | 同构（22→44→66） |

## 5. 差异清单（按影响排序）

### D1 NPC 不会出招反击（ gameplay 最大差 ）
参考服两只怪（obj 0x11000378/79，monv030101「追风士」）全程用 `jn_mon053_02` 反打玩家：30 次 msg20+msg337（目标=玩家）、3 次 msg24 c1=7 命中（实伤 1）、0x2D 同步。Java 侧 msg20/337 全部来自玩家（0 次 NPC 出招），怪物是纯木桩。
**Java 缺整套 NPC 战斗 AI 出招链**（记忆库"战斗 AI 移动走 0x21"只覆盖了移动）。

### D2 myjf08 武技连击（wuji）系统缺失
参考服 myjf08 一招 = 437×4 跳 AOE + c1=71 武技段 ×4（跨目标）+ 每跳：
- msg169 `[1, buf_CS_jh_myjf08, NPC, 玩家, '', 玩家名, unixms, 0]`（给 NPC 上 debuff，**带过期毫秒**）
- msg169 `[0, buf_CS_jh_myjf08_01, 玩家, 玩家, …]`（移除自身 _01 增益）
- 击杀后 msg169 `[1, buf_fixed_red, 玩家…]`（×2）
数据源：skill_static `LevelupRelevance=wuji_CS_jh`（myjf03/myjf08 有此字段）。Java 侧 myjf08 只有一发 msg20（目标还是空的）+ 0–1 跳 88 伤害，无 169、无连击。myjf03 出招时也发 `[0, buf_CS_jh_myjf03_01]` 移除型 169。

### D3 锁定判定：TargetType=4 才是权威（**修正 combat-skill-211.md §13**）
同一发长格式 211（都带 aim obj），msg24 的 script 标签与 msg20 动作目标：

| 技能 | skill_new.script | CanRush | TargetType | 参考服 wire | Java wire |
|---|---|---|---|---|---|
| myjf01 | SkillNormal | 0 | 4 | **SkillLock + aim** | SkillNormal + 空 |
| myjf02 | SkillLock | 1 | 4 | —（未出） | SkillLock + aim |
| myjf03 | SkillLock | 1 | 4 | SkillLock + aim | SkillLock + aim |
| myjf04 | SkillNormal | 0 | 4 | （预测 lock+aim） | SkillNormal + 空 |
| myjf05 | SkillNormal | 0 | 8 | （预测空） | 空（恰好对） |
| myjf08 | SkillNormal | 0 | 4 | **SkillLock + aim** | SkillNormal + 空 |
| yqq01（§13 钉死） | SkillNormal | 0 | 8 | 空 | — |
| yqq02（§13 钉死） | SkillLock | 1 | 4 | aim | — |
| wuji_hyjf08（§13） | SkillNormal | 0 | 4 | aim | — |

**规则修正：msg20 动作目标 = aim 对象 ⇔ `skill_static.TargetType==4`（单体目标型）**；自增益（IsDamage=0）仍= 自己；TargetType≠4 伤害技=空。script 字段/CanRush 只是部分重合的旁证。Java 按 §13 旧规则判，myjf01/04/08 全部发空目标 → 客户端不播锁定/冲锋动作（"伤害出了人没过去"同族根因，此三技能是漏网）。

### D4 出招 buff 的 169 下发
参考服出招路径**有** msg169（myjf08 加/删、myjf03 删 _01、buf_fixed_red），与 2026-11-03 "面板内嵌无 169" 的取证相反（不同 build/不同技能）。Java 出招路径零 169（仅格挡 BuffInParry×32）。此条与 D2 绑定（buff 本身就没实现全）。

### D5 怒气经济（§4.2，2026-11-06 勘误）——参考服=格挡清怒(单帧 SP=0)+怒招扣50+时间回复5/s(锁294栅格、满值停推)；Java 消耗-50 碰巧对齐，但缺：时间回复、格挡清怒单帧、被击+2。逐帧结论见 docs/rage-recovery-frames.md。

### D6 伤害模型（§4.3）——参考服按技能恒定+折减差；Java 逐次掷骰、飘字=实扣。

### D7 View40 CanUse 行为
参考服：仅快捷栏 **17 行**，值**恒 1**（本会话 1632 行无一 0；官方只在 AConsumeQGP 行出 0，本套技能无）。Java：行号到 **82**（learned=193 全推），值 0/1 混合（施放置 0、CD 尽置 1）——是 §9.4 的"实现对齐后"故意行为，但行范围超出官方（官方只推快捷栏行）。

### D8 active_skill_rec 删行缺失
参考服 8/8 全配对。Java 29 加 18 删，**11 个窗口无删行也无清除帧**（窗口 3,4,7,10,14,17,18,22,23,24,26，全部是 myjf01/02/03）。窗口尾部直接接下一招的个人 CD 行，疑似收尾定时器被跳过/取消。影响面待真机确认（客户端技能行残留）。

### D9 面板属性集
- 出招面板：参考 186 项 vs Java 185 项，唯一差 `SelectSchool`（Java 的 CAST_PANEL_NAMES 漏收）。
- NPC 命中小面板：参考 n=6（多 `Orient/PosiX/PosiY/PosiZ/Prop21`，57B）vs Java n=5（36B）。死亡面板两服同款 `LogicState=120 + Dead=1 + HP=0`（双发）。

### D10 收尾 State="stand"：参考服发（n=1 单属性帧），Java 停发（2026-11-02 故意，§9 记录在案）。

### D11 0x38 心跳：参考服 S2C 推 115 帧（0x38 对钟/心跳族）；Java **0 帧**。对应 Java 侧"登录种子+2s 推送默认停发"开关——wire 级确认当前部署为关。
**勘误+已修（2026-11-05）**：55866 c2s 复核——115 帧 0x38 全是**客户端 0x18 ping（~1s 一发 12B：uptime u32+seq u16）的回包**，载荷原样回显、严格成对，非服务端周期推送。本服 60760 会话 83 ping↔0 回（dispatcher 无 case 静默吞）→ 客户端 RTT 配对恒超时、时序补偿劣化（真机：进游戏后出招特效要等一段时间才出现；2026-10-31 旧仓同根因=HUD 延时恒 3000ms 封顶）。修复=`LoginFlow.onPing` → `HeartbeatMessages.heartbeatReply`（30B，menu2 exe heartbeatReply 逐字节移植版，仅按 ping 回）。

### D12 msg414：参考 reset×5 + sync(204 小号) + gamestep；Java 仅 reset×2，无 sync/gamestep。

### D13 格挡 169 节奏：参考服只在**值边沿**回 BuffInParry（31 发→20 回，序列与边沿完全一致）；Java 逢 54 必回（32/32）且出现连续同值（`…1,1,0…`）。

### D14 msg205 配对率：参考服玩家击 19 中只 13 发 205（部分跳不发）；Java 66/66 恒配对。参考服 NPC 击发 24 不发 205。

### D15 标识与串格式
- 玩家 obj：参考 `(0x11000001, 0x7A5C0B)` vs Java `(0x1, 0x1)`；NPC：参考 `0x110003xx, owner=1` vs Java `0x5b8+`。
- msg24 受害者模型串：参考 `monv030101`（裸模型名）vs Java `npc\monster115`（路径型）。

### D16 移动流量形态：Java 0x1F×1141（每 NPC 物化各 6 帧）vs 参考×5；0x21 批 495 vs 172。非战斗语义但同会话可见。

### D17 逻辑态：两服玩家首击进战斗（LogicState 0→1）；参考服 10s 闲时退战（1→0 ×2 次），Java 会话内未见退战（战斗连续，无法判优劣）。NPC 死亡 LogicState=120 两服一致。

## 6. 完全一致的项（已核实）

CD 双行结构与数值（§4.1 全表）、GCD team、active_skill 加行、msg337 回显（含空中坐标）、msg20 五值形态、
msg24 玩家形态布局、0x2D 全字段（含 2/28/112 常量与双 HPRatio）、msg18 hurt_half、0x13 fight_relation 21B 布局与累计语义、
出招面板瘦集主干（185/186）、n=6 技能态清除帧六项、msg294 1s 对钟、格挡 169 值语义（0/1 与 C2S 同值）、
死亡面板（120/Dead=1/HP=0 双发）、C2S 211 长格式（两服玩家都发 14 值长形态）。

## 7. 修复建议（优先级）

1. **D3 锁定判定改 TargetType==4**（一行判据修正，解锁 myjf01/04/08 的冲锋/锁定动作，连带 msg24 script 标签）。
2. **D1 NPC 战斗 AI 出招**（怪反击：msg20/337/24(c1=7)/2D，技能从 creator/Npc 模板配）。
3. **D8 active_skill 删行丢失**（11/29，查收尾调度为何被跳过）。
4. **D2 myjf08 武技连击 + buff 169 族**（LevelupRelevance=wuji_* 触发；含 NPC debuff/_01 自增益/buf_fixed_red）。
5. **D5 怒气经济对齐**（-50 消耗已对；补时间回复 5/s tick、格挡清怒 SP=0 单帧、被击+2）——帧契约见 docs/rage-recovery-frames.md §8。
6. 小项：D9 SelectSchool 补入白名单、D7 CanUse 行范围收敛到快捷栏、D13 格挡边沿去抖、D14 205 配对率（低风险维持现状亦可）。

## 8. 复现

```
python -I tools/smoke/combat_compare.py census  <ref_stem> <java_stem>
python -I tools/smoke/combat_compare.py casts   <stem> [窗口数]
python -I tools/smoke/combat_compare.py c2s     <stem>
python -I tools/smoke/combat_compare.py detail  <stem> <起> <止>
# stem = F:/Game/Online/9yin/9yin_server1/captures/local/127-0-0-1-55866 等
```

## 9. 冲锋"先冲后放"机制（2026-11-04 深夜 IDA 取证，9yin-game-native-menu.exe）

用户真机反馈「按冲锋技能原地出招、应先冲向对手接近后才放」→ IDA 反编译破案，
**冲锋是服务端暂存（staged）机制**，Java 此前完全缺失：

### 9.1 参考服链路（函数级）

```
211(冲锋技, 选中目标距离 > RushRange)
 └ handleSkillCustom (0x140481f60)
    门控: skill.CanRush && RushRange>0 && script=="SkillLock"(EqualFold)
          && skillTargetOutOfRange(dist>RushRange, 选中目标) && combatTargetTransform 存在
    └ stagePreCast (0x140473260):
        stagePendingCast(技能,目标,30s 窗口)          ← 不发 CD 行、不扣 MP/SP
        + castSkillIDFrame: 0x10 单属性 CurSkillID
        + msg25 ["skill_pause", 30000]                ← 客户端进入冲锋等待（倒计时源）
        + msg24 c1=61 [obj(玩家),61,f32 destX/Y/Z,2,"qg_fastruning_s_2h_01"] ← 起跑
        + markRushInFlight；直接 return
客户端起跑（qg_fastruning 疾跑动画）→ 到达目标身边
 └ C2S msg212 [212, f32 x, f32 y, f32 z, (f32 朝向)]
    └ handleCastDashLanding (0x14041b440):
        clearRushInFlight + updatePosition
        + msg337 [obj(玩家), x, y, z, 朝向, 0]        ← 落地坐标回显
        + consumePendingCast(now)（30s 过期作废）
        + msg26 []（空值，解除 skill_pause）
        + 重组 211 值列表（10 值短形：[211,技能,x,y,z,朝向,0,0,0,""]，无目标对象
          ——目标回落选中态）重入 handleSkillCustom → 此时已在身边 → 正常出招全链
```

- `RushTarget`/`PendingSkill` 两个属性名**不在 0x09 协商表**——exe appendNPCProperty
  编码失败静默跳过（stagePreCast 有容错分支），Java 同样不发。
- 距离 ≤ RushRange 的冲锋技**直接正常出招**（msg20 带目标，客户端本地冲锋动画，§13 旧路径）。
- 非 CanRush 技能 exe 未见射程闸（"skill range gate" 日志仅在冲锋分支）——Java 的 8m
  静默拒是自设闸，已对锁定族放宽到 15m。

### 9.2 Java 落地（同日）

`SkillCastHandler.stageRushCast/handleDashLanding` + `PlayerActor.stagePendingCast/
consumePendingCast/rushInFlight` + `FightMessages.skillPauseFrame/skillPauseClearFrame/
dashRushFrame` + `LoginFlow case 212`。测试 `RushStageFlowTest`（暂存帧序/落地续招/
30s 过期作废三段，生产树门控）。

## 10. CD 下发结论（IDA 取证 + 字节级对拍）

- 个人行 = `skillCooldownFrameWithDuration`：`[CoolDownCategory, start(UnixMs),
  start+个人CD(UnixMs), team=int16恒-1]`。
- GCD 行 = `skillTaoluSwitchLockRow`（经 taoLuSwitchCooldownFrames）：
  `[50000, start, start+AddCoolDownTime, team=CoolDownTeam]`。
- **Java 的 CD 双行 wire 与参考服逐字节一致**（55866 vs 58278 复核：个人 team=0xFFFF、
  GCD team=50250、每招恒两行）——CD "有问题"的体感不在 CD 行，候选根因：
  ① 冲锋链缺失（按下无反馈被当 CD 问题）② CanUse 行为差异（§5-D7）③ View40 行
  CoolDownTime 显示源个别技能未对齐（§10 旧案）④ msg414 sync 缺失（§5-D12）。

## 11. 打坐（待查）

`zs_default_01` 字符串仅 `grantSitcrossShortcut`（快捷键授予）引用；240 切坐路径已
按 Go 对齐（52905 对拍）。打坐同类问题疑在 CD 显示源/UseLimit=9 战斗拒的体感，
未做专项 IDA 取证——下次真机反馈带具体症状再挖（handleCustom 中 240 分支 +
grantSitcrossShortcut 0x14045ece0 是入口）。

