# 玩法第一批实现：玩家互播 + 道具使用效果

> 依据：`docs/text-extract/`（客户端文本知识抽取）+ `share/` 配置数据。
> 2026-10 第一批，两个玩法包 + GM 目验辅助。

## A. 玩家互播（多人可见性）

此前所有玩家 objectId 恒 `PLAYER_OBJECT_ID=1`，会话间只播 NPC 帧——社交/PvP/交易的总瓶颈。

**设计**：
- **oid 分配**：`LoginSession.playerObjectId`，`SceneFlow.sendSceneNpcs` 首进场景时从 **500001** 递增分配（避开 NPC 静态段 10001+ / 动态段 900001+）。
- **会话视图映射**：每个客户端看**自己恒 1**（自机渲染/输入绑定不动），看**别人用其分配 oid**。
- **帧**：`S2CPackets.playerAddObject(objectId, ownerId, ...)` 重载（0x0D 帧结构不变，仅 oid 参数化；外观属性 181-394 复用建角数据）。

**钩子**（均在 `SceneFlow`）：
| 时机 | 位置 | 行为 |
|---|---|---|
| 进场景完成 | `sendSceneNpcs` 尾部 | 双向互播 add+location（`announcedPeerOids` 幂等防 GM 重发重复帧） |
| 移动 | 0x0A 67 字节位置包（`relayPeerPosition`） | 150ms 节流向同场景其他会话转发 `serverLocation(oid)` |
| 离场 | `sendSceneNpcs` 头部（切图）+ `NineyinServer.onDisconnected`（断线） | 同场景同伴收 0x0E `removeObject(oid)` |

同场景过滤条件：同 `SceneWorld` && `npcsSent` && 非 `awaitingReentryReady` && 双方均已分配 oid。

**GM 目验**：`spawn_dummy` / `despawn_dummy`（oid 800001 段）——单客户端注入假玩家帧；前端 console.html 已有按钮（带 dummyHp 时补发 HP 464/MaxHP 577 属性帧）。

## B. 道具使用效果（msg31）

此前 `handleBodyItemUse` 只处理穿戴，不可穿戴一律拒绝。现分三层：

```
msg31 [31, view, slot, ...]
 └─ Java（不进脚本）：背包定位 / 800ms 节流 / 效果落账 / 消耗与帧配对
     ├─ ① scripts/item/<configId>.lua 的 M.on_use(ctx)   ← 效果逻辑（热重载）
     └─ ② INI 回退（无脚本时）：
         giftbox.ini [configId].DropID → drops_mvp.json 权重开包
         item_use_skill_static.ini [item_type]（如 2101→yaopinhp1 L1）→ 回血/回蓝阶梯
```

**Lua 指令集**（`ItemScriptBridge` 白名单解析，随机数 Java 预掷进 `ctx.rand`）：

```lua
return {
  heal_hp = 300, heal_mp = 100,                 -- 回血/回蓝
  open_box = { drop_id = "comb_xx", rolls = 1 },-- 权重开包（Java 解析 drops_mvp.json）
  grant_items = { { id = "sd_001", amount = 2 } }, -- 确定性给物品
  temp_stat = { attack_add = 25, defense_add = 0, max_hp_add = 0, life_ms = 86400000 }, -- 限时属性（同源替换）
  consume = true,                               -- 默认 true；false = 二次使用卸下类
}
```

ctx：`item_id/amount/now/self{hp,max_hp,mp,max_mp,silver}/rand{r_drop,r_proc}`。

**落账链**：给物品走既有 `giveItem` 容量感知路径（满则中断+日志）；回血/回蓝改 `actor.hp/mp` + `vitalUpdate`；`temp_stat` 进 `PlayerActor.tempStats`（并入 `combatStats()`/`maxHp()`，tick 每秒过期自清）；成功后 `consumeBagItem`（堆叠感知：减量或整行删）+ `viewRemove/bagViewAdd` 帧对 + 镜像页清理。

**样例脚本**（`scripts/item/`）：`yaopin_sc_1001.lua`（祛风散回血锚点）、`useitemhs_dy_06.lua`（飞云剑匣·金 攻+25/24h）、`box_chinajoy_2026_giftbox.lua`（惊鸿礼盒开包）。

**关键组件**：
- `game/ItemUseCatalog.java`：giftbox（UTF-8）/item_use_skill_static（GBK）分节解析 + 编码探测 + 回血阶梯兜底（L1=100…L9=3000，回蓝减半；skill_new 无 yaopinhp* 数值条目的文档化默认）。
- `game/DropPacks.java`：drops_mvp.json（5326 包）解析 + 权重抽取（`DoubleSupplier` 注入，确定性可测）+ 内置极简 JSON 读取器。
- `db/RoleInventoryStore.consumeBagItem(n)`：FOR UPDATE 事务减量/删行；`itemTypeOf(configId)` 带内存缓存。
- `script/ScriptHost`：新增 `item/` 域扫描与 `itemModule()`；`reload_scripts` 自动生效。

## 实机验证清单

1. 双开客户端同场景：互看外观/名字、移动同步（≤150ms）、切图后对方消失、断线后消失。
2. GM `give_item yaopin_sc_1001` → 右键使用 → 血量 +100、数量-1（堆叠>1 时减量帧对）。
3. GM `give_item box_chinajoy_2026_giftbox` → 右键 → 按 drops_mvp 权重开出 yaopin_00100/00300/00500/00700 之一。
4. GM `give_item useitemhs_dy_06` → 右键 → 面板攻+25，24h 后（可 GM 改系统时间/等待）自清。
5. GM 面板 spawn_dummy → 身旁出现"木桩侠 N"假玩家（血条按 dummyHp）；despawn_dummy 清除。
6. 修改任意 scripts/item/*.lua → GM `reload_scripts` → 效果即时变化（无需重启）。

## 边界与后续

- msg34/71（道具动作表）仍保持日志（参数形状未逆向钉死，31 已覆盖右键使用主链）。
- 疾病治疗（祛风散的"治一重风类疾病"）待疾病系统实装——脚本已留注释锚点。
- 换装类（易容军装）未做；`game_item_effects` 表保留作将来 DB 覆盖导入层。
- 远端玩家暂只同步外观+位置；出招/受击/属性帧互播（`AiEventEngine`/`settleSkillHit` 的 PLAYER_OBJECT_ID 出口参数化）为第二批。
