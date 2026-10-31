# 第四批方案（未实施）：Buff 数值效果 —— Lua 效果层 + INI 默认回退

> 状态：**方案定稿，暂缓实施**。前三批已落地：互播基础 + 道具使用（gameplay-player-visibility-and-item-use.md）、
> 战斗互播（gameplay-combat-visibility.md）、PvP 结算（gameplay-pvp-settlement.md）。
> 本文档为第四批的完整实施蓝图，后续批次开工时按此执行。
> 定稿原则（用户指定）：**效果逻辑走 Lua（热更可调），无脚本时回落 INI 数据默认**——与 skill/neigong/item 三域同构。

## 1. 背景与数据探查结论

Buff 目前纯视觉：24 槽租约（`PlayerActor.ActiveBuff{staticData, expiresMs, level, configId}`）+
event-169 add/remove 帧配对，对战斗数值零参与（`combatStats()` 只有 equip/neigong/tempStats 三层）。

数据侧（share/，GBK+CRLF）探查结论：

| 事实 | 数据 |
|---|---|
| 有直读数值的 buff | 8,885 / 28,532 = **31.1%**（CCHP/CCMP/CCQGP/@PropModifyPackRec 任一） |
| 数值主链 | `buff_new(@EffectTimer) → buff_static(EffectType/Min-MaxVarPropNo) → buff_varprop(Level 行: CCHP/CCMP/@PropModifyPackRec) → proppack(r=属性行)` |
| EffectType 语义（推断） | 1=常驻状态修改(80.9%) / **3=周期效果 HoT/DoT(15.5%)** / 4=轻功 / 0=清除类 |
| proppack 解析器 | **已有**：`PropPackCatalog` 全量装载 + `recomputeEquipBonuses` 累积器范式可抄 |

已验证样例链（真实数据走通）：

- **HoT**：`buff_new[buf_yaopinhp1] StaticData=90, EffectTimer=2000` → `buff_static[90] EffectType=3, Min/MaxVarPropNo=181..200` → `buff_varprop[181] Level=1, LifeTime=10000, CCHP=22` = **10s 内每 2s 回 22 HP**
- **DoT**：`buff_new[buf_fb_slcs_001] CCHP=0,0,,HP,-10,0 + EffectTimer=5000`（长形；varprop 短形同构）
- **属性**：`buff_new[buf_qg_fast]` → `buff_static[217]` → `buff_varprop[22001..22010]`（CCQGP=-7..-5, @PropModifyPackRec=6307..6315）→ `proppack[6311] r=,RunSpeedAdd,3,0`

## 2. 架构

```
Buff 数值效果 = scripts/buff/<buffId>.lua 优先（M.passive 属性表 + M.on_tick 周期效果，reload_scripts 热重载）
             └ 无脚本 → INI 默认（propPacks 属性累积 + EffectTimer/CCHP/CCMP 周期）
Java 永远持有：24 槽租约 / event-169 帧配对 / 到期剔除（不动，与脚本层解耦）
```

四层装配（照 NeigongRuntime 装配模式）：

```
ScriptHost（buff/ 域扫描） ─→ BuffScriptBridge（ctx 构造 + 指令白名单，纯转换）
                           └→ BuffRuntime（钩子触发 + 指令执行 + 状态缓存）
rule/BuffEffectCatalog（INI 默认层：三表解析 → periodicOf/propPacksOf）
PlayerActor（第四聚合层 buff*Add + ActiveBuff.nextTickMs + buffScriptState）
SceneFlow（wire + tick 调用点 + stageSkillBuffs 后即时重算）
```

## 3. 改动清单

### 3.1 ScriptHost：`scripts/buff/` 新脚本域

照 item 域三行改动：目录扫描装载、`buffModule(buffId)` 访问器、locateScriptsRoot 识别、
loadedCount 计入。模块约定：

```lua
-- scripts/buff/buf_yaopinhp1.lua
local M = {}
M.meta = { id = "buf_yaopinhp1", name = "回血药", interval_ms = 2000 }  -- Java 按此节流调用 on_tick
function M.passive(ctx)                       -- 常驻属性（可选；字段同 neigong 被动表）
  return { max_hp_add = 0, attack_add = 0, defense_add = 0 }
end
function M.on_tick(ctx)                       -- 周期效果（可选）
  -- ctx: now / since_last_ms / self{hp,max_hp,mp,max_mp} / state / rand
  return { heal = 22, mp_restore = 0, damage = 0, state = { last_at = ctx.now } }
end
return M
```

### 3.2 新 `script/BuffScriptBridge.java`

- `useContext(...)`：now/since_last_ms/self/state/rand（照 NeigongScriptBridge.tickContext 形态）
- `parseTick(buffId, result)`：白名单 `{heal, mp_restore, damage(自身 DoT), state}`，非法条目丢弃 + WARN（单脚本错误不打断 tick）
- passive 解析**直接复用** `NeigongScriptBridge.parsePassive / PassiveStats`（字段本就是通用属性表，无需重写）

### 3.3 新 `rule/BuffEffectCatalog.java`（INI 默认层）

- 装载：`buff_new`（buffId→staticData/EffectTimer）→ `buff_static`（Min-MaxVarPropNo/EffectType）→
  `buff_varprop` 全数字行；GBK 读入 + 全字段 `trim()` 防 `\r`（实测 grep '$' 锚全失配的坑）
- API：
  - `Periodic periodicOf(buffId, level)` → `{intervalMs, cchp, ccmp}`（interval 取 buff_new.EffectTimer 优先、
    varprop 行覆盖；cchp/ccmp **只认 varprop 短形**，buff_new 长形 6 列暂缓）
  - `List<Integer> propPacksOf(buffId, level)` → @PropModifyPackRec（解析照 `EquipEffectCatalog.intValues`；
    等级选行 floor 语义照 `EquipEffectCatalog.rowAt`；区间行数小（实测 ≤20 行）线性扫可行）
- 内存：varprop 5.4 万行全存 int 键 HashMap（数十 MB 内）；空占位节容错跳过

### 3.4 新 `game/BuffRuntime.java`（照 NeigongRuntime 模式）

**`recomputeBonuses(PlayerActor)`**（每秒 + 挂 buff 后即时调用，幂等清零重积）：

```
遍历 24 槽 active buffs：
  有脚本 → M.passive(ctx) → PassiveStats
  无脚本 → BuffEffectCatalog.propPacksOf → PropPackCatalog.modsOf
         （仅 flag=0 平值行；flag=1 百分比语义未钉死跳过）
累积进 PlayerActor 新 buff*Add 字段层（镜像 equip*Add 白名单 switch）
→ combatStats() / maxHp() / maxMp() 叠加第四层
（四层汇合：equip 词缀 + neigong 被动 + tempStats 道具 + buff——前三批成果在此闭合）
```

同 configId 复用槽 = 天然刷新不叠层；槽顶替由幂等重算兜底。

**`onTick(fx, session, actor, now)`**（返回 changed，并入既有 vitalUpdate 节流）：

```
遍历 24 槽 active buffs：
  有脚本 → 按 meta.interval_ms 节流（无 interval_ms 则每秒）调 M.on_tick
           state 存 actor.buffScriptState.get(buffId)（返回即整体替换，热重载安全，同 neigong state 语义）
  无脚本 → periodicOf 周期（ActiveBuff.nextTickMs 判定）结算 CCHP/CCMP
heal/restore → hp/mp 上限夹取；damage → applyDamage（25% 瘀血分层免费获得）
  死亡 → 复用 damagePlayer（8s 复活调度 + vitalUpdate 双发，PvP 第三批设施）
```

### 3.5 PlayerActor 改动

- `buff*Add` 字段组（buffPhyVa/buffPhyDef/buffStrAdd/buffMaxHpAdd... 镜像 equip*Add）
- `ActiveBuff` 加 `long nextTickMs`（INI 周期节流）
- `Map<String, Map<String, Object>> buffScriptState`（脚本 state 存储，key=buffId）
- `combatStats()/maxHp()/maxMp()` 叠加第四层

### 3.6 SceneFlow 装配与调用点

- `setScriptHost` 里 `new BuffRuntime(host, buffEffectCatalog, propPacks)` wire（构造器装载 BuffEffectCatalog）
- `SceneFlow.tick`：`neigong.onTick` 旁并列——先 `buffRuntime.recomputeBonuses`（覆盖 activate/clear/expire 全部变更点）后 `buffRuntime.onTick`
- `stageSkillBuffs` 尾部即时 `recomputeBonuses`（挂上即生效，不等下一秒）

### 3.7 样例脚本（scripts/buff/）

- `buf_yaopinhp1.lua`（回血药 HoT，上面模板即为全文）
- `buf_fb_slcs_001.lua`（DoT：`return { damage = 10 }`，interval_ms=5000）
- 可选一个属性 buff 样例（passive 表演示）

## 4. 范围外（明确不做，标注后续批次）

| 项 | 原因 |
|---|---|
| EventProcess 脚本系（1.1 万处） | 长尾，Lua 层已提供等价表达 |
| buffpack @BuffModifyPackRec（轻功 CCQGP 减免） | 引用仅 11+61 处，后置 |
| proppack flag=1 百分比行（MoveSpeedAdd 等） | 语义未钉死，减速/加速留后批 |
| ReplaceCategory/ReplaceType 互斥 | 幂等重算以槽现值为准已兜底 |
| EndFightClear/DeadClear | 无战斗态跟踪，后批 |
| buff_new 长形 CCHP（6 列，1,273 处） | 列语义不明，varprop 短形先行 |

## 5. 验证方案

1. **真实数据 spot check**（locateShareRoot + assumeTrue）：
   `periodicOf("buf_yaopinhp1",1)` → `{2000, 22, 0}`；`"buf_fb_slcs_001"` → cchp=-10；
   `propPacksOf("buf_qg_fast",1)` 非空；无 varprop 的 buff → 空默认（不误伤）。
2. **Lua 端到端**（@TempDir fixture，照 NeigongScriptTest 套路）：
   buff 脚本装载、interval 节流（两次 tick 只触发一次）、state 往返、指令白名单非法丢弃、
   脚本错误回退 INI 默认不打断。
3. **INI 回退层**（PvpSettlementTest 式裸会话 fixture）：
   fixture proppack（自写 `r=,PhyVa,50,0`）+ activateBuff → recompute → combatStats +50；
   clearBuff → 还原；HoT 两跳回血 44；DoT 打空致死且停跳；CCMP 回蓝。
4. **全量回归** `./mvnw test`（262 基线全绿）。
5. **实机清单**：
   GM `give_item` 回血药 → 使用 → 血条按 2s 分段回复（此前一次性回满）；
   带 stat-buff 的技能 → 面板属性变化且到期还原；
   修改 `scripts/buff/*.lua` → GM `reload_scripts` → 效果即时变化（无需重启）；
   PvP DoT 技 → 对方持续掉血且同伴可见（第二三批互播链路复用）。

## 6. 后续批次候选（优先级待定）

- 区域技打玩家（areaTargets 扩扫同场景玩家会话）
- proppack flag=1 百分比语义钉死 → 减速/加速/轻功消耗减免全量接入
- 恶人值/红名/死亡掉落
- 技能倍率 varprop 全量核对（当前 baseDamage→waveRatio 已接，抽查覆盖率）
