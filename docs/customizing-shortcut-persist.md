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
| C2S | **201** | `[201, 行号]` | 按记录行号摘除 |

帧序：替换 = `0x12` 删旧行 + `0x11` 增新行（shortcut_rec 官方下标 93，三列完整行）；
幂等重设零帧；摘除缺行静默。

### 实现

- `LoginFlow.handleSetShortcut/handleRemoveShortcut`：内存态（PlayerActor.setShortcut/
  removeShortcutRow）+ 帧下发 + **role_shortcuts 落库**（现有表：`RoleInventoryStore
  .upsertShortcut` 按 shortcut_index 原位更新/追加、`deleteShortcut` 按 index 删）。
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

## 七、遗留

- 240（打坐 SITCROSS）与 217/218 仍在 backlog（打坐有独立状态机，另行批次）。
- yydf04 型引导循环技（观测 6-8 段浮动 > 单趟动作帧数）当前按单趟帧位结算，未做
  动作 Loop 循环判定。
