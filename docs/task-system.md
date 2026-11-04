# 任务系统（一期：对话型任务闭环）

NPC 点击 → 对话菜单 → 接任务 → 步骤行 → 提交 → 奖励 → 持久化的完整闭环。
协议语义逆向自客户端 lua64（`E:/Workspace/project/game/res1/lua64`），配置数据
来自 share 任务表（`share/task/**`）与 npcconfig 任务归属列。

## 一、协议逆向结论（客户端 lua64 出处）

### 1.1 消息通道

| 通道 | 方向 | 用途 |
|---|---|---|
| 0x07 对象请求（33B：seq@17/objId@21/owner@25/**FuncID@29**） | C2S | 两段式：FuncID=0 初点 NPC；FuncID=mark 菜单选择 |
| 0x1C SERVER_MENU | S2C | NPC 对话菜单（`NpcMessages.serverMenu`） |
| 0x1D 清菜单 | S2C | `NpcMessages.clearMenu` |
| 327 CLIENT_CUSTOMMSG_TASK_MSG | C2S/S2C | 任务子命令族（下表） |

### 1.2 官方 funcid 编码 vs 本服 mark 路由

官方客户端菜单功能号（`form_talk_data.lua` g_menu_id，base=10⁶）：

- 接任务 funcid = **100×10⁶ + taskId**；交任务 = **(200+选择偏移)×10⁶ + taskId**
- 放弃 300×10⁶ 起；离开 600×10⁶；普通对话 802×10⁶
- 引擎 `SelectMenu(npcid, funcid)` = 0x07 FuncID 回发

**本服方言**：官方 funcid 数字空间被对话系统替换为 `NpcDialog` 本地 mark
路由（每次对话从 1 递增；protocol-notes §12 有意不复用旧空间）。任务窗
（sub=1/sub=2）的 funcid 字段填 mark，客户端原样回传 0x07，服务端查
mark 表路由——闭环等价。

### 1.3 C2S 327 子命令（task_define.lua）

| 子命令 | 值 | 参数 | 本服处理 |
|---|---|---|---|
| giveup_task | int 0 | taskId | ✅ 放弃（删 Task_Accepted 行） |
| accept_task | int 1 | funcid | 仅新手帮助窗用（form_help），正常接取走 0x07 |
| reset_task | int 2 | — | log-only |
| share_task / share_res | int 3 / str "share_res" | — | log-only |
| complete_task | **str** "complete_task" | taskId, presentId, npc(obj) | ✅ 提交（走 submitTask） |

注意子命令线型混合：`custom_giveup_task` 发 `nx_int(0)`、`custom_complete_task`
发 `nx_string("complete_task")`——解析需按 tag 分支（int 2/3 vs string 6/7）。

### 1.4 S2C 327 `task_msg_logic(submsg, ...)`（命令名寻址）

首值为窄字符串命令名（`CustomMessages.modernCustomCommandMessage`）：

| submsg | 载荷 | 语义 |
|---|---|---|
| 1 | npcIdent, info（14 段 '\|' 串） | 接任务窗 `on_show_accept_form` |
| 2 | npcIdent, funcid, taskId, titleId, menuId, condLevel, condTips, line | 交任务窗 `on_show_submit_form` |
| 4 | npc, taskId, menuStr | 未完成提示 |
| **8** | taskId | **提交完成**（刷新头顶 + on_task_submit） |
| **9** | taskId | **接受成功**（on_task_add，客户端读 Task_Accepted 行渲染） |
| 15 | npcConfigId, flag | NPC 头顶标记（2 可交 / 4 可接） |

sub=1 的 info 14 段：funcid/taskId/titleId/menuId/prize_base/prize_ex/
prize_over/decMoney/adventure_plot/lock_value/strLimit/title/line/tiaozhan。

### 1.5 记录表列语义（task_define.lua accept_rec_* / task_rec_*）

**Task_Accepted（官方 schema 29，28 列）**：0 id / 1 line / 2 server /
3 submit_scene / 4 submit_npc / 5 title(文本key) / 6 status(0 进行,1 失败,
2 可提交) / 7 track_flag / 8 step / 9 time(i64) / 10 plot / 11 can_giveup(byte) /
12 target / 13 context / 14 accept_npc / 15 accept_scene / 16 submit_npc_ex /
17 prize_base / 18 prize_ex / 19 prize_over / 20 accept_limit / 21 submit_limit /
22 repute_limit / 23 vip(byte) / 24 sceneid / 25 prize_condition / 26·27 未用。

**Task_Record（30，15 列）**：0 id / 1 subtype(1 杀 2 采 3 交互 4 护送
5 quest **6 story** 7 用道具) / 2 index / 3 order / 4 curnum / 5 maxnum /
6 key / 7 scene / 8 trackinfo / 12 curnum_ex / 13 maxnum_ex / 14 hyper_link。

**Task_Completed（31，单 int32 位图）**：`serial=taskId+1`（一基）、
`row=serial/32`、`bit=serial%32`（0 回退上行 32）、`mask=1<<(bit-1)`。
增量更新必须**先删行再整刷**（行值 = 该行全部任务位合并，只发单任务
mask 会清掉同行其它位）；行 0..finalRow 含全零行都要发（starter 链口径）。

### 1.6 奖励串（23 段逗号，fill_prize 逆向）

[1] 钱(文) [2] 历练 Faculty [3,4] 门派声望名/数 [5,6] 江湖声望名/数
[7,8] 预留 [9] 可选奖励标记 [10] 随机奖励标记 [11..18] Item1,Num1..Item4,Num4
[19] 预留 [20,21] 生活槽类型/数 [22] 钱2 [23] 预留。全 "0" 串客户端不渲染
奖励区（is_have_prize）。→ `TaskCatalog.PrizeDef.renderPrizeString`

## 二、配置数据源（GBK 带引号 TSV）

| 表 | 路径（share 相对） | 表头 | 关键列 |
|---|---|---|---|
| 主任务 | task/task/task/task_*.txt | 2 行（类型/列名） | ID Line AcceptNpc SubmitNpc SceneID AutoSubmit PreTaskLimit TitleId ContextId TaskTargetId TrackInfoID CanGiveup TaskItemList |
| 奖励 | task/task/prize/prize_*.txt | 2 行 | Faculty NormalCapital ReputeName1/3 Item1..4 Num1..4 DropID |
| 步骤 | task/task/subfunction/subfunction_*.txt | 2 行 | ID Type(story/interact) TypeID Order |
| NPC 归属 | npc/npcconfig/**/*.txt | 3 行 | `table@TaskCanAccept` / `table@TaskCanSubmit`（值 `24721;24727;` 分号列表） |

实测规模：主表 ~15377 条（94 非空文件）、归属 NPC ~4500、对话选项
optiontalk_dialog_pack.ini 的 task 选项 target 即任务 ID（
`task|title_<id>|<id>|<接取对话>/<完成对话>`）。

## 三、服务端实现（com.nineyin.server.game.avatar.task）

| 类 | 职责 |
|---|---|
| `TaskCatalog` | 四源 TSV 加载 + ResourceCache 缓存；TaskDef/PrizeDef（含 23 段奖励串渲染）/Step/NpcTasks |
| `PlayerTask` | 已接表 + 已完成集状态机；accept（配置/前置 OR 语义/查重校验）/complete/giveup + Snapshot |
| `TaskStore` | data/tasks.json 主档 + role_tasks 快照表（MeridianStore 同模式）；DDL `admin/sql/0009` |
| `TaskMessages` | taskMsg 命令名帧/Task_Accepted 28 列行/Task_Record 行/位图公式 completedBit 与行同步帧 |

LoginFlow 集成点：

- **openNpcDialog**：optiontalk 未命中 → 任务专线路径 `sendTaskOnlyMenu`
  （TaskCanAccept 可接 + TaskCanSubmit 已接可交 + 告辞）；命中 →
  `sendDialogMenu` 追加可交任务项。
- **dispatchMenuMark**：`NpcDialog.AcceptTask/SubmitTask` mark 动作 →
  acceptTask/submitTask。
- **applyDialogOption** case "task"：optiontalk task 选项 → acceptTask。
- **接取**：Task_Accepted 28 列行 + story 步骤行 + 327 sub=9 + persist。
- **提交**：先算行号（starter 15 行前缀 + 已接升序序）→ 0x12 删行 +
  位图行同步（starter 位 ∪ 玩家位）+ 327 sub=8 + 奖励 + persist。
- **奖励**（grantTaskRewards）：历练→`PlayerProgress.addFaculty`、
  碎银→CapitalType1 同单位累加、物品→`insertBagItem`+`pushBagItemFrames`
  （GM grant_item 同通道）；收尾 vitalUpdate。
- **登录恢复**（grantTaskRestoreFrames，starter 链后）：已接行（starter
  族去重）+ 位图差异行（新玩家零帧，golden 帧序不变）。
- **dispatchCustom case 327**：sub 0 放弃 / "complete_task" 提交。

行号契约：客户端 Task_Accepted 记录行序 = starter 功能解锁 15 行
（`StarterQingGong.acceptedTaskIds`）+ 玩家已接升序。删行前必须按此口径
计算（`taskAcceptedRowNumber`）。

## 四、已知边界与后续期

- **PreTaskLimit OR 假设**：多前置任一完成即放行（单链配置无差异，
  多前置语义未从客户端条件系统确证）。
- **AutoSubmit / AutoAcceptID**：自动提交/承接链未实现（对话型任务玩家
  手动提交完全可用）。
- **杀怪/采集/护送进度**（huntlist/collectlist/convoy）与 Task_Record
  计数推进、EventNpc 放行、任务道具消耗（TaskItemList 扣还）、限时任务
  （TaskTimeLimitRec）留二期。
- **sub=1/2 任务窗**：组帧已备（`TaskMessages.showAcceptForm/showSubmitForm`），
  一期菜单流足够闭环；字符串路由帧（0x27 首值 tag6）无官方抓包实证，
  真机若不生效维持 SERVER_MENU 菜单流（功能等价）。
- 0x1C 菜单项 type 字节语义未证（统一 0，真机待验）。
