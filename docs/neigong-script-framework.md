# 内功特效 Lua 脚本框架

> 接入日期：2026-10-31。承接 [skill-script-framework.md](skill-script-framework.md)
> 的纯函数钩子体系（ADR [script-system-assessment.md](script-system-assessment.md)），
> 把 138 门内功特效（`E:\Workspace\九阴服务端\data\内功特效清单.txt`，717 个
> "内功 × 层数档位"行）从纸面描述转为可热更的 Lua 脚本并接入战斗管线。
> 本文档是内功脚本契约的唯一权威。

## 1. 目录与模块

```
scripts/
  neigong/<buffId>.lua    # 一门内功一个脚本（文件名 = buffId，如 buf_ng_wd_001）
```

- 由 `ScriptHost.load` 与技能脚本同批装载（`neigongModules`，键 = buffId）；
  GM `reload_scripts` 一并热更。
- 模块自包含（沙箱无 require）：`local M = {} … return M`。

```lua
-- 两仪护心功（buf_ng_wd_001）｜武当｜受击触发
-- 源：内功特效清单.txt 行579-582（4 层）。
local M = {}
M.meta = { id = "buf_ng_wd_001", name = "两仪护心功", book = "ng_wd_001",
           category = "victim", max_tier = 4 }
M.defaults = { proc = 0.10 }          -- 清单未标数值的触发几率（可调，热更生效）
M.levels = { [1] = {absorb = 100}, …, [4] = {absorb = 250} }

local function tier_of(ctx)           -- 每个脚本内联这三个助手（沙箱无 require）
  local mt = M.meta.max_tier
  local bl, bm = ctx.book_level or 1, ctx.book_max_level or mt
  return math.max(1, math.min(mt, math.ceil(bl * mt / bm)))
end
local function lv(ctx) return M.levels[tier_of(ctx)] or M.levels[M.meta.max_tier] end
local function roll(ctx, p) return (ctx.rand and ctx.rand.r_proc or 1) < p end

function M.on_victim(ctx) … return { shield = {amount = lv(ctx).absorb, life_ms = 12000} } end
return M
```

- **category** 六值对应清单机制分类：`victim`（受击触发）/`attack`（攻击触发）/
  `parry`（招架触发）/`tick`（周期触发）/`passive`（常驻/被动）/`stack`（叠加层数）。
  仅信息标注——钩子按需组合，与 category 不强绑定。
- **层数档位映射在脚本侧**：ctx 携带书本修炼等级 `book_level`/`book_max_level`，
  脚本按 `meta.max_tier` 自行换算（Java 不读 meta）。
- **模块索引键推导**：运行书本 configId（`faculty.curNeigong`，如 `ng_wd_001`）
  → `"buf_" + 书本id`（与 `BootstrapPackets.STARTER_BOOKS` 的 NeiGongBook.buffId
  先例一致）。新手三门（两仪护心功/幻玉神功/碧波心经）接通即生效。

## 2. 钩子契约（挂点 → Java 侧）

| 钩子 | 触发点（Java） | ctx 关键字段 | 可返回指令 |
|---|---|---|---|
| `M.on_attack(ctx)` | `SceneFlow.settleSkillHit` 命中结算（附加伤害并入同一血量批/死亡路径） | wave/waves/damage/crit/self{hp,max_hp,mp,max_mp,attack}/target{oid,config_id,hp,max_hp}/state/rand | extra_damage/heal/mp_restore/shield/state |
| `M.on_victim(ctx)` | `AiEventEngine.castSkill` applyDamage 前 | incoming/crit/parried/controlled/self/attacker/state/rand | reduce_pct/reflect/heal/mp_restore/shield/state |
| `M.on_parry(ctx)` | 同上，招架生效块内（先于 on_victim） | on_victim 全部 + parry_endurance | parry_reduce/heal/shield/state |
| `M.on_tick(ctx)` | `SceneFlow.tick` 每秒（被动重算后） | now/since_last_ms/self/state/rand | heal/mp_restore/shield/state |
| `M.passive(ctx)` | `NeigongRuntime.refreshPassive`（书本/等级变化才重调；切换内功清旧 state） | book_level/book_max_level | 被动属性表（见下） |

- **随机数 Java 预掷**（`ctx.rand.r_proc`，`[0,1)`）——脚本不掷骰，同输入可复现
  （与 formula.lua 同哲学）。`roll(ctx, p)` 即 `< p` 判定。
- **state 往返**：`ctx.state` 为该内功当前状态快照（副本），返回
  `state = {…}` 整体替换（存于 `PlayerActor.neigongState`，热重载安全）。
  周期触发的"上次触发时刻"约定字段 `last_at`（`since_last_ms` 由 Java 依它计算）。
- **护盾**为玩家侧单池（`PlayerActor.neigongAbsorb`，多来源叠加、到期即失效），
  受击时先于血量层消耗。

### 指令 DSL v1（白名单；全服务器侧结算）

```lua
return {
  reduce_pct    = 0.08,                       -- 本次受击伤害减免（夹 [0,0.9]）
  extra_damage  = {amount = 120, kind = "yang"},  -- 对当前目标附加伤（attack 钩子）
  reflect       = {amount = 35, kind = "external"}, -- 反弹攻击者（victim/parry 钩子）
  heal          = 100,                        -- 自身回血
  mp_restore    = 50,                         -- 自身回内力
  shield        = {amount = 250, life_ms = 10000},
  parry_reduce  = {internal = 15, external = 12},  -- 招架平减（当前管线全外功，取 external）
  state         = {stacks = 2, last_at = 1730000000000},
}
```

- `kind`：`yang/yin/gang/rou/external`（阳/阴/刚/柔/外功）。非法条目丢弃 + WARN。
- **客户端 buff 帧暂不发**（`buf_ng_*` 的 static_data 目录缺失）；后续拿到目录后
  在指令集加 `self_buff` 走既有 event-169 配对路径。
- 被动表字段（对齐 `CombatStats` + 有效上限层）：`max_hp_add/max_mp_add/
  attack_add/defense_add/pierce_add/accuracy_add/evasion_add/block_rating_add/
  damage_reduction_add`（整数）与 `crit_rate_bonus/crit_damage_bonus`（小数，
  0.15 = +15%）。罡气/内息等暂无 CombatStats 落点，相关子句以 TODO 注释留在脚本里。

## 3. 实现深度与 TODO 约定

138 门全部生成；数值型主机制（概率触发、附加伤害、免伤%、护盾、吸血、回蓝、
叠层计数、周期触发）忠实实现。依赖缺失系统的子句在脚本头注释标 TODO，主要类别：

- **临时属性类**（"N 秒内招式伤害 +X%"）——需动态属性 buff 指令；部分以
  附伤/减免窗口近似，其余留 TODO。
- **DoT/持续回复**——需 HoT 调度，多处以"立即一跳"近似并注明。
- **控制类**（定身/打断/减速/霸体）——待控制指令扩展。
- **环境判定**（空中/地面、体格、同帮会人数、淤血/死血分层、打坐、坐骑、怒气）——
  待对应系统接入后补 ctx 字段。
- 玩家 vs 玩家未接（紫瑞心经等只对玩家生效的门派留占位）。

## 4. 测试与运维

- `NeigongScriptTest`（7 用例）：宿主加载/指令白名单/被动解析/运行时
  （免伤缩放、护盾穿盾数学、state 往返、被动层）/138 门全量加载冒烟
  （meta 完整、category 合法、至少一个钩子或 levels 表）。
- 运行期日志：`neigong <id> on_victim -20% -> incoming …`、`absorb consumed …`、
  `neigong passive refreshed …`。
- GM `reload_scripts` 热更 138 门脚本（`script host loaded: … neigong=138`）。
- 调参与平衡：改 `M.defaults.proc` / `M.levels` → reload 即生效，无需重启。
