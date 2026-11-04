# 战斗一期：技能释放（msg211）wire 规格与实现映射

来源：`F:\Game\Online\9yin\9yin_server1\captures\local\127-0-0-1-62433_{c2s,s2c} - 技能.bin`
（2026-11-01 19:27 抓包，F 盘参考 exe；C2S 1286 帧 / S2C 6856 帧；玩家 obj=0x11000001 owner=0x7A5C0B，
取证脚本 `tools/smoke/combat_forensics.py`）。Go 参考：`G:\pojie\ai\9yin-go-server1\cmd\protocol-probe\skill_combat.go` / `skill_catalog.go`。
**两源冲突时以抓包为准**（F exe 比 go-server1 源码新：active_skill_rec/337/24/205/18/0x2D/0x13 均为 Go 源码没有的帧）。

## 1. 请求：C2S 出招

0x0A 信封（`0x0A | 16B session | u32 seq@17 | TVarList@21`）custom msgId=211：

```
[i32 211, text 技能ConfigID, f32 x, f32 y, f32 z,        // 施法者坐标
 f32 朝向(弧度),                                          // 扇形技能朝向判定用
 (f32 tx, f32 ty, f32 tz,                                 // 多为 0；指向型技能非零
  text '',                                                // 参数串（短形态到此为止=87B，无目标）
  obj(目标objId,目标owner), f32 目标x, f32 目标y, f32 目标z)]  // 长形态=111B
```

按类型宽容解析（Go `parseUseSkillCustom`/`useSkillCastTransform` 同款：只校验 [0]=211 int、[1]=非空串、[2..4]=float，[5] 可选 float）。

## 2. 目标选择（出招前置）

- **C2S 0x07**（33B 二进制）：`07 | 16B session@1 | u32 seq@17 | u32 objId@21 | u32 ownerId@25 | i32 FuncID@29`，点击目标时先发 0x07、紧接 custom **msgId=431** `[431, obj(目标,owner)]`（信息等价，seq=0x07 的 seq+1）。
- 服务端确认：`0x10` 玩家对象 `LastObject` 属性 = obj 对（capture: idx 414, type 9）。
- Go 只在 0x07（`objectRequest`）设置 `selectedObject/selectedOwner` 并校验 `interaction=npcInteractionCombat` 且 HP>0；431 不解析。

## 3. 被接受的出招：S2C 帧序列（每次一招，顺序固定）

| # | 帧 | 内容 |
|---|---|---|
| 1 | `0x11` cooldown_rec | `[CoolDownCategory(i32), start(unixms i64), start+CoolDownTime(i64), 0xFFFF(u16)]` 个人 CD |
| 2 | `0x11` active_skill_rec | `[技能ConfigID(窄串)]` 单列记录行 |
| 3 | `0x11` cooldown_rec | `[50000, start, start+AddCoolDownTime, CoolDownTeam(u16)]` 公共 GCD |
| 4 | `0x10` 玩家全量 | isView=0，188 项属性（LogicState/HPRatio/MPRatio/MaxHP/MaxMP/HP/MP/SpeedRatio/MoveSpeed/RunSpeed/HitHP/HitHPRatio/QingGongPoint/.../CurSkillID/CurSkillTarget/.../BufferInfo1-24/五系/PowerLevel/TitleID），即 addObject 同款属性面 |
| 5 | `0x1E msg337` | `[obj(玩家), f32 x, f32 y, f32 z, f32 朝向, i32 0]` 施法者位置/朝向同步（值来自 211 请求） |
| 6 | `0x1E msg20` | `[obj(玩家), 技能串, obj(0,0), i32 0, f32 0.0]` 技能动作。**玩家出招目标恒为空对象**（有选中目标也是 obj(0,0)，抓包实证）；NPC 施法时第三参=目标 obj |
| 7 | 每个受击目标 | 下方 §4 |
| 8 | 有 buff 时 | `0x1E msg169` `[169, i32 0, 窄串 buffConfigId, obj(施法者), obj(承载者), 宽串名, 宽串名, i32 0, i32 0]`（九值；对比格挡形态 v8=int64 毫秒）+ `0x10` 玩家 `BufferInfoN`(N=槽位) |
| 9 | 动作总时长后 | `0x12` 删 active_skill_rec 行 0 + `0x10` 玩家 `State`(idx19)="stand"（12 次出招=12 对，State 帧全程只出现 stand 复位） |

**被拒（未学/CD 中/无目标/死亡/资源不足）：静默吞掉只记日志，不回任何帧**（抓包 35 发 12 中）。
**无掉落/银子/经验帧**（0x11 goods/money 记录与 Silver 属性更新均为零）。

## 4. 每目标命中序列

```
0x10 NPC[HP/MaxHP/HPRatio/LogicState/Dead]      ← 掉血/死亡属性帧（n=9，含重复项）
0x13 fight_relation_rec                          ← 玩家对象上的威胁/伤害累计格
0x1E msg24  伤害飘字（技能形态，11 值）：
    [24, obj(受害者), i32 17, i32 伤害值, i32 0, i32 0,
     窄串技能ID, 窄串script名("SkillNormal"), 窄串受害者模型("monv030101"),
     宽串攻击者名, i32 0]
0x2D 受击特效（25B）：
    2D | 01 | 00 | u32 受害者objId@3 | u32 owner@7 | u16 2 @11 | u16 28 @13
      | u32 受害者HPRatio@15 | u16 112 @19 | u32 受害者HPRatio@21
    （28/112 两常量全程不变；HPRatio=受击后血量百分比整数，出现两次）
0x1E msg18  通用动作：[18, obj(目标), 窄串"hurt_half", i32 0, obj(攻击者)]
0x1E msg205 命中确认：[205, obj(施法者), 窄串技能ID, obj(目标)]
```

NPC 打玩家形态（AI 期参考）：`[24, obj(玩家), 7, 1, obj(NPC), 窄串NPC模型, 宽串玩家名, 宽串NPC名, 20]`。

### 0x13 fight_relation_rec 布局（21B，字面复刻）

```
13 | u8 0 | u32 玩家objId@2 | u32 owner@6 | u16 记录索引@10
  | u16 0x0001 @12 | u16 0x0001 @14 | u8 0x00 @16 | u32 累计伤害@17
```

- 官方协商表（447 schema）中 cooldown_rec=**109**（cols `[3,4,4,2]`，team 是 **u16**）、fight_relation_rec=**114**（cols `[9,2]`）、active_skill_rec=**446**（cols `[7]`）。Java 自协商表索引以 `RecordMessages` 常量为准。
- u32 值 = 该玩家对**该目标**的累计伤害（抓包 143→210→277→344 每击 +67 递增，与飘字对齐）；row/col 恒 1/1，col0(obj) 从不写——语义未完全钉死，按字面字节复刻，真机不符再迭代。
- 0x11 cooldown 行帧长 38B：`11 | u8 0 | u32 obj@2 | u32 owner@6 | u16 rec@10 | u16 srcRow@12=0 | u16 rowCount@14=1 | i32 cat | i64 start | i64 end | u16 team`。
- 0x11 active_skill_rec 行：`... | u32 (len+1) | GBK/UTF8 串 + NUL`（同 RecordMessages.recordAddString）。
- 0x12 删行：`12 | u8 0 | u32 obj | u32 owner | u16 rec | u16 行号=0`。

## 5. CD 数据来源（ini 已核实）

| 值 | 来源 | 抓包实证 |
|---|---|---|
| 个人 CD 类别 | `skill_static[CoolDownCategory]` | sfgp01=8837, sfgp02=8838, sfgp04=8848（19494/19495/19497 节） |
| 个人 CD 时长 | varprop L1 `CoolDownTime` ms | sfgp04=2000, sfgp01=8000 |
| 个人行 team | 固定 `0xFFFF`（static 无 CoolDownTeam 时也是 0xFFFF） | 全部 24 行 |
| GCD 类别 | 固定 `50000` | — |
| GCD 时长 | `skill_new[AddCoolDownTime]` ms | sfgp 族=7000（bug.txt"7 秒公共 CD"同源） |
| GCD 行 team | `skill_static[CoolDownTeam]` | sfgp 族=50369 |

## 6. 技能编译数据链（Go `skill_catalog.go` 移植）

`resources/modern/share/skill/`：skill_new（StaticData/script/AddCoolDownTime）、skill_static（varprop 区间/CoolDown*/IsDamage/TaoLu）、
skill_normal_varprop + skill_lock_varprop（按级：CoolDownTime/Consume/DamageCalculate/HitShapePkg/HeightDiff/AddCoolDownTime）、
skill_consume（AConsumeMP/SP/QGP）、damage_calculate（r 字段第 3 段求和=编译伤害）、attack_hitshape（HitShape→目标模式）、
buff_new/buff_static/buff_varprop（buf_<技能ID> 精确 buff）。
动作时间线 `resources/modern/ini/action/zhaoshi_player*.ini`（4 文件合并命名空间；段格式 `N=动作名;?;endFrame;inBlend;?`，30fps）。

- 目标模式：HitShape 1=以己圆 / 2,11=以目标圆 / 3,9,10=扇形(P2=半径,P1=角) / 4,12=矩形(P2=长,P1=宽) / 其余=单体需目标。
- buff 变体：sfgp04 有 base/_01/_02 并列节（StaticData 27292/27302/27538），exe 选 _02 的规则未钉死——**实现规则：默认 base（buf_<技能ID>），变体经 Lua overlay 指定**；tjq04 走 _1/_2 轮换（StaticData 6013/6055，Go overlay 同值已核实）、hsqs04=15101/hsqs05=15105、tjq05=6043、tjq07=6050(LifeTime 5000)。
- skill_new.ini 含使 grep 误判二进制的杂字节，工具读取用整读方式（Java 无此问题）。

## 7. 伤害公式（Lua）

`scripts/combat/formula.lua` M.calc(c) → {damage, miss, crit, parried, reduction_pct}；
`ratio = 编译伤害/100/波数(正 r 段数)`；rand 由 Java 预掷传入（脚本不掷骰）。Java 兜底 `CombatFormulas` 同常量同顺序。
抓包参考值：飘字 143/92/67/57，NPC(1000HP) 每 hit 约 -130 HP（24 显示值与扣血有差，含浮动/减免）。

## 8. 与 Go 源码的差异清单（以抓包为准）

1. Go 只发 1 行 cooldown；F exe 发个人+GCD 两行（§5）。
2. Go 无 active_skill_rec、msg337、msg24/205/18、0x2D、0x13；F exe 全套（§3/§4）。
3. Go 出招发 State(动作名) 帧 + followup 段；F exe 技能动作纯走 msg20，State 只在收尾发 "stand"（idx19；idx183 是登录首帧的另一同名属性）。
4. Go msg20 尾参 int 1/float 1；F exe 发 0/0.0，且目标恒 obj(0,0)。
5. Go 命中掉落银子（MaxHP/100 夹 1..99）；F exe 零掉落。
6. cooldown_rec 第 4 列官方类型 u16；Java 协商表可自定但对齐 u16 以字节级贴近。

## 9. 真机反馈五问题取证（2026-11-02，combat_forensics2.py）

1. **战斗态进入**：抓包 LogicState 时间线——出招的 vital 帧仍 LogicState=0，命中敌人才切 1
   （后续小帧 n=6 携带）；打坐/脱战后回 0。实现：出招不切态，命中 enterCombat，10s 闲时退出。
2. **格挡闸门**：仅 LogicState=Fighting 时 engage 生效（非战斗静默忽略；release 保持原游离确认规则）。
3. **怒气上限**：官方属性 MaxSP=idx580/MaxSPAdd=idx583（F exe 全量帧恒发，其值 80）。
   不随下发客户端上限显示为 0；本服发 SP=progress、MaxSP=100/MaxSPAdd=0。
4. **技能可用性 CanUse**：View40 行帧 `{0x10 | isView=1 | u32 40 | u32 行号 | u16 1 |
   u16 CanUse(2236) | u8 0/1}`（15B，owner 字段=行号 1..N，抓包 2159 帧、每行 127 次周期重播）。
   不发则客户端技能图标恒不可用（能释放但显示灰）。实现：授予时全行置 1、施放置 0、
   max(个人CD,GCD) 到期置 1（事件驱动替代 exe 周期重播）。
   **值语义勘误（2026-11-04，52795/57378 取证 `tools/smoke/rage_canuse_57378.py`）**：
   官方值恒 1、**不按资源门控**——316 批 11730 帧中仅行 11（CS_gb_dgbf03，
   AConsumeQGP=10）出 0；怒招行（AConsumeSP=50）在 SP=26 时仍推 1；被释放的
   怒招行（chz04/hyjf08/yydf05/tjq08/shl08，行 20/87/134/178/187）**全程零推送**
   （无出招置 0、无 CD 尽置 1）。推送范围仅快捷栏 17 行（GM 号 207 已学行中）。
   怒招图标点亮/特殊动画由客户端按 SP+行内 AConsumeSP 自算；服务端只做周期保活。
   实现（对齐后）：授予置 1、施放置 0、个人 CD 尽置 1（不看 SP）、ticker 恒 1、
   SP 变化不推行帧（pushRageRowTransitions 已删）。

## 10. 技能 CD 文本显示源 = View40 行 CoolDownTime 属性（2026-11-02，view40_forensics.py）

**现象**：真机所有技能 CD 文本恒显示 1s（服务端出招判定与 cooldown_rec 双行时长均正确）。

**取证**（`F:\...\captures\local\127-0-0-1-59719` 2026-11-02 21:50，View40 0x18 行 207 条）：
- 0x18 行布局：`18 | u16 view | u16 objIdx | u16 propCount | (u16 propIdx + 0x09 表线型值)*`。
- 官方行的 `CoolDownTime`(idx 2034, i32) = **varprop 按级 CoolDownTime 毫秒**，17/17 全对齐：
  jysg01=2000、tjq04=1000、tjq05=15000、tjq07=40000、hsqs01=2000、hsqs05=15000、
  zs_default_01=5000（与出招 cooldown_rec 个人行 dur 同源）。
- **skill_init.ini 的 `cooldown` 字段官方数据全 0，不是显示源**——下发 0 时客户端
  CD 文本兜底显示 1s。
- 官方行还带 Java 未发的属性：VarPropNo（如 275281=varprop 节 id）、AConsumeMP、
  TargetShapePara2/HitShapePara2(f32)、Type(0)；UseLimit/Quality/Flow 官方值与 bak 语义
  不同（xhmdl01: UseLimit=1/Quality=6/Flow=27011 vs 本服 9/1/5）——显示异常再取证。

**修复**：`CombatSkillCatalog.personalCdMs(id, level)`（skill_new→skill_static→按级 varprop，
不走完整 compile）；`StarterSkills`/`StarterGrants` View40 行 CoolDownTime 改为
skill_init `cooldown>0` 显式覆盖优先、否则 varprop（生产官方数据恒走 varprop）。

**附带**：msg294 时间广播 `[294, tick, uptimeMs, unix秒, 游戏日f64]` 1s 一发——
cooldown_rec 的 start/end 是绝对 Unix 毫秒，客户端靠它对钟；msg414 三形态中
"reset"=单类 CD 重置、"sync"+203 个小号 id（0..2925，物品/道具 CD 类别，不含技能
CD 类别 16933/8837/50000）。


## 9. 空中出招位置重置根因（2026-11-03，抓包 127-0-0-1-50768 全解）

真机症状：轻功空中出招人物被拉回地面；参考服（Go exe）同操作不受影响。
对参考服整段抓包逐帧解码 + Java 端 `tools/smoke/position_reset_probe.py` 探针对拍，
出招回帧序列两服一致（零 0x1F/0x20/位置属性帧，337 原样回显空中坐标），差异锁定在
**技能状态元组语义**（FxGameLogic 自目标技能动作门读 CurSkill* + ModifySkillLockTime，
Go 源码 player_actor.go 注释实证）：

| 偏差 | Java(旧) | 参考服 exe | 修复 |
|---|---|---|---|
| ModifySkillLockTime[221] | 5000（抄旧 Go 源码） | **200** | PlayerActor 两处 5000→200；5s 硬直锁会打断空中动作 |
| 无目标技能 CurSkillTarget | obj(0,0) | **obj(自己)**（Go targetID 默认 playerObjectID） | SkillCastHandler targetKey 默认自身 |
| 收招技能状态清除帧 | 只发 0x12 删行 | **0x12 → n=6 清除帧 → State=stand** | 补发 skillStateClearUpdate()（exe #2014 六项形态：LogicState 回显当前 + CurSkillID/Target 清空 + CanNoLimit/CantBe(,)Locked 归零；62433 战斗抓包 LogicState 跨招=1 实证非硬编 0） |

收尾 State=stand 维持停发（2026-11-02 真机反馈贴地；参考服发它但当时伴随上述三偏差，
锁 200 后理论上可恢复，待真机验证再做）。

**探针**：`position_reset_probe.py`（复刻真机空中三连招 + msg15 字节模板打坐标补丁；
官方 msg15 = `[i32 15, i32 0xA0005000, i32 state, f32×4, text(33B 会话哈希)]` count=9）。

### 二轮修复（同日真机复测仍拉回地面后）

上表三修复上线后真机复测仍重置——根因另有其处，全部在**出招 buff 的下发形态**
（对参考服 exe 抓包算术取证，无需 IDA：exe 自身日志同时打印 value 串与 uid/expiry，
三招 value−expiry ≡ **11,644,736,000,000ms** 恒等差，即 FILETIME 纪元）：

| 偏差 | Java(旧) | 参考服 exe | 修复 |
|---|---|---|---|
| buff 槽位 | freeBuffSlot 从 1 扫 → 出招 buff 落**系统槽 1/2/3** | **槽 12**（Go 注释"System leases keep slots 1..10；routine Buffs allocate from 11..24"，格挡租约占 11） | freeBuffSlot 改扫 12..24；activate 加同 ConfigID 槽复用（Go activateSkillBuff 三级槽位序） |
| BufferInfo 第 5 字段 | Unix 毫秒（1A1A…） | **Unix毫秒+11644473600000**（1601 纪元，C38D…）——Java 旧值客户端解出"公元 1657 年已过期" | PlayerActor EPOCH_1601_OFFSET_MS（bufferInfo+bufferListString 两处） |
| buff 帧形态 | 出招后独立发 msg169+BufferInfo 槽帧 | **只随 0x10 全量面板内嵌**（三招 customs 无 169、无独立槽帧；Go 亦在 vitalUpdate 前 stage） | stageSkillBuffs 提前到 CD 行之前；删出招路径 emitBuffFrames（到期清槽帧保留=Go schedulePlayerBuffExpiry） |

注：QingGongHandler 缓降(216)与格挡(54)的 169 流程不动（各自已真机验证/槽位 11+）。
62433 官方抓包中花神四式有 msg169——与本 exe 形态分歧，若真机反馈花神 buff 显示回归再取证。

### 三轮修复：出招面板瘦身（抓包阀实证）

给 Java 加了 wire 级抓包阀（`transport/CaptureSink`，`<serverRoot>/captures/local/`，
格式同参考服可直接 capture_deep 解码）后对拍发现：**参考服每招面板 = 登录面板减 78 项**
（#2007 的 186 名瘦集），Mount/CurRiderType/NewRideID/Mount*Speed/WalkSpeed/CantMove/
State/ActionSet 等移动模式与外观属性官方**不随出招重发**；Java 每招全量 218 项硬推——
腾空出招瞬间重推 `Mount=''`/`CurRideMount=obj(0,0)` 会让客户端清骑行/移动态、把空中
人物强制放回地面（特效留在空中=客户端知道 337 坐标，仅模型被搬走）。

**修复**：`PlayerActor.castVitalUpdate()` = stateProperties 过滤到 `CAST_PANEL_NAMES`
白名单（186 名，逐名取自 #2007）；SkillCastHandler 出招改用之；SpeedRatio 默认 100→0
（exe 出招面板实发 0；旧 Go newPlayer 的 100 过时）。登录面板不动。

**2026-11-03 真机闭环**：三轮修复上线后用户实测空中出招不再落地，问题解决。
七项偏差全量对齐参考服 exe（lock200 / CurSkillTarget 自身 / n=6 清除帧 / 槽 12+ConfigID
复用 / FILETIME 纪元 / buff 面板内嵌无 169 / CAST_PANEL_NAMES 白名单+SpeedRatio=0），
其中出招面板瘦身（⑦）为决定性修复。

## 11. 怒招消耗下发 + 圈定/冲锋/腾空（bug.txt:45-49，2026-11-03）

### 11.1 怒招消耗下发（View40 行补集）

官方 View40 行携带而本服原缺失的属性族（`StarterSkills.skillRowProperties` 现经
`CombatSkillCatalog.skillRowData(id, level)` 下发）：

| 属性 | 官方索引 | 值源 | 说明 |
|---|---|---|---|
| VarPropNo | 2239 | 匹配 varprop 行号（=客户端 varpkg） | 原恒 0 |
| UseLimit | 2238 | skill_new.UseLimit | 原恒 9；缺省回落 9 |
| AConsumeHP/MP/SP/QGP | 2245/2246/2252/2254 | varprop→Consume→skill_consume | 仅 >0 才发（官方稀疏风格）；**怒招由此带出 AConsumeSP → 客户端显示怒气消耗并在怒气不足灰图标** |
| TargetShapePara1/2 | 2259/2260 | varprop→TargetShapePkg→attack_targetshape | f32；新装载该表 |
| HitShapePara1/2 | 2261/2262 | varprop→HitShapePkg→attack_hitshape | f32；地面圈指示/射程提示 |

官方行的 `Type(0)` 跳过（属名在 0x09 表多处出现，玩家域解析到 idx181 语义不明）。
服务端 SP 闸门本就存在（`PlayerActor.beginSkillUse`：SP&lt;AConsumeSP 拒 + 扣减），
此前体感「没检查」= 客户端看不到消耗 + 怒气无获得途径单向扣。

### 11.2 怒气获得（官方机制，用户 2026-11-03 确认）

怒气增长**只与单位时间打击次数有关、与伤害高低无关**：

- 攻击：实招命中**未格挡**目标 +2/次（多段招每段每目标各算=叠加速更快）；对方招架状态不增加。
- 被击：自己未格挡 +1、自己格挡（招架）+2 —— 本阶段无 NPC 攻击玩家路径，常量
  `RAGE_TAKEN_UNPARRIED/PARRIED` 预留未接线。
- 实现：`SkillCastHandler.awardRagePerHit`（单段结算后/多段每段）→ `PlayerActor.addSP`
  饱和 0..100，变化才发单属性 SP 帧（满值不发）。

### 11.3 211 长格式落点与 GROUND 目标模式

**长格式（111B）解析**（原只读 idx 0-5）：idx 6-8 方向点 tx/ty/tz（指向型非零）、
idx 9 空串、idx 10 目标对象（tag8=int64 `objId|owner<<32`）、idx 11-13 目标坐标。

**落点中心优先级**（`resolveCastPoint`）：可解析目标对象（战斗 NPC）位置 → idx11-13
非零 → idx6-8 非零 → 选中 NPC 位置（Go 近似兜底）→ null（放行不结算，Go "no center" 同款）。

**TargetMode 新增**（`compileTargetMode`）：

| HitShape | 原映射 | 新映射 | 语义 |
|---|---|---|---|
| 11（玩家点选地面圆） | SELECTED_AREA（需选中目标→无选中即静默拒=**圈定技能放不出的根因**） | **GROUND_AREA** | 免选中，圆心=落点 |
| 12（地面矩形） | CASTER_RECTANGLE | **GROUND_RECTANGLE** | 免选中，落点+施法朝向投影 |
| 2（以目标为圆心） | SELECTED_AREA | 不变 | 需选中目标 |

腾空圈定（IsAirSkill）无独立服务端门：空中出招已通（§9 七项修复），GROUND 落点 +
HeightDiff 高度差过滤天然覆盖（圆心在地面、目标在地面 dy≈0）。

**射程闸**：CanRush（skill_static）技能距离上限放宽到 RushRange（无值回落 15 米，
实测值域 {8,15,30}）；GROUND 落点用 max(RushRange, 30 米) 宽容闸（客户端按 TargetShape
本地限距，服务端仅防跨图）。冲锋位移信任客户端（用户确认）：服务端只结算伤害，
玩家位置由客户端移动同步驱动，msg337 仍回显 211 坐标。

**测试**：`GroundChargeAndRageTest`（圈定免选中按落点命中/超程拒/无落点零伤害；
冲锋 12m 放行 25m 拒+非冲锋同距对比；怒招 SP 闸门/扣减/命中回怒+SP 帧）。

## 12. 套路武器限制 + 空手基础外功（2026-11-04，bug.txt「套路 武器限制」落地）

**武器门控**：`skill_new.UseLimit` → `skill_use_limit.ini` 的 `r=0,ItemType,<code|code>,==`
规则编译为 `CombatSkill.weaponItemTypes`（101刀/102单手剑/103匕首/104双刀/105双剑/
106双刺/107长棍/108短棍/111-117暗器族）。出招时已穿武器 ItemType（`PlayerActor.
equippedWeaponItemType`，`applyEquipBonuses` 全量重算点同步，0=空手）不在集内
→ **静默拒**（与 CD/超距族同语义；客户端本地图标按武器自置灰）。拳/腿套路无
ItemType 条件（UseLimit 缺失或无武器行）→ 空集不闸，空手可出。同节其它条件
（门派/阵法/血量/战斗态，如打坐的 9=非战斗态）不在此期范围，忽略不编译。

**空手基础外功**：`meleeDamageRange` 与面板 Min/MaxMeleeDamage 同口径
（基础 20-35 + 装备外功 + 内功 Min/MaxMeleeDamage + PhyDamageAdd 平加；
MeleePower 为独立攻击强度不折入）。修复前空手 attack=0 走公式保底恒 1 点
（真机「打怪无伤害」根因之一）。掷骰序改为 miss/crit 先掷、区间后掷——
命中/暴击结论不依赖区间跨度（同种子稳定）。

**测试**：`WeaponLimitGateTest`（UseLimit=2→{102} 编译/空手静默零帧/持剑放行/
持刀仍拒/拳套空手可出）。
