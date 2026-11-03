# NPC 官方巡逻路径（mappath 路线巡逻）

取代原 Go 复刻的占位巡逻（前 3 个非战斗 NPC 每 5s X±1.5 摆动），
按官方 `creator/mappath` 路线数据驱动 NPC 沿路点往返行走。对应
`bug.txt` V2 "添加NPC官方巡逻路径"。

## 数据链（9yin_server1 数据已验证）

```
creator XML <item PathId="AI_sz_001">        ← 实例级绑定（优先）
npcconfig 模板列 PathID                      ← 模板级缺省
        ↓
share/creator/mappath/<场景短名>_npc.ini/_mst.ini/...   （GBK）
        [AI_sz_001]
        r=59,458.7,0.629,637.8,-10,0,stand,nil,walk,nil,1,
        ↓
PatrolRoutes.bind：就近吸附 ≤35m + 按速度属性构建 PatrolSchedule
        ↓
SceneLifecycle 时间表步进（0x20/0x1F）
```

场景短名 = creator 目录名去掉首个 `_` 后缀（`city02_suzhou` → `city02`），
加载该前缀的全部 `.ini`（stem 含 `offline` 的排除）。列序已用苏州 NPC
sz051（公振鹏）交叉验证：出生点 (459.139, 0.630, 637.788) ↔ 路线首点
(458.7, 0.629, 637.8)。

### 三种行格式（对齐尾部 `stand,nil,walk/run,nil,1` 固定列）

| 格式 | 样例 | x,y,z | 停留列 |
|---|---|---|---|
| 前导空列（主流，15.5 万行） | `r=,900.36,17.48,117.49,0,5000,…` | field 1..3 | field 5 |
| 整数导航点 id 前缀（约 1 万行） | `r=59,458.7,0.629,637.8,-10,60000,…` | field 1..3（**跳过 id**） | field 5 |
| 无 id 列（少量） | `r=318.19,3.79,632.01,-10,4000,…` | field 0..2 | field 4 |

> 教训：旧 Java 备份（bak/PatrolPaths.java）把格式 2 的 id 列当 x 读，
> 坐标整体错位——这正是"偶尔上天入地/穿墙"的根因。`coordinateBase()`
> 按"首列含小数点=坐标，否则为 id/空列"区分。

各列语义：
- **朝向列**：`0`=未设、`-10`=哨兵 → 两者按行进方向 `atan2(dx,dz)` 计算；
  `0<|o|≤2π` 才采用路点朝向
- **停留列**（毫秒）：到达该路点后的驻留，实测值 0/2000/4000/5000/15000/
  20000/30000/**60000**，钳 0..120s —— 路点停留由数据驱动（哨卫换岗等）
- **walk/run 标记**（field 8 固定列）：该腿到达点为 run 则用 RunSpeed
- 旧实现的 28m 跳腿上限已移除（正确解析后长腿是合法数据，走全程，
  行程钳 300..90000ms）；保留高度差 |dy|>6m 路点剔除（构建时一次完成）

## 时间表（PatrolSchedule）与多连接一致性

每条绑定在目录加载时展开成绝对时间线：段序 w0→…→w(n-1)→…→w0，
每段 = 行程(距离/速度) + 到达停留；周期 = 全部行程 + 停留。

- **共同时钟**：epoch = 绑定时刻（场景目录缓存加载），所有连接共享
  同一份 schedule + epoch → 同场景不同玩家看到**一致的 NPC 位置**
- **重进续走**：视口重进（120m 出入）时 `registerNpc` 按
  `positionAt(now)` 采样当前位置（行程中线性插值），不再回到路线起点
- **绝对时间步进**：每步按时间表时刻推算（剩余时长 = 段末 - now），
  调度抖动不累积漂移；SceneLifecycle 时钟可注入（测试确定性驱动）
- **速度同步**：行程 = 距离 / 该 NPC WalkSpeed（walk 腿）或 RunSpeed
  （run 腿），与 0x0D 下发属性同值（客户端动画据此播放），缺失回退
  1.25/4.0，钳 0.5..15 m/s —— 速度不同步会造成到点 0x1F 橡皮筋

## 绑定与步进策略

- **绑定**（`PatrolRoutes.bind`）：路线 ≥2 点（<0.5m 去重）且出生点到
  最近路点 ≤35m → 就近吸附；速度属性构建时间表（不可用则站立）。
  `attacknpc`/`bossnpc` 不绑定（敌对巡逻与战斗互斥，留待敌对 AI 期）
- **步进**：行进段发 `0x20 ServerMoving`（复用 `moveNpc`：到点 `0x1F`
  权威落位）；停留段只排程不发帧；首步 5s + 下标×300ms 错峰（避开
  物化 0x1F 重放窗口 250..5000ms）
- **视口重进**：物化后新注册的巡逻 NPC 立即开链；链带代序号，旧链
  回调自灭不误伤新链

## 真实数据干跑（9yin_server1/resources/modern/share）

| 场景 | 路线 | 摆位 | 绑定巡逻 | 带停留路线 | 周期 min/avg/max |
|---|---|---|---|---|---|
| city02_suzhou 苏州 | 314 | 7135 | 189 | 79 | 6.8s / 8.4min / 48min |
| born03_YanYuZhuang 出生 | 385 | 5149 | 176 | 21 | 0.6s / 2.7min / 16min |
| city05_chengdu 成都 | 370 | 5355 | 221 | 47 | 5.4s / 6.5min / 31min |

短周期（<5s）路线仅 born03 2 条，无帧风暴风险。

## 代码索引

- `world/npc/PatrolRoute.java` 路点/路线（dwellMs 朝向哨兵 walk/run）
- `world/npc/PatrolRoutes.java` 加载（三格式/offline 排除/去重）+ 绑定
  吸附 + 时间表构建（速度属性/共享 epoch）
- `world/npc/PatrolSchedule.java` 绝对时间线（行程+停留）、positionAt/
  actionAt 采样、dy 过滤、行程钳制
- `world/scene/SceneLifecycle.java` 时间表步进（注入时钟）、注册采样
  （重进续走）、`startPatrol/startPatrolFor/patrolStep`
- 测试：`world/npc/PatrolRoutesTest.java`（格式回归/停留解析/吸附/速度钳
  制）、`world/npc/PatrolScheduleTest.java`（时间线数学/回绕/run 腿/过滤）、
  `game/scene/SceneLifecycleTest.java`（步进/停留/弹回/重进续走）

## 已知限制

- 0x20 后 reconcile 立即跟一条目标点 0x1F（承自 Go 复刻 moveNPC 行为，
  无官服抓包佐证，未改动）
- 服务器重启后 epoch 重置，NPC 在路线上重新"随机"分布（各连接仍一致）
- 停留中的朝向沿用到达段行进方向（数据无站立朝向列）
