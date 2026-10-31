# 技能 Lua 脚本框架

> 接入日期：2026-10-31。引擎：[gudzpoz/luajava](https://github.com/gudzpoz/luajava)
> （`party.iroiro.luajava` 4.1.0，native Lua 5.5，LuaJava 现代移植）。
> 本文档是脚本编写契约的唯一权威：分层归属、钩子签名、指令 DSL、沙箱边界与迁移指南。

## 1. 为什么是现在、为什么是这套边界

ADR [script-system-assessment.md](script-system-assessment.md) 预留的方向：
"嵌入式脚本，限定**纯函数钩子**（输入世界状态快照，输出指令列表），不让脚本持有
玩家状态或直接访问 Session/ECS 内部"。技能层已按此接入：**效果组合与数值公式
进 Lua（热更不重启），协议帧与落账留 Java（帧配对不被脚本破坏）**。

数据表能表达的不进脚本：CD/消耗/射程/形状/动作时间轴仍由
`resources/modern/share/skill/*.ini` 驱动（`SkillCatalog` 编译），脚本只承载
**数据表达不了的逻辑**（条件效果、架势联动、系数调整、新机制组合）。

## 2. 分层归属（从原 Java 实现总结的框架）

| 层 | 内容 | 归属 | 调整方式 |
|---|---|---|---|
| 门 Gates | 学习/射程/CD/资源/互斥/受控 | Java（SceneFlow） | 改 INI 表 |
| 费 Costs | MP/SP/QG | Java（SceneFlow） | 改 INI 表 |
| 形 Shape | 单体/圆/扇/矩/自身 | Java（areaTargets） | 改 INI 表 |
| 时序 Timeline | 多跳波次/动作段排期 | Java（GameScheduler） | waves 参数可被脚本覆盖 |
| **效果 Effects** | 五类 EffectKind 的组合与条件 | **Lua compile/on_cast** | **改 scripts/skill/\*.lua** |
| **数值 Numbers** | 伤害公式掷骰（攻防/暴击/闪避/招架/平减） | **Lua formula.lua** | **改 scripts/combat/formula.lua** |
| 帧 Frames | cooldown_rec/STATE/血条/buff add-remove 配对 | Java（S2CPackets） | 不开放 |

五类 `EffectKind`：`damage` / `player_buff` / `target_control` / `red_armor` / `stance_cycle`。

## 3. 目录与生命周期

```
scripts/
  skill/<skillId>.lua     # 一个技能一个脚本（文件名 = 技能 ConfigID）
  combat/formula.lua      # 伤害公式（全局唯一）
```

- 定位：env `NINEYIN_SERVER_ROOT` 优先，否则从 cwd 逐级向上找 `scripts/`
  （与 share 数据同模式）；找不到 = 无脚本模式，服务器照常跑默认管线。
- 加载：`NineyinServer` 构造期（早于 `SkillCatalog.load` 的 levelOne 预编译）。
- 热重载：GM 面板 `reload_scripts`（重建 Lua state + 清技能定义缓存；
  进行中波次用旧 Def 跑完，下次编译生效）。
- 模块约定：`local M = {} … return M`；`eval` 整文件取返回值。
- 无脚本的技能（17k 个中的绝大多数）零开销：脚本句柄为空直接走默认编译。

## 4. 钩子契约（纯函数：只读输入 → 指令输出）

### 4.1 compile（编译期，效果覆盖）

```lua
-- scripts/skill/CS_yhwq_hsqs04.lua
local M = {}
function M.compile(def)
  -- def：INI 编译快照（只读）
  --   id/level/static_data/script/tao_lu/base_damage/hit_count
  --   target_mode("selected"/"self"/"caster_circle"/…)/target_range_meters
  --   exact_buff = {id, static_data, level, life_ms, slot}（若有 buf_<skillId>）
  return {
    self_buff_lease_ms = 35000,   -- 覆盖 exact buff 租约（毫秒）
    self_buff_slot     = 5,       -- 覆盖 exact buff 首选槽（PlayerActor 槽位常量）
    red_armor          = true,    -- 追加红霸体（租约 = 动作总时长）
    stance_cycle = {              -- 追加架势轮换（变体循环，同槽覆盖）
      variants = {
        { id = "buf_CS_wd_tjq04_1", static_data = 6013, level = 1,
          life_ms = 24*60*60*1000, slot = 7 },
      },
    },
    -- effects = { … },           -- 全量效果覆盖（逃生舱，见 4.4；提供时忽略默认编译）
  }
end
return M
```

返回 nil / 无该字段 = 沿用 INI 默认编译。原 `VERIFIED_OVERLAYS` 6 条已逐字段
迁移为脚本（花神四/五式、太极四/五/七/八式），`SkillCatalogTest.realShareDataSpotChecks`
锚定迁移等价性。

### 4.2 on_cast（起手，动态追加效果）

```lua
function M.on_cast(ctx)
  -- ctx = { skill_id, level,
  --         caster = { stance_static_data, hp, max_hp, mp },   -- 太极架势槽快照
  --         target = { oid, config_id, hp, max_hp } }          -- 仅有目标时
  return { add_effects = {
    { kind = "player_buff",
      buff = { id = "buf_x", static_data = 999, level = 1, life_ms = 8000, slot = 0 } },
  } }
end
```

`add_effects` 只接受 `player_buff` / `stance_cycle` / `red_armor`（伤害与控制
必须走编译期管线，保证帧配对/落账不变，非法条目丢弃 + WARN）。

### 4.3 on_hit（每波命中，倍率修正）

```lua
function M.on_hit(ctx)
  -- ctx 在 on_cast 基础上增加 wave/waves/ratio（当前波倍率）
  if ctx.caster.stance_static_data == 6055 then
    return { ratio = ctx.ratio * 1.2 }   -- 攻态 +20%
  end
  return nil
end
```

在 `CombatFormulas` 掷点**前**调用，只改倍率；门、帧、波次调度不受影响。

### 4.4 effects 全量覆盖（逃生舱）

```lua
function M.compile(def)
  return { effects = {
    { kind = "damage", amount = 120 },
    { kind = "player_buff", buff = { id = "buf_a", static_data = 99, level = 1, life_ms = 5000 } },
    { kind = "target_control", buff = { id = "buf_stun", static_data = 123, level = 1, life_ms = 2000 } },
    { kind = "stance_cycle", variants = { … } },
    { kind = "red_armor" },
  } }
end
```

提供 `effects` 时**完全替换** INI 默认编译效果（其余字段失效）。kind 白名单外
的条目丢弃 + WARN。

### 4.5 formula.lua（伤害公式）

输入 `c`（只读，Java 预填全部公式参数）与输出见脚本头注释。约定：

- **随机数由 Java 预掷**传入 `c.rand = {r_miss, r_crit}`，脚本不掷骰——
  同输入可复现，`ScriptHostTest.formulaMatchesJavaImplementation` 与
  `CombatFormulas.calc`（确定性重载）逐输入对照。
- 脚本缺失/抛错/无 damage 字段 → 回退 `CombatFormulas`（Java 常量实现保留为
  对照与兜底，行为不漂移）。
- 调系数（防御曲线、暴击斜率、闪避上限、平减换算）直接改常量区，reload 即生效。

## 5. 沙箱与稳定性

- 只开 `base`/`string`/`table`/`math` 白名单库；`io`/`os`/`debug`/`package`/`require`
  不加载，LuaJava 默认注入的 `java` 互操作全局置 nil（防误用，非对抗性边界）。
- `print` 重定向 slf4j（native stdout 会绕过 logback 编码）。
- 所有脚本调用在 pcall 内（`ScriptHost.callMap`）：错误 → WARN → 回退 Java 默认，
  **绝不打断场景 tick**；单个语法错误脚本在加载期被跳过。
- 线程：native LuaState 非线程安全，调用经 `ScriptHost` synchronized 收敛
  （出招受 500ms 互斥锁限频，锁竞争可忽略）。
- 模块无状态：reload 即重建整个 Lua state，旧引用全部失效。

## 6. 引擎实现备注（踩过的坑）

- **参数转换**：`LuaValue.call(Object...)` 不递归转换嵌套容器（嵌套 Map/List
  变 Java 代理，脚本无法索引）——`ScriptHost.callMap` 用 `Lua.push(Map)` 深度
  转换 + `pCall` 栈协议。
- **返回值转换**：`toObject` 只做浅层——自写 `deepValue`（`lua_next` 全遍历，
  键全为连续 "1".."n" 的数组形态转 List）。
- **`lua_next` 陷阱**：读 key 前必须 `pushValue` 复制——`lua_tolstring` 会就地
  替换栈上数字键（→ string），下一轮 `lua_next` 即 `invalid key to 'next'`
  native panic（FATAL，进程级）。
- Lua 5.5 与 5.1 差异（整数除 `//`、goto 等）对新写脚本无影响；对未来可能
  参考的原服 Lua 资产，native 5.5 兼容性远好于纯 Java 5.1 实现。
- native 库（`lua5564.dll` 等 9 平台）随 `natives-desktop` classifier 打进
  shade jar 根部，启动自动解压加载（已实测 build.bat 产物可用）。

## 7. 迁移指南：给技能加脚本

1. 建 `scripts/skill/<skillId>.lua`（抄 `CS_wd_tjq05.lua` 最简模板）。
2. `compile` 返回增量指令或全量 `effects`；字段见 4.1/4.4。
3. 测试锚定：fixture 链用 `@TempDir` 写脚本 + `ScriptHost.load`（参考
   `ScriptHostTest.overlayParsingEndToEnd`）；真实数据用
   `NINEYIN_SERVER_ROOT=… mvnw -pl nineyin test -Dtest=SkillCatalogTest`。
4. 线上：GM `reload_scripts` 热更；写坏脚本 = WARN + 回退默认（不会炸服）。

## 8. 边界与后续

- BOSS AI 决策钩子（`AiEventEngine` 出招选择/转阶段）**未接入**——`ScriptHost`
  的调用分发已预留扩展位，届时按 on_think/on_select_skill 钩子同模式接入。
- 轻功（216/217 独立 opcode）不在本框架内；内功特效已于 2026-10-31 接入，
  契约见 [neigong-script-framework.md](neigong-script-framework.md)。
- 运行目录与仓库分离部署时（cwd 无 `scripts/`），把 `scripts/` 拷到运行根或设
  `NINEYIN_SERVER_ROOT`。
