# 技能脚本框架（scripts/ Lua 契约）

`scripts/` 与 share 同法经 `ServerPaths`（env `NINEYIN_SERVER_ROOT` → 属性 → cwd 探测）定位；
引擎为 party.iroiro.luajava 4.1.0（lua55 natives，单 Lua 状态 + 全局锁——脚本是纯函数）。
**缺目录/natives 失败/执行异常 → 伤害公式回退 Java `CombatFormulas`、技能覆盖回退 INI 默认编译，
出招链路永不因脚本层不可用而拒绝**。

## 目录

| 路径 | 契约 | 状态 |
|---|---|---|
| `combat/formula.lua` | `M.calc(c) → {damage, miss, crit, parried, reduction_pct}` 伤害公式权威实现 | 战斗一期已接 |
| `skill/<技能ID>.lua` | `M.compile(def) → overlay` 编译期增量覆盖 | 战斗一期已接（6 个） |
| `neigong/buf_ng_*.lua` | `M.levels` / `M.on_hit(ctx)` / `M.on_be_hit(ctx)` 内功 buff 特效 | 待接（战斗二期 hook） |
| `item/*.lua` | useitem/box/yaopin 使用物效果 | 待接 |

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

## 热重载

GM 面板 `reload_scripts`（GmRemote 白名单 + applyGmCommand）：重建 Lua 状态并清 overlay 缓存，
下一次出招即生效；重载失败自动回退 Java 管线并回执失败原因。

## 运行期钩子（战斗二期预留）

- `M.on_hit(ctx)`：`ctx.caster.stance_static_data`（架势相位）、`ctx.ratio`，返回 `{ratio=...}` 调整本波倍率
  （CS_wd_tjq04.lua 有注释示例，默认关闭）。
- 内功 `M.on_hit/M.on_be_hit`：吸收/反伤/HoT（scripts/neigong 138 个，钩子接线在 NPC AI 期一并做）。
