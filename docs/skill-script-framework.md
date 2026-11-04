# 技能脚本框架（scripts/ Lua 契约）

`scripts/` 与 share 同法经 `ServerPaths`（env `NINEYIN_SERVER_ROOT` → 属性 → cwd 探测）定位；
引擎为 party.iroiro.luajava 4.1.0（lua55 natives，单 Lua 状态 + 全局锁——脚本是纯函数）。
客户端方言为 Lua 5.1，引擎对齐方案（lua55→lua51，4 文件 ~12 行，未执行）见
[lua-dialect-51.md](lua-dialect-51.md)。
**缺目录/natives 失败/执行异常 → 伤害公式回退 Java `CombatFormulas`、技能覆盖回退 INI 默认编译，
出招链路永不因脚本层不可用而拒绝**。

## 目录

| 路径 | 契约 | 状态 |
|---|---|---|
| `combat/formula.lua` | `M.calc(c) → {damage, miss, crit, parried, reduction_pct}` 伤害公式权威实现 | 战斗一期已接 |
| `skill/<技能ID>.lua` | `M.compile(def) → overlay` 编译期增量覆盖 | 战斗一期已接（6 个） |
| `neigong/buf_ng_*.lua` | `M.levels` / `M.on_hit(ctx)` / `M.on_be_hit(ctx)` 内功 buff 特效 | 待接（战斗二期 hook） |
| `item/*.lua` | useitem/box/yaopin 使用物效果 | 已接（msg31 使用链，2026-11-04） |

## formula.lua（伤害公式）

输入 `c`（只读 table，Java 预填）与输出字段见脚本头注释；要点：

- `rand = {r_miss, r_crit}`：**随机数由 Java 预掷**传入（脚本不掷骰 → 可测试、与 Java 兜底逐位一致，
  `LuaFormulaParityTest` 网格对照钉死）。掷骰顺序：未命中 → 暴击（招架不免疫）→ 招架穿透 → 平减 → 保底 1 点。
- `ratio`：技能倍率 = 编译伤害/100/波数（`CombatSkill.damageRatio()`，damage_calculate.ini r 段求和）。
- Java 兜底 `CombatFormulas`（`server/script/`）同常量同顺序；调参改 Lua 后需同步常量区，
  parity 测试会抓不同步。
- 当前守方侧输入（defense/闪避/暴击值等）取缺省 0——属性域补全后由
  `SkillCastHandler.rollDamage` 组装处接线（meleeDamageRange 已从装备+内功取攻方掷值区间）。

## skill/<技能ID>.lua（编译覆盖）

`CombatSkillCatalog` 编译每个技能时调用 `LuaScriptService.skillOverlay(id, exactBuff)`；
`def` 输入 = INI 编译出的 `buf_<技能ID>` 精确 buff 快照（`config_id/static_data/level/lifetime_ms`），
返回增量覆盖（对应 Go verifiedSkillEffectOverlays 硬编码的迁移）：

```lua
function M.compile(def)
  return {
    self_buff_lease_ms = 35000,   -- 自 buff 租约覆盖（毫秒）
    self_buff_slot     = 5,       -- 指定 BufferInfo 槽位（0/缺省=自动找空槽）
    red_armor          = true,    -- 红霸体：起手霸体帧，租约=动作总时长（槽1/8682）
    stance_cycle = {              -- 架势轮换：逐次施放循环变体（如太极拳四式 守/攻）
      variants = {
        { id = "buf_CS_wd_tjq04_1", static_data = 6013, level = 1,
          life_ms = 86400000, slot = 7 },
        { id = "buf_CS_wd_tjq04_2", static_data = 6055, level = 1,
          life_ms = 86400000, slot = 7 },
      },
    },
  }
end
```

已接脚本：`CS_yhwq_hsqs04`（35s/槽5）、`CS_yhwq_hsqs05`（槽6+红霸体）、`CS_wd_tjq04`（架势轮换）、
`CS_wd_tjq05`（槽8）、`CS_wd_tjq07`（槽9）、`CS_wd_tjq08`（红霸体）。未列技能无脚本 → INI 默认编译。

## item/&lt;configId&gt;.lua（使用物效果，msg31）

> 探索取证全录（客户端右键链路/宝箱数据模型/掉落包全景/参考实现盘点/决策记录）：[item-use-msg31.md](item-use-msg31.md)

`LoginFlow.useItemEffect`（msg31 不可穿戴/非卡片分支）调
`LuaScriptService.itemUse(configId, ctx)`；**解析优先级：Lua 脚本 → INI 数据回退
（`server/game/item/ItemUseCatalog`）→ 均无则维持 use-chain not implemented 日志**。
脚本返回 nil / 无脚本 / 引擎坏 → 一律走 INI 回退，使用链不拒绝。

`M.on_use(ctx)` 的 `ctx`（只读，Java 预填）：`item_id/amount/now` +
`self{hp,max_hp,mp,max_mp,silver}` + `rand{r_drop,r_proc}`（随机数 Java 预掷）。返回指令白名单：

```lua
function M.on_use(ctx)
  return {
    heal_hp    = 300,                            -- 回气血（Actor.restore，满血闸掉）
    heal_mp    = 100,                            -- 回内力
    open_box   = {drop_id = "xx", rolls = 1},    -- 按 share/drop/drops_mvp.json 权重开包（1..10 roll）
    grant_items = {{id = "sd_001", amount = 2}}, -- 确定性直接给物品（≤16 条）
    temp_stat  = {attack_add = 25, defense_add = 0, max_hp_add = 0,
                  life_ms = 86400000},           -- 限时属性（已解析未接：仅剩此效果时不消耗）
    consume    = true,                           -- 是否消耗 1 个（默认 true）
  }
end
```

INI 回退（`ItemUseCatalog.resolve(configId, itemType)`，share/item 下三表）：
`giftbox.ini[configId] DropID`（项目自建 UTF-8 表）→ `tool_item.ini` 的
`script=BoxItem`+DropID 开包 / `script=CompensateItem` 每个 `@CompensateItemRec` ref
各开一次（原版 func-56 条件门控未接，简化为全发）→ `item_use_skill_static.ini[itemType]`
回血阶梯（yaopinhp* 无 varprop 数值，L1..L9=100..3000，MP 减半）。掉落包体
`DropPacks`（share/drop/drops_mvp.json）权重抽 1 条/roll、数量 min..max、同物品合并。

落账顺序对齐 bak SceneFlow.useItem：效果先行（`grantBagItem` 容量感知、
回血+`vitalUpdate`），随后消耗 1 个（`consumeBagItemOne` 帧对，归零删行+镜像清位）；
会话级 800ms 节流防连点；随机数取会话 `combatRandom`（测试可换种子）。
已接脚本：`box_chinajoy_2026_giftbox`（开包）、`yaopin_sc_1001`（回血 100）、
`useitemhs_dy_06`（限时属性，效果层待二期）。未列物品无脚本 → INI 回退。

## 热重载

GM 面板 `reload_scripts`（GmRemote 白名单 + applyGmCommand）：重建 Lua 状态并清 overlay/itemUse 缓存，
下一次出招/使用即生效；重载失败自动回退 Java 管线并回执失败原因。

## 运行期钩子（战斗二期预留）

- `M.on_hit(ctx)`：`ctx.caster.stance_static_data`（架势相位）、`ctx.ratio`，返回 `{ratio=...}` 调整本波倍率
  （CS_wd_tjq04.lua 有注释示例，默认关闭）。
- 内功 `M.on_hit/M.on_be_hit`：吸收/反伤/HoT（scripts/neigong 138 个，钩子接线在 NPC AI 期一并做）。
