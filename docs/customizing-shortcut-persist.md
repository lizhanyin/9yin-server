# 个性配置（按键）与快捷栏持久化（2026-11-02）

> 协议权威源：`9yin_server1/captures/local/127-0-0-1-52795` 抓包直接解码 +
> Go 参考服 `shortcut_records.go`。解决 bug.txt「个性设置没修 每次上去都得重新设置按键」
> 与「技能栏修复（玩家自编辑不保存）」两项。

## 一、个性配置（按键/操作习惯）——全新闭环

### 协议（52795 抓包钉死）

| 方向 | ID | 载荷 | 语义 |
|---|---|---|---|
| C2S | **107** | `[107, configStr]` | 引擎 `customizing_manager:SaveConfigToServer()` 上传序列化配置串（登录期 seq=0 即首次上传，玩家改设置再传；会话内 38 次） |
| S2C | **705** | `[705, configStr]`（0x1E 壳） | `on_parser_customizing` → `UpdateToConfigObj` 灌回配置对象（会话内 39 次，与 107 逐条对答） |

配置串 = 纯 ASCII+控制字节（0x01-0x65，UTF-8 无损，抓包样本 144B）。**客户端本地不落盘**：
登录即默认键位并立即上传，服务端不回存档则每次重置——这就是 F 端「个性设置没修」的根因
（F 只回显当次上传、不持久化）。

### 实现（game/avatar/customizing + LoginFlow）

- `CustomizingStore`：`data/customizing.json` 主档 + MySQL `role_customizing`
  （VARBINARY(65535)，0005 建表脚本）双源，读优先后备、写同步 best-effort。
- **ClientReady 推送**：有存档 → 先发 705(saved)（在客户端登录期上传之前），玩家键位重登即恢复。
- **C2S 107 时序闸**：客户端登录期首个上传是**默认键位转储**（此时未必已应用恢复帧）——
  会话首个 107 若库里有存档则**跳过落库**（防默认覆盖玩家配置）并回发存档再确认恢复态；
  无存档则首个上传作为基线落档。后续上传（真实改键）全部落档并回显（F 同款）。

## 二、快捷栏编辑持久化（C2S 200/201）

### 协议（shortcut_game.lua + Go handleShortcutCustom 同款）

| 方向 | ID | 载荷 | 语义 |
|---|---|---|---|
| C2S | **200** | `[200, index, kind, id]` | 设置快捷栏（kind∈skill/item/neigong/zhenfa；item 的 id=unique_id） |
| C2S | **201** | `[201, index]` | 按**可见快捷栏索引**摘除（见 §十三勘误） |

帧序：替换 = `0x12` 删旧行 + `0x11` 增新行（shortcut_rec 官方下标 93，三列完整行）；
幂等重设零帧；摘除缺行静默。

### 实现

- `LoginFlow.handleSetShortcut/handleRemoveShortcut`：内存态（PlayerActor.setShortcut/
  removeShortcutIndex，201 按索引删——见 §十二勘误）+ 帧下发 + **role_shortcuts 落库**
  （现有表：`RoleInventoryStore.upsertShortcut` 按 shortcut_index 原位更新/追加、
  `deleteShortcut` 按 index 删）。
  登录链已有 `shortcuts(roleId)` 回放（grantStarterProgress），至此设置 → 落库 →
  重登回放闭环。
- tag2 负值宽容读取：客户端 nx_int 负数以 u32 回绕上行（-3 → 4294967293），
  `(int) value.int32` 下转还原（`LoginFlow.tolerantInt`）。

## 三、SQL

- `admin/sql/0005_add_role_customizing_20261102.sql.gz`：新表 role_customizing
  （基线快照表形态，FK roles ON DELETE CASCADE）。role_shortcuts 复用现有表，无新脚本。

## 四、测试

- `CustomizingFlowTest`（3）：ClientReady 推送帧形（705+存档串）/ 首上传跳过防覆盖 +
  回发存档再确认 / 后续上传落档回显 / 无存档首上传建基线。
- `ShortcutCustomFlowTest`（2）：200 首设-幂等-替换帧序（0x11/0x12/93 下标）/ 201 按行删 +
  缺行静默 / 非法参数零帧。

## 五、多段伤害与攻击距离（同批补齐）

### 多段伤害（trigger_effect 帧位分次结算）

52795 抓包钉死的形态：单次出招对同一目标发**多条** msg24/扣血帧（均值 8.7 条/招），
同招各段**伤害值恒定**（113×3、97×4——每招掷一次、段间重复），每段全套命中帧
[NPC 0x10 → 0x13 → msg24 → 0x2D → msg18 → msg205]，目标中途死亡后续段跳过。

段时刻来源 = zhaoshi 动作表 `trigger_custom_N=段;trigger_effect;帧号;效果名`（30fps），
按抓包对照实证的过滤序（`CombatSkillCatalog.compileSkillActions`）：

1. 效果名含 `_hurt` 的帧 = 伤害段（gxfhj02 的 `CS_jhgp_gxfhj_hurt`×4 = 每招 4 段 ✓
   shl03×4、gxfhj05×8——观测 2-3 段是目标中途死亡的自然截断）；
2. 无 hurt 时取效果名以技能核心名（尾段去数字，sfgp01→sfg）前 3 字母开头的帧
   （shl08 的 `shl08_NN`×6 = 6 段 ✓）；
3. 刀光/fly/通用特效（mwcq_hurt_01 之外的 `_01a` 型）与技能全名施法帧剔除；
   sfgp01 的 `CS_jh_sfgp01_1/_2`（帧4/77）→ 2 段，与 62433 抓包 2 msg24/招吻合 ✓；
4. 皆无 → 单段即时结算（原路径不动）。

实现（`SkillCastHandler.scheduleMultiHits`）：出招时解析目标 + 掷一次伤害值，按帧位
逐段调度；首段命中进战斗+插 buff 帧+目标控制，后续段 `touchCombat` 防段间闲退；
末段按整招累计伤害喂经脉真气。varprop 的 `HitCount` 与观测段数不相关（误导项，未采用）。

### 攻击距离闸门

SELECTED/SELECTED_AREA（单体需目标）技能：出招时校验施法者(211 上报坐标)→目标距离，
射程 = max(HitShape 半径, 近战兜底 8 米)，**超距静默拒**（不进 CD/动作，与"被拒不回帧"
惯例一致）；客户端面板变红提示为本地行为不受影响。形状技能（以己圆/扇/矩）以施法者
坐标为圆心、半径即射程，天然有界不走此闸。starter 集里 `CS_wd_tjq02`（HitShape=0）即
SELECTED 模式。

## 六、测试

- `MultiHitAndRangeTest`（3）：gxfhj02 段帧位 [233,600,900,1466]ms 编译断言 / 4 段
  恒定伤害飘字 / 中途击杀后续段跳过（探针法动态断言）/ tjq02 超距零帧+贴身受理。
- `CustomizingFlowTest`（3）/ `ShortcutCustomFlowTest`（2）：见上两节。

## 七、操作模式持久化（C2S 99，2026-11-04 补）

> 真机反馈：快捷键保存后重登异常。键位数据两域（144B customizing + 198 主串）都已闭环，
> 缺的是**操作模式**——键位方案与模式错配的表象。用户钉死方向：99 要存且要下发。

### 协议（lua64 源码钉死）

| 方向 | ID | 载荷 | 语义 |
|---|---|---|---|
| C2S | **99** | `[99, mode]`（单 int） | `CLIENT_CUSTOMMSG_SET_OPERATEMODE`：`form_system_interface_setting.switch_mode` → `custom_set_operate_mode` → `game_visual:CustomSend(99, mode)`；0=3D（WASD）/ 1=2.5D（点击） |
| S2C | 0x10 属性 | `OperateMode`（0x09 表索引 **223**，type3 int32） | 玩家属性回推；跨服重连 lua 直接 `QueryProp("OperateMode")`，正常登录由引擎消费恢复模式 |

注意：F 盘参考服抓包（50768~62185 七会话）**无 99 上行也无 223 下行**（会话里没切过模式），
回推形态无参考端范本，按属性表 + 跨服 lua 语义实现。保存界面（form_shortcut_save）
点保存时引擎另发 ShortcutKey1/2 属性写与 107 配置串，**不发 99**——99 只在
3D↔2.5D 切换确认框点确定时发。

### 实现（LoginFlow + RoleInventoryStore）

- `dispatchCustom` 增 `case 99`：`handleSetOperateMode` 校验 mode∈{0,1}（非法静默零帧），
  回推 `operateModeFrame`（0x10 单属性帧），落库失败仅告警。
- 持久化：`role_keybinds.operate_mode`（TINYINT NULL，NULL=从未设置；键位域同表）。
  UPSERT 注意 bind_value NOT NULL 无默认值，首插补空串（`admin/sql/0008`，幂等 ALTER）。
- 登录 post-spawn：`pushSavedKeybind` 后紧跟 `pushSavedOperateMode`（共用 keybindPushed 闸），
  无存档零帧（客户端保持本地默认）。

## 八、键位方案槽三串闭环（C2S 195，2026-11-04 补）

> 真机反馈：保存面板"方案一/方案二 恒未保存"。面板 `check_saved` 读**服务端属性视图**
> `ShortcutKey1/2`（QueryProp），不回推则界面不认——键位串本身早在走，缺的是方案槽协议。

### 协议（bak 抓包 12173/1578 双帧钉死 + form_shortcut_save.lua 语义）

| 方向 | ID | 载荷 | 语义 |
|---|---|---|---|
| C2S | **195** | `[195, scheme, str]`（引擎级，非 lua custom_define） | `ShortcutKey:EndEdit(commit, scheme)` 上传键位串；scheme **0=当前启用/1=方案一/2=方案二**（样本 658B，形 "项,键码;…"） |
| S2C | 0x10 属性 | ShortcutKey=**198** / ShortcutKey1=**199** / ShortcutKey2=**200**（均 type7，串尾补空格 u32=len+1） | 官服收到 195 不回包（抓包无 198/199/200 即时回显）；但我们回推——客户端属性视图以服务端下发为准 |

模型（form_shortcut_save.lua）：方案一/二=两份存档串；**当前使用哪套 = bind_value（198）**
——客户端"启用"方案 N 时引擎以 scheme 0 上传该集，bind_value 自动跟随启用集，
重登回推 198 即用户所启用的那套；模式（223）随启用时 switch_mode 的 99 同步。

### 实现（LoginFlow + RoleInventoryStore）

- `case 195` → `handleKeybindUpload`：scheme∈{0,1,2} + 串校验（≤4096、仅数字/逗号/分号），
  回推对应属性帧 + `role_keybinds` 落列（0→bind_value、1→shortcut_key1、2→shortcut_key2）。
  **UPSERT 坑：slot 0 目标列即 bind_value，列单写**（"Column specified twice" 硬错）。
- 登录 post-spawn：`pushSavedKeybind`（198）后补 `pushSavedKeybindSchemes`（199/200，
  无档零帧）+ `pushSavedOperateMode`（223），共 keybindPushed 一闸。
- 探针 `probe_operate_mode.py` 扩到 14 项：195×3 槽回显 / DB 三列 / 重登四属性全回推。

## 九、跨场景重推（2026-11-04 修复）

> 真机反馈：跨场景后键位数据消失。根因：切图 0x0C 清客户端玩家对象，addObject(0x0D)
> 重建的属性档不含键位四项；`completeSceneReentry` 旧注释假设"键位是连接级不重发"是错的
> （个性配置 705 确是连接级——它落在 lua 配置对象上，不随对象清）。

修复：`completeSceneReentry` 标志复位清单加 `keybindPushed = false`，重入收尾经
`grantOncePerConnectionRows` 与轻功/经脉/皮肤/时装同链重推 198/199/200/223。
单测 `LoginFlowSceneSwitchTest.sceneSwitchRearmsKeybindPushForPropertyReplay`
（MySQL 集成式：scratch 角色种四列 → 切图收尾 → 断言四单属性帧）；
探针扩到 21 项（GM switch_scene_preset 全链，注意会话需先发 0x0A 进 InWorld 相位）。

## 十、0x0D 属性档内嵌（2026-11-04"模式没推"修复）

> 真机反馈：模式帧发了但客户端不认。日志实证 "pushed saved operate mode" 两帧均在——
> **帧到了引擎不读**：引擎在玩家对象创建时读 0x0D 属性档，post-ready 0x10 补推太晚。

- 官服佐证：登录 0x0D 大档（count=121）内含 198（642 形态=641 串+NUL，**无尾空格**），
  且档内+单属性帧**双发**（frame#16 + frame#26）——Java 同款双发。
- 实现：`PlayerActor.KeybindArchive(bindValue, operateMode)` + `addObject` 三参重载，
  onChooseRole / applySceneSwitch 两调用点传入 `loadKeybind/loadOperateMode`；
  post-ready 单属性帧链不动（幂等重复=官服形态）。
- 195 校验放宽：`[0-9,;]+` → 可打印 ASCII（真机引擎另发 649B 形态串被旧正则误拒）。
- 探针 24 项：新增 0x0D 档解析（count@0x3d 驱动，**idx=0 是合法属性 Type 勿当哨兵**；
  choose 链帧必须保留——玩家档在 choose 突发里，ready 突发全是 NPC 档）；
  切图段按操作码等待（环境 0x10 会插队，收帧计数会错过 0x0C）。

## 十一、运维注意

- **部署顺序**：必须先 Stop 旧进程再 cp 覆盖 jar——运行中 JVM 的 jar 被覆写会直接
  崩进程（2026-11-04 01:05 事故：cp 在 kill 前执行，服务无异常日志即死）。
- 服务端仍有原因不明的突然终止（01:15 一次，无 hs_err 无异常，用户会话中被杀），
  复现时先查 hs_err_pid*.log 与 Windows 事件日志。

## 十二、快捷栏拖拽修复（2026-11-04 真机反馈）

> 症状：① 技能拖出快捷栏在空白处松手不移除；② 拖到已占栏位时"覆盖失败、原栏位还在"。

### 根因（两处叠加 + 两个同族潜伏）

1. **201 语义误读**：Go/Java 都把 201 参数当"记录行号"（Go `removeShortcutRow`）。
   实际客户端 `shortcut_game.lua` 的 `remove_shortcut(index)` → `custom_remove_shortcut`
   的全部调用点传的都是**可见快捷栏索引**（`game_hand.lua` 拖出释放/点击、
   `put_other_short` 交换、`form_jingmai` 清栏遍历 beginindex..endindex）——
   `custom_sender` 形参名 `row` 是误导。行号≠索引（starter 布局 idx20 在 row8），
   于是 201(20) 删掉 row20（idx27 的行）或落空 → 拖出无效 + 交换错删无辜行。
2. **替换原位 set vs 客户端删+追加**：Java `setShortcut` 替换是 `list.set(row)`，
   但下发帧序是 `0x12 删旧行 + 0x11 增新行`——客户端记录表删行后**重编号**、
   新行**追加表尾**，服务端列表原地不动 → 一次替换后两端行号口径永久错位，
   后续所有 0x12 删错行（表象即"图标异常"）。Go 是 `append(append(前段,后段...), 新行)`
   删旧追新，Java 移植时走样。
3. **（潜伏）DB 模式回放不入内存**：`grantStarterProgress` 只发 role_shortcuts 帧，
   `player.shortcuts` 始终空 → 200/201 的行号计算从登录起就与客户端无关；
   且打坐幂等门 `hasShortcutIndex(9)` 恒假 → idx9 重复下发。
4. **（潜伏）切图后整栏丢失**：starter 分支 `unchanged` 跳过零帧，而 ExitScene 清
   客户端记录行——Go 有独立 `grantShortcutRows`（快照全量重放）兜底，Java 移植丢了。

### 修复

- `PlayerActor`：`setShortcut` 改**删旧行+追加表尾**（镜像客户端表操作，行号同口径）；
  `removeShortcutRow` → `removeShortcutIndex(index)`（返回行号+行，供 0x12 用）；
  新增 `resetShortcuts(rows)`（DB 基线整表入内存）。
- `LoginFlow.handleRemoveShortcut`：按索引删 + 索引范围校验（0..4096），未命中静默。
- **每场景代全量重放**：`grantOncePerConnectionRows` 新增块（轻功后、打坐前）——
  首次裁决 DB 基线（非空→`resetShortcuts` 入内存，此后内存为唯一事实源；
  空→starter 回退并记住），随后 `StarterGrants.grantShortcutRows` 按快照整表重放；
  `completeSceneReentry` 复位 `shortcutRowsSent`（同键位闸模式）。
  `grantStarterProgress` 不再发快捷栏帧（dbShortcuts 参数改为 starter 补授开关）。
- 登录帧序不变：starter 模式登录时快照为空（重放零帧），打坐/starter 15 行仍在
  原位（golden 1698/1732-1746 段界不动）。

### 测试与探针

- `ShortcutCustomFlowTest`（5）：201 按索引删+缺行静默 / 替换移表尾（行序
  [10,12,11] 断言）/ 交换四连（put_other_short 帧序 0x12行0-0x11-0x12行0-0x11，
  两栏互换、第三栏不受牵连）/ 拖出按索引删 / 非法参数零帧。
- `LoginFlowSceneSwitchTest.sceneSwitchReplaysShortcutRowsAfterExitScene`：切图
  重入后会话内替换值（idx40=A→B）整表重放、idx9 无重复行。
- `probe_shortcuts.py` 扩拖出探针：201(索引)→0x12(行号=索引所在行)、重复静默、
  200 恢复→0x11。
- `LoginFlowTest`/`LoginFlowSceneSwitchTest` 的 Go 仓库根支持 `GO_SERVER_ROOT`
  环境变量覆盖（默认 G: 不变）。

## 十三、遗留

- 240（打坐 SITCROSS）与 217/218 仍在 backlog（打坐有独立状态机，另行批次）。
- yydf04 型引导循环技（观测 6-8 段浮动 > 单趟动作帧数）当前按单趟帧位结算，未做
  动作 Loop 循环判定。
