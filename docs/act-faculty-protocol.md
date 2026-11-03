# 演武（Act Faculty）碎银额度/团练疲劳/动作链 —— 协议权威记录

> 2026-11-03 整理。数据源：F 盘参考服抓包 `captures/local/127-0-0-1-61579_*.bin`
> （演武短会话）与 `bak/127-0-0-1-10715_*.bin`（60 连击长会话）、客户端 lua
> （`lua64_unpacked` form_wuxue_act / form_wuxue_faculty / form_team_faculty_member）。

## 1. 客户端语义（lua 钉死）

| 属性（0x09 表索引） | 语义 | 客户端用法 |
| --- | --- | --- |
| ActDayValue(790) | 今日可用碎银额度（**文**，1 两=1000 文） | 主面板"可用碎银 X 两" = (ActDayValue−ActCostSuiYin)/1000 |
| ActCostSuiYin(791) | 今日已耗碎银（文） | 同上；F 登录帧实证 660（卡价 10/40/130 的整数和） |
| ActDayValueMax(797) | 每日上限（文）；**0=不限制** | 演武页"今日可用" = Max−Used（<0 不刷新） |
| ActDayValueUsed(796) | 今日已用（文） | 演武页"今日可用" = Max−Used |
| ActDayValueLast(795) | 剩余总量（文，跨日） | 演武页"剩余总量" = Last+Used−Max |
| TeamFacultyValue(777) | 团练消耗值 0..80000 | 剩余精力% = (80000−值)×100/80000（faculty_team_action.ini [setting] TeamValue=80000） |
| TeamFacultyLast(778) | 团练剩余可抵扣修炼度 | 团练面板 lbl_offset_faculty |
| TeamFacultyState(775) | 团练状态（0=未在团练） | 团练成员表开合闸 |

- 演武页两标签的数字显示依赖客户端 **switch 2812**（本服不推开关 → 恒显"不限制"，非 bug）。
- 卡价 = `faculty/actfacultyinfo.ini`：`[configId] r=<level>,<band>,<卡0>,<卡1>,<卡2>`，
  **基价即文**（勿乘 1000——乘错导致碎银余额判不足、打卡全部静默拒绝，2026-11-03 踩过）。
- 支付方式：1=碎银(C1)、2=官银(C2，未建档免扣)、4=银票(C4，未建档免扣)。单卡修为 +20000。

## 2. 进入演武时的下发帧序（F 参考逐帧，61579 #2188-#2206）

```
客户端 1006/24（开修炼面板） → 服务端：
  [0x10 n=1 FacultyName=""]            ← 故意清空逼 binder 重跑
  [0x10 面板瘦集 186 属性]              ← LogicState=0 Style=1 FillSpeed=200

客户端 1006/21（点"演武"按钮） → 服务端：
  [0x10 面板瘦集 186 属性]              ← 仅 3 值变化：LogicState 0→108、FacultyStyle 1→2、FillSpeed→0
  [0x1E custom [191,22,0]]             ← ACT_BEGIN：客户端开演武表单+演武相机

每秒节奏（持续整个演武）：
  [0x1E 294 时间对钟]
  [0x10 n=4 QingGongPoint=150,GJ*=0×3] ← 轻功点心跳（与 0x38 配对）
  [0x38 心跳回包]

客户端 1006/23 card,cost → 服务端：
  [0x18 view43 书帧]                    ← CurFillValue +20000
  [0x10 全量帧]                         ← LogicState 保持 108、ActCostSuiYin+卡价
  [0x1E [191,24,0]]                     ← ACT_PLAY：太极特效+1.5s 冷却+续卡循环

客户端 1006/22（退出） → [0x10 全量帧 LogicState=0 FacultyState=0 FacultyStyle=1]
```

- 客户端每 3 张卡自动 **22→24→21** 循环重开（引擎按 LogicState 0→108 翻转重启动作）。
- 动作 = 客户端引擎 `LS_FACULTY=108`（share/logicstate_define.lua:11）映射；
  服务端职责就是上面的 108↔0 翻转帧。
- **24/21/23 响应的 0x10 全部是同一套 186 名"面板瘦集"**（#2190/#2194/#2203 逐帧
  diff 全等，与出招瘦集 CAST_PANEL_NAMES 零差集）。不含 Mount/CurRideMount/
  ActDay*/TeamFaculty*/NorFacultyAdd——这些只在登录帧下发（全会话 ActDay* 仅现于
  登录帧 #18，ActCostSuiYin=660）。我服 SelectSchool 无数据源，实发 n=185。

## 3. 我服实现（2026-11-03，全部落地）

- `PlayerProgress`：actDayValue 族 5 字段 + teamFaculty 族 2 字段（快照/JSON/MySQL 持久化），
  `tryChargeActSuiYin`（有限额超限 false）；properties() 全量随帧下发。
- `ActFacultyCatalog`：actfacultyinfo.ini 解析（`cardBaseWen`，缺表 cost 0 免扣）。
- `FacultyCustomHandler.actPay`：pay=1 扣 CapitalType1 + 额度入账 + [碎银单帧, 0x18, 0x10, 191/24]。
- `PlayerActor.panelParityProperties()`：参考 188 属性对照补齐块
  （MoveSpeed/RunSpeed/JumpSpeed、InParry/ParryValue/PhyParryProb(Add)/*ParryDamRes(Add)、
  CapitalType0/2/4、MeleePowerAdd=臂力/MagicPowerAdd=内息/PhyHitAdd=DodgeAdd=ShotPowerAdd=身法/
  MagicHitAdd=MagicVaAdd=灵气、PhyVa=23/MagicVa=16（模板常量待钉）、终伤十件套、
  NeigongUseValue=书 power、FamousNeiGongName="书,层" 宽串。SelectSchool 无数据源不发）。
- `PlayerActor.qingGongHeartbeatFrame()` + LoginFlow 1s ticker：294 后随轻功点四元组。
- GM：`set_act_day_limit value`(两,0=不限制) / `clear_team_fatigue` / `set_team_faculty_last`
  （白名单+case+console.html 面板按钮齐）。
- 回归：`ActTeamBlockTest` 10 测（含 LogicState 108↔0 线帧级锁定 + 补齐块断言）、
  `ActFlowE2ETest`（真实 0x0A 信封全链）。

## 4. 已知坑

1. **卡价单位是文原值**——×1000 会让所有打卡静默拒绝（无任何提示帧）。
2. 部署后必须**重启进程**才生效；deploy.bat 可能卡 `pause`（MySQL 探活失败分支），
   手动部署 = 备份 jar → 拷贝三 jar → robocopy scripts。
3. E2E 探针 `tools/smoke/act_probe.py`：登录包为 25B 头（账号槽长 u32@0x15）+
   账号/密钥/登录串三槽（**长度必须 16 的倍数**）+ clientType + loginType 尾 4B；
   选角包 ≥0x54 字节；打卡信封第 1 值=1006、第 2 值=sub(int)、card/payment 为 f64。
4. **演武待机动画不出（2026-11-03 晚已修）**：帧序本身早已正确（真机 55512 会话
   LS=108+[22,0]×3 全在线上），根因是 0x10 整推 218 项登录全集——帧尾重推
   `Mount=''`/`CurRideMount=0` 清客户端视觉状态，把帧首刚下发的 LogicState=108
   （LS_FACULTY 待机动作）立即打回普通待机。与空中位置重置同类根因。修复 =
   1006 全家族响应改走 `castVitalUpdate()` 186 名瘦集（FacultyCustomHandler
   `writePanelUpdate`）。ActDay*/团练块改只在登录帧下发（与参考一致）。
5. act_probe.py 两处坑：选角包必须 ≥0x54B（1B 旧包被静默丢弃）；信封第 1 值必须
   是 msgId=1006、第 2 值才是子命令（只发子命令会被当独立 msgId 记
   "unrecognized" 丢弃）。

## 5. 团练（未实现，仅疲劳值下发）

991 = CLIENT_CUSTOMMSG_TEAM_FACULTY → `form_faculty_team.on_msg`，子命令
1/2/3/5/11/12/13/30/31/40/41/50（READY/PREPAR/PLAYING/OVER/成员/加入/离开/成败/兑换），
数据源 VIEWPORT_TEAM_FACULTY 视图（team_faculty_rec 记录 + PlayType/CurTurn/TotalTurn/
TaoLuID/CurState/QueuePoint/FailCounts 属性）+ 玩家 TeamFacultyState。客户端发 3=开始/4=退出，
设置面板带套路 id（如 xl_thd_001）。动作表在 faculty_team_action.ini（ready/wait/fail/over 段）。
