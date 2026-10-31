# 第二批：战斗互播（远端玩家战斗可见）

> 承接 [gameplay-player-visibility-and-item-use.md](gameplay-player-visibility-and-item-use.md)（第一批：外观/位置互播 + 道具使用）。
> 本批让同场景玩家互相看到：出招动作、技能 buff、NPC 掉血/死亡、受击/倒地/复活、打坐/招架。

## 核心设计：双发模式

**发给自己的帧一个字节不动**（oid=1 自机渲染依赖），旁边构造 **oid 版帧**发给同场景其他会话。自机行为零回归风险，peer 端增量可见。

## 三个中继原语（SceneFlow）

| 原语 | 作用 |
|---|---|
| `relayPeerFrame(self, frame)` | 玩家帧中继：同 `SceneWorld` + `peerReady` 过滤（GameScheduler 定时器闭包可安全调用） |
| `relayPeerCombatVitals(self, session)` | 战斗生命帧中继：`PlayerActor.peerCombatUpdate(oid)` 子集 |
| `broadcastToWorld(world, frame, exclude)` | NPC 帧广播（帧自带 NPC oid，血量帧幂等；exclude=已单播的攻击者） |

**peerCombatUpdate 白名单**（`PlayerActor`）：HP/HP_RATIO/MAX_HP/HIT_HP/HIT_HP_RATIO/DEAD/LOGIC_STATE/CUR_SKILL_ID(+TARGET)。**不含** CAPITAL（货币）、轻功点、BufferInfo、修炼等私有属性——`vitalUpdate()` 全量帧保持自机专用。

## 改造点清单

| 链路 | 位置 | 帧 |
|---|---|---|
| 出招动作 | `handleSkill` + `scheduleFollowupActions`/`scheduleCurrentSkillClear`（定时器闭包经 channel attr 取 session） | STATE 属性帧双发（`sendStateDual`）+ peerCombatUpdate |
| 技能 buff add | `stageSkillBuffs` | 0x1E msg169 ObjRef(oid) 版双发 |
| 技能 buff remove | `SceneFlow.tick` 到期剔除 | 169 remove + 槽位清空帧 peer 版（与 add 配对，防 peer 端 buff 栏悬死） |
| NPC 掉血/死亡 | `settleSkillHit` | 0x10 批 + 0x0E removeObject `broadcastToWorld` |
| NPC 出招 msg20 | `AiEventEngine.castSkill`（**修复既有 bug：target 硬编码 1**） | 本人帧 target=1 单播；peer 广播帧 target=受击者 oid（`Broadcaster.onBossAction` 增加 exclude 重载 → `NineyinServer.broadcastFrame(world,frame,exclude)`） |
| NPC 打玩家 | `castSkill` 受击/死亡/复活、`sendBossHp`、`regenTick` | `peerVitalsRelay` 回调（`AiEventEngine.setPeerVitalsRelay` ← `SceneFlow::relayPeerCombatVitals`） |
| 内功回血/护盾 | `NeigongRuntime.executeSelf` | 同上 relay（`setPeerVitalsRelay` wire 于 `SceneFlow.setScriptHost`） |
| 打坐/取消 | `handleSit`(240) + 移动取消 | STATE + LOGIC_STATE 双发 |
| 招架 | `handleManualParry`(54) | 169 BuffInParry + IN_PARRY/IN_MANUAL_PARRY 属性帧 peer 版 |

## 测试（CombatVisibilityTest，5 用例）

- peerCombatUpdate 头结构（0x10/oid@2/owner@6/子集≥8 项）
- 属性白名单：必含 HP/DEAD/LOGIC_STATE 等；**不得含** CAPITAL/轻功点/buff 槽
- 受击/死亡帧变化跟踪（applyDamage 后帧变化、打空 DEAD=1）
- msg20 target 参数化（peer 版含分配 oid、自机版不含）
- STATE 4 参/2 参构造一致性（仅 oid 字节差异）

全量回归：**253 tests / 0 failures**（基线 248 + 新增 5）。

## 实机验证清单

双开同场景 A/B：
1. A 打 NPC → B 看到：A 出招动作（zhaoshi 系列 STATE）、NPC 掉血、NPC 死亡消失
2. A 开带 buff 的技能 → B 端 A 头顶 buff 图标出现/到期消失
3. NPC 打 A → B 看到 A 掉血、倒地（DEAD）、8 秒后复活站起
4. A 打坐/按住右键招架 → B 可见姿态变化
5. GM `spawn_dummy` 后 NPC 攻击 dummy 场景（若可选中）核对 msg20

## 边界与后续（第三批候选）

- 伤害飘字：客户端从施法者动作自渲染，无需服务端帧（维持现状）
- 被控状态（定身/眩晕）peer 表现帧、msg218 主动招架帧
- 玩家互打（PvP 伤害结算走 settleSkillHit 的玩家目标分支——当前仅 NPC 目标）
- BufferInfo 槽位 peer 同步（当前只发 169 事件帧）
