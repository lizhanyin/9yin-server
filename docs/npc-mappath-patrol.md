# NPC 官方巡逻路径（mappath 路线巡逻）

取代原 Go 复刻的占位巡逻（前 3 个非战斗 NPC 每 5s X±1.5 摆动），
按官方 `creator/mappath` 路线数据驱动 NPC 沿路点往返行走。对应
`bug.txt` V2 "添加NPC官方巡逻路径"。

## 数据链（本机已验证）

```
creator XML <item PathId="AI_sz_001">        ← 实例级绑定（优先）
npcconfig 模板列 PathID                      ← 模板级缺省
        ↓
share/creator/mappath/<场景短名>_npc.ini/_mst.ini/...   （GBK）
        [AI_sz_001]
        r=59,458.7,0.629,637.8,-10,0,stand,nil,walk,nil,1,
        ↓
NpcSpawn.route（就近吸附 ≤35m） → SceneLifecycle 步进链
```

场景短名 = creator 目录名去掉首个 `_` 后缀（`city02_suzhou` → `city02`），
加载该前缀的全部 `.ini`（stem 含 `offline` 的排除）。列序已用苏州 NPC
sz051（公振鹏）交叉验证：出生点 (459.139, 0.630, 637.788) ↔ 路线首点
(458.7, 0.629, 637.8)。

### 三种行格式（对齐尾部 `stand,nil,walk/run,nil,1` 固定列）

| 格式 | 样例 | x,y,z 位置 |
|---|---|---|
| 前导空列（主流，15.5 万行） | `r=,900.36,17.48,117.49,0,…` | field 1..3 |
| 整数导航点 id 前缀（约 1 万行） | `r=59,458.7,0.629,637.8,-10,…` | field 1..3（**跳过 id**） |
| 无 id 列（少量） | `r=318.19,3.79,632.01,-10,…` | field 0..2 |

> 教训：旧 Java 备份（bak/PatrolPaths.java）把格式 2 的 id 列当 x 读，
> 坐标整体错位——这正是"偶尔上天入地/穿墙"的根因。`coordinateBase()`
> 按"首列含小数点=坐标，否则为 id/空列"区分。

朝向列语义：`0`=未设、`-10`=哨兵 → 两者都按行进方向 `atan2(dx,dz)` 计算
朝向；`0<|o|≤2π` 才采用路点朝向。第 9 列 `walk/run` 决定该腿用
WalkSpeed 还是 RunSpeed。

## 绑定与步进策略

- **绑定**（`PatrolRoutes.bind`，目录缓存内一次完成）：路线 ≥2 点（相邻
  <0.5m 去重后）且出生点到最近路点 ≤35m → 就近吸附（改写 spawn 坐标与
  有效朝向，记录起始下标）；否则站立。`attacknpc`/`bossnpc` 不绑定
  （敌对巡逻与战斗互斥，留待敌对 AI 期，见 go-replica 文档三期项）。
- **步进**（`SceneLifecycle.patrolStep`）：端点弹回（往返）；单腿 XZ>28m
  或 |dy|>6m 或 <0.15m 的路点只推进不下发（脏数据防瞬移）；每腿发
  `0x20 ServerMoving`（复用 `moveNpc`：到点 `0x1F` 权威落位）；路点停留
  200ms。
- **速度同步**：服务端行程时间 = 距离 / 实体 WalkSpeed（walk 腿）或
  RunSpeed（run 腿），与 0x0D 下发给客户端的属性同值（实测模板列如
  TeamLeaderNpc walk=1.5/run=5.0），缺失回退 1.25/4.0，钳 0.5..15；
  行程钳 600..60000ms。速度不同步会造成客户端动画与服务端到点 0x1F
  橡皮筋。
- **首步 5s + 下标×300ms 错峰**：避开物化 0x1F 重放窗口
  （250/750/1500/3000/5000ms，`LOCATION_REPLAY_DELAYS_MILLIS`），
  防止重放帧把已走动的 NPC 拽回出生点。
- **视口重进**：物化后新注册的巡逻 NPC 立即开链（旧实现重进后巡逻
  永久停止）；链带代序号，旧链回调自灭不误伤新链。重进 NPC 从吸附
  起点重新起步（已知限制：不模拟离开期间的进度）。

## 真实数据干跑（9yin_server1/resources/modern/share）

| 场景 | 路线数 | 摆位 | 绑定巡逻 | 超 28m 腿（运行时跳过） |
|---|---|---|---|---|
| city02_suzhou 苏州 | 314 | 7134 | 189 | 762 |
| born03_YanYuZhuang 出生 | 385 | 5142 | 178 | 449 |
| city05_chengdu 成都 | 370 | 5355 | 221 | 258 |

## 代码索引

- `world/npc/PatrolRoute.java` 路点/路线（不可变，跨连接共享）
- `world/npc/PatrolRoutes.java` 加载（三格式/offline 排除/去重）+ 绑定吸附
- `world/npc/SceneNpcRegistry.java` `catalogFor` 内加载 mappath 并 bind
- `world/scene/SceneLifecycle.java` `startPatrol/startPatrolFor/patrolStep/nextLeg/patrolSpeed`
- 测试：`world/npc/PatrolRoutesTest.java`（格式回归/吸附/站立策略）、
  `game/scene/SceneLifecycleTest.java`（步进/弹回/跳点/视口重进）
