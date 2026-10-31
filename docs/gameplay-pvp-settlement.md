# 第三批：PvP 伤害结算（玩家互打闭环）

> 承接 [gameplay-combat-visibility.md](gameplay-combat-visibility.md)（第二批：战斗互播）。
> 本批打通：选中远端玩家 → PK 门控 → 伤害/招架/控制/死亡/复活全链路。

## 判据与解析

**ownerId 无法区分玩家/NPC**（NPC 默认 owner 也是 1），唯一可靠判据是 oid 区间：

```java
static boolean isPlayerOid(int oid) { return oid >= 500001 && oid < 900001; }
```

`resolvePlayerTargetByOid(st, oid)`：遍历 sessionsSource，`playerObjectId` 匹配 + **同 world** + `actor.hp > 0` ——位置/血量实时，且天然支持多跳波次重解析（目标死亡/切图后空结算）。

## PK 门（tips_pkmode 语义，文本抽取 rules-and-data.md §7）

`pvpGateMessage(attacker, victim)` → 非null即拒（单播系统提示）：

| 条件 | 行为 |
|---|---|
| `attacker.pkMode == 0`（和平） | 拒绝：「和平模式下无法攻击其他侠士，请先切换攻击模式」 |
| `victim.pkProtectOn` | 拒绝：「对方处于保护状态，无法攻击」 |
| pkMode 1（善恶）/ 2（杀戮） | 放行 |

恶人值/红名后果不在本批。PK 模式切换帧（msg291 → PKMode 436 属性）已有。

## 结算链（`settlePlayerHit`，照 AiEventEngine.castSkill 同构）

```
① 竞态复查（victim.hp > 0）
② 招架判定：victim.parrying && parryEndurance > 0 → drainParry(攻方面板攻击)
③ 公式：formulaHost.calc(攻方.combatStats(), 受方.combatStats(), ratio, combatRng, parrying)
   —— 双方全属性生效：装备词缀/内功被动/限时道具属性（第一、二批成果在此汇合）
④ 内功受击钩子：adjustIncomingPlayer（attacker 以 oid+名字泛化），
   反弹伤害经 reflectSink 落回攻击者（damagePlayer 同构：瘀血/死亡/帧）
⑤ applyDamage（25% 瘀血分层免费获得）
⑥ damagePlayer：victim vitalUpdate（自机）+ relayPeerCombatVitals（同场景 peer，
   含攻击者——A 打 B，A 端看到 B 血条变化）
⑦ 死亡：8s 复活调度（revive + 双发）
```

- **多跳波次**：`settleSelectedPlayer` 每波按 oid 重解析（波次闭包 pvpTargetOid 分支），castSeq/hp 作废条件沿用。
- **出招打人动画**：起手 `nativeSkillAction(攻方oid, 1, skillId, 受方oid, 1)` `broadcastToWorld`（排除攻击者自机）。
- **控制**：TARGET_CONTROL 效果对玩家走 `applyControlToPlayer`（装备免疫位闭环），纯控制技在效果循环按 oid 重解析。

## NeigongRuntime 泛化

`adjustIncoming` 重构为 `adjustIncomingCore(attackerOid, attackerConfigId, reflectSink)`：NPC 入口包装 `reflectToNpc`；新增 `adjustIncomingPlayer` 入口（反弹交给调用方落攻击者）。on_parry/on_victim 的 Lua 钩子 ctx 不变（attacker.oid/config_id 参数化）。

## 选中体验（SelectFlow）

选中远端玩家（`npcOf` 查不到 + `isPlayerOid`）→ `SceneFlow.onPlayerSelected`：
- 回填 `selectedX/Z`（受方实时坐标）——**顺带修复**选中玩家后坐标恒 0 导致 28m 自动清除永不生效的问题；
- 单播 `peerCombatUpdate` 补正出场帧假血量（1000/100%）——选中即见真实血条。

## 测试（PvpSettlementTest，9 用例，全绿）

oid 区间边界（500000/500001/900000/900001）、PK 门三分支、目标解析（同 world/异 world/死亡/未知）、settle 伤害入层+双端帧、固定掷骰 200 次内致死+死亡后空结算、招架耐力消耗、选中坐标回填+血条帧。
（双 EmbeddedChannel 裸会话 fixture + `useCombatRngForTest` 固定种子，照 AiEventEngineTest 确定性套路。）

全量回归：**262 tests / 0 failures**（基线 253 + 新增 9）。

## 实机验证清单

双开 A/B 同场景：
1. A 切 PK 模式 1（客户端 PK 按钮或 GM）→ 选中 B（真实血条）→ 出招：B 掉血、A 端 B 血条同步、B 瘀血分层；
2. B 按住右键招架 → 伤害减免 + B 招架耐力消耗；
3. A 连招至 B 死亡 → B 倒地（A 可见）、8s 后复活站起；
4. A 切回和平模式出招 → 系统提示拒绝；B 开 PK 保护 → A 被拒；
5. 内功带反弹的角色被 PvP 攻击 → 反弹伤害落攻击者。

## 边界与后续

- 区域技打玩家（`areaTargets` 只扫 NPC，需另扫同场景玩家会话）；
- 恶人值/红名/死亡掉落/切磋保护；
- PvP 战斗互播的 msg20 已含（出招帧 target=受方 oid）。
