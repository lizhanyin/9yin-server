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
   不发则客户端图标恒不可用（能释放但显示灰）。实现：授予时全行置 1、施放置 0、
   max(个人CD,GCD) 到期置 1（事件驱动替代 exe 周期重播）。

