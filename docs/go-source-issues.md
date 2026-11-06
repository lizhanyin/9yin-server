# Go 源码（go-server1）问题清单 · 历次发现汇总

> 汇总 2026-10-28 ～ 2026-11-06 期间对 `G:\pojie\ai\9yin-go-server1`（= 参考服旧版源码）
> 的分析中发现的问题，按**可信度分级**组织：哪些值照抄即错、哪些方言/帧型不可用、
> 哪些整族功能缺失、哪些是数据坑、哪些是"Go 对但复刻易抄错"。
> 总原则：**F exe（9yin_server1 部署端）比 go-server1 源码新，两源冲突一律以真机抓包为准**
> （combat-skill-211.md §8 开篇）。
>
> 各条目均标注出处文档；Java 侧处置状态截至 2026-11-06。

---

## 0. 总定位（一句话版）

| 维度 | 结论 |
|---|---|
| 源码新旧 | go-server1 源码 **落后于** F exe：active_skill_rec / msg337 / 24 / 205 / 18 / 0x2D / 0x13 整族帧 Go 源码没有 |
| 0x09 属性表方言 | Go 的 228 字段方言真机**可用**；官方 2370 项方言也可用（type4/6 按 double 解）；bak 2424 方言**不可用**（已推翻） |
| 0x0A 记录表方言 | Go 的 6 表自协商方言对客户端原生 C++ 消费者**不可用**（CoolLogicManager 不启动 CD）→ 必须官方 447 schema 整表透传 |
| 帧型可信度 | Go 注释自称 proven ≠ 官方在用（0x20 ServerMoving 官方真机零帧，全走 0x21） |
| 数据可信度 | data/ 含大量**自制/占位**内容（drops_mvp 自制包、占位巡逻、38 键场景预设），不是官方数据 |

---

## 1. P0 · 值错误 / 私设（照抄即错）

| # | 问题 | Go 源码值 | 正确值（实证） | Java 处置 | 出处 |
|---|---|---|---|---|---|
| 1 | ModifySkillLockTime（idx221） | 5000 | **200** | 已修（PlayerActor 两处）；5s 硬直锁会打断空中动作，是"空中出招拉回地面"根因之一 | combat-skill-211.md §9 |
| 2 | MaxVisuals（EntryScene） | 1000000（私设） | **画质档位 u16=100**（官服 0x0B 抓包） | 已纠正 | go-replica 决策记录 2 |
| 3 | msg201 删除快捷栏参数语义 | 形参名 `row`（记录行号）——**误读** | **可见快捷栏索引**（shortcut_game.lua 全调用点 + 抓包 `[201,209]` 实证） | 已修（removeShortcutIndex） | protocol-notes §7.3；customizing-shortcut-persist §十二 |
| 4 | msg20 尾参 | int 1 / float 1 | **0 / 0.0**，且动作目标按技能类别三分（TargetType==4 ⇔ aim 对象），非恒 obj(0,0) | 已修（D3 判据改 TargetType） | combat-skill-211 §8.4；combat-traffic-compare §5-D3 |
| 5 | 命中掉落 | 掉银子（MaxHP/100 夹 1..99） | **零掉落** | 已对齐 | combat-skill-211 §8.5 |
| 6 | 出招动作通道 | 发 State(动作名) 属性帧 + followup 段 | 技能动作**纯走 msg20**；State 只在收尾发 "stand"（idx19） | 已对齐（stand 帧曾故意停发，待真机复验） | combat-skill-211 §8.3；traffic-compare D10 |
| 7 | cooldown 行数 | 每招 1 行 | **个人 + GCD 两行**（个人 team=0xFFFF，GCD team=组号） | 已对齐 | combat-skill-211 §8.1 |

---

## 2. P1 · 方言 / 帧型不可用或未经官方验证

| # | 问题 | 事实 | Java 处置 | 出处 |
|---|---|---|---|---|
| 1 | **0x0A 记录表自协商方言失效** | Go 协商 6 张表（cooldown_rec 下标 5 / Task_Accepted 下标 2 且 7 列 / shortcut 4 / completed 3 / ActiveQG 1）；官方是 **447 schema 整表**（cooldown_rec=109、Task_Accepted=29 且 **28 列**、shortcut=93、completed=31、ActiveQG=117 且 [7,1]）。客户端 CoolLogicManager 按**官方下标**绑定，自协商下标下 CD 不启动 | 已切官方整表透传（official_record_table.bin），行帧索引全换官方下标、缺列补零满宽 | jingmai-cd-qinggong.md §一 |
| 2 | **45B 0x20 ServerMoving 是官方不用的帧型** | Go 源码注释自称 proven，但 Go 服日志无任何巡逻输出；官方真机两服一致**全部走 0x21 movement**（每秒一拍批量 3+44N，移动帧 spd=WalkSpeed flags=1 / 到达帧 spd=0 flags=0；NPC 移动**不配 0x1F**） | 已切换 0x21 批量帧（npc-mappath-patrol） | npc-mappath-patrol.md §帧型更替 |
| 3 | **0x38 心跳回显缺失** | "Go 源码无此帧"曾导致 Java 也不回；实为客户端 0x18 ping（12B）的逐字节回显，缺失 → RTT 配对超时 → HUD 延时恒 3000ms 封顶、特效延迟 | 已修（HeartbeatMessages.heartbeatReply，仅按 ping 回） | go-replica §8；traffic-compare D11 勘误 |
| 4 | 0x09 属性表缺 type 4/6 | Go 源码时代 4/6 不入属性表；真机 2370 项表两者都在（type4=58 项、type6=33 项），且**均按 8B double 解**（CapitalType1=104514.0；按 4B 解 188 属性面板必错位） | 已切真机 2370 方言（双域解析） | protocol-notes §4；go-replica 决策记录 1 |

---

## 3. P2 · Go 源码缺失的整族功能（靠抓包 / IDA / bak 补齐）

**已确证 Go 源码没有**（combat-skill-211 §8.2）：

- active_skill_rec（出招行 + 收尾 0x12 删行）
- msg337 位置回显、msg24 三形态（玩家/NPC→玩家 c1=7/武技连击段 c1=71）、msg205 命中确认、msg18 hurt_half、0x2D 受击特效、0x13 fight_relation_rec
- cooldown 双行（见 §1.7）

**参考服（F exe）有、Go 源码未证实**（取证来源非 Go 源码，勿在 Go 里找）：

- **武技连击 wuji**（skill_static `LevelupRelevance=wuji_*` 触发：4 跳 AOE + c1=71 跨目标段 + msg169 buff 加/删族）——traffic-compare D2
- **NPC 战斗 AI 出招链**（怪用 jn_* 技能反打：msg20/337 目标=玩家、msg24 c1=7 实伤 1、0x2D 同步）——D1
- **怒气经济全貌**：格挡清怒（SP=0 单帧 18B）+ 被击 +2（msg24 c1=10 四值短形态）+ 时间回复 5/s（锁 msg294 秒栅格、满值停推、大批帧形）——rage-recovery-frames.md
- **msg30 MOVEITEM / msg31 USEITEM / msg886-889 一键穿**：证据链 = menu2.exe 字符串 + bak 反汇编（msg31 恰 4 值、uid=MD5(roleId:bag|equip:slot)[:16] 等）；"早前把使用挂 30"是误判，30 是纯移动——item-use-msg31.md、装备外观与风物志挂载.md
- **冲锋 staged 暂存机制**（IDA 9yin-game-native-menu.exe：stagePreCast 30s 窗口 + msg25 skill_pause + msg24 c1=61 起跑特效；受理三段式射程内/暂存带/静默拒）——traffic-compare §9；commit 5c07a6a
- **msg294 对钟、msg414 三形态**（reset/sync(204 小号)/gamestep；Go 只用 reset）——combat-skill-211 §10、traffic-compare D12
- **官方巡逻路径**（mappath 采样 + 0x21 节拍）：Go 复刻是**占位巡逻**（前 3 个非战斗 NPC 每 5s X±1.5 摆动）——npc-mappath-patrol.md
- **场景预设表**：Go 表 38 键 vs menu2 exe 提取 105 键（并集 107，另加踢馆 tiguan_1..35）——gm-command-wiring.md
- **View40 CanUse 官方节奏**：仅快捷栏 17 行、值恒 1（不按资源门控；被释放的怒招行全程零推送）——combat-skill-211 §9.4 勘误
- **面板身份块/词缀/内功详情**（PowerLevel/Justice/Evil/词缀累积）：探查结论**均不存在于 go-server1**，从 bak（真机验证过的旧 Java 分支）移植——go-replica 四期

---

## 4. P3 · 数据坑（go-server1 data/ 与资源树）

| # | 问题 | 说明 | 出处 |
|---|---|---|---|
| 1 | drops_mvp.json 是自制包 | 头注释自述"MVP 自制包承接原版缺失包体"，现 5328 包——**不是官方掉落表** | item-use-msg31 §5 |
| 2 | giftbox.json 全发语义 | 固定全发（weight 无意义），322/764 箱，仅 gm-web 用 | 同上 |
| 3 | "官服 zip"不实 | `九阴服务端.zip`（31,546 条目）= go-server1 工作树快照，**两个"官服"zip 均非官服** | item-use-msg31 §7 |
| 4 | Npc_Func 旧表过期 | 成都绑定与现网 ConfigID 不匹配，只留离线审计 | protocol-notes §12 |
| 5 | 内功书 QName 双记录 | 经典可见记录 vs `mind_` 隐藏记录（Visible=0）——一律用经典路（ng_wd_001/SD5 等，勿用 1113 等 mind_ 值） | protocol-notes §14.27 |
| 6 | shop.ini 键 "0" 重复 | 常规 INI 读取器只剩最后一条，须逐行扫 | protocol-notes §12 |
| 7 | NPC 模板 StopTime 列 | 取**第二次出现**（物理列 87），唯一出现才取首值 | protocol-notes §14.26 |
| 8 | GBK 引号 TSV | 字段可含嵌入换行，须 CSV 语义；分隔行=全空单元格是版式不是数据 | protocol-notes §12 |
| 9 | golden fixture 时钟依赖 | Go golden 生成时 fixture 玩家 24h 内功 Buff 已过期（vital 820 vs 运行时 890）→ e2e 对 0x10 只比 opcode；View43 同理 | go-replica §7.2 |

---

## 5. P4 · Go 行为正确但复刻易抄错（陷阱，非 Go 缺陷）

1. **PauseTime 双精度**：Go 表达式是无类型常量（双精度求值再转 float32）。Java 必须 `(float)(N*1000.0/30)`，逐 float 运算差 1 ulp（太极拳 08 实测字节不等）。（go-replica §7.1）
2. **无目标技能 CurSkillTarget 默认 obj(自己)**（Go targetID 默认 playerObjectID）——Java 旧版发 obj(0,0) 曾致空中出招异常。（combat-skill-211 §9）
3. **67B 之外的 C2S 运动包含目的地/速度假元组**，扫出的坐标绝不能入库；合理性界 |X|,|Z|≤10000、|Y|≤3000、|Orient|≤20。（protocol-notes §6.2）
4. 0x09 表**顺序冻结只能尾部追加**、0x11 的 +0x0C/+0x0E 是源行 start/count 不是列区间、0x12 删行恰 14B 删的是 0 基记录行号、Task_Completed 只追加（前导零行全发）、TVarList 枚举可能是 double、msg414 用 reset 不用 sync、UTF-16 长度前缀含 NUL……完整 28 条见 **protocol-notes §14 坑清单**（复刻必读浓缩）。
5. **golden 是持久字节真值**：Go 侧 `NINEYIN_GOLDEN_OUT=… go test -tags golden -run TestDumpGolden` 导出，Java 逐字节断言；带属性帧用官方快照 `*_official.*`。（go-replica §6）

---

## 6. P5 · 设计性留空 / 已知未实现（Go 源码与参考服共同边界）

- **任务系统**：Task_Accepted Go 只发 7 列兼容前缀（官方 28 列）；功能解锁位图两族都要发才点按钮（protocol-notes §5.2）
- **剧情/副本门未复原**（故意落空；零售门在穿过 MoveTarget/SpringRange 时自动触发，X/Z 判定 + 短冷却）（protocol-notes §9）
- **禁地**：无战斗数据，官方服务器侧数据无法获取（bug.txt V3）
- 骑乘 243/266（CurRideMount 真值）、兵录修改 943、邮件域之外的社交系统——go-replica §8 遗留清单
- 个性设置/按键不持久（bug.txt，Java 已自行补齐 role_shortcuts 落库闭环：customizing-shortcut-persist.md）

---

## 7. 经验规则（给后续取证的）

1. **Go 源码注释的 "proven" 不算数**——0x20 教训：以真机抓包有无该帧型为准。
2. **Go 与 F exe 冲突 → 抓包赢**；Go 与 bak 冲突 → 看 F exe（bak 是旧 Java 分支，部分块真机验证过可优先于"凭空实现"）。
3. **自协商下标/方言是高危假设**：客户端原生 C++ 消费者可能按官方固定下标绑定（cooldown_rec 109 教训）；同理 0x09 表内容即索引语义（0x09 帧无索引列，bak 2424 表教训）。
4. **Go data/ 的"自制/MVP/占位"字样要当红旗**，先查头注释与四副本 md5 再当官方数据用。
