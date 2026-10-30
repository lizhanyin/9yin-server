# 右键格挡（Manual Parry，Custom msg 54）协议分析与实现

> 数据来源：F 盘参考服 `F:\Game\Online\9yin\9yin_server1`（2026-10-30/31 抓包日志 + exe 逆向，
> IDA: `build\9yin-game-native-menu.exe`，函数名 `main.handleBlockStateCustom`）。

## 1. 客户端 → 服务器：CustomSend 0x0A / msgId 54

**消息 = 0x0A 信封，msgId=54，单个 int64 参数：`0` = 按下右键（engaged），`1` = 松开（released）。**

原始帧（37 字节，登录会话实抓）：

```
0a 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 e5 07 00 00 02 00 02  36 00 00 00 03 00 00 00   ← 按下（参数=0）
00 00 00 00 00
```

| 偏移 | 字段 | 说明 |
|---|---|---|
| 0x00 | `0A` | opcode（0x0A 信封，21 字节头） |
| 0x11 | `e5 07` | 帧序号 u16 LE，每帧 +1 |
| 0x15 | `02 00 02` | 值个数=2、类型 2（i32） |
| 0x18 | `36 00 00 00` | **msgId = 54**（i32） |
| 0x1C | `03` | 类型 3（int64） |
| 0x1D | 8 字节 | 参数：`0` 按下 / `1` 松开 |

### 行为特征（统计自 23:10–23:33 会话 357 包 + 备份会话 2390 包）

- **按下只发 1 个 `0`；松开连发 2 个（偶见 3 个）`1`**——重复包要求服务端按值幂等落态。
- 参考服有回帧确认时按下即停；**Java 侧未回帧时客户端每 ~500ms 重发 `0` 等确认**
  （01:05:54–57 实测连发 6 次），这就是"格挡不触发"的直接原因——客户端动画以服务端回包为闸。
- 持续格挡期间无格挡专属包。伴生的 msgId 924（每秒延迟探测）与 msgId 82（每 20 秒）与格挡无关。
- 218（主动招架，subtype+argument 两字段）是另一条链路，勿混。

## 2. 参考服处理（exe `handleBlockStateCustom` 逆向）

```
if 参数非整型  → error "block state value must be integral"
if engage 且 currentSkillState 非空（施法中）→ 忽略并日志 "block engage ignored during cast skill=%s"
if setPlayerBlock() 返回假（状态未变，即重复包）→ 直接吞掉，不回帧
状态变化时：
  ① 回 BuffInParry buff 事件（0x1E msg 169）
  ② 回格挡状态属性帧（0x10）
```

`setPlayerBlock`（带锁、去重）：

- engage：`buffs[11] = {StaticData: 4558, level: 1, 到期: now + 24h}`
- release：`delete(buffs, 11)`

### ① 0x1E msg 169（skillBufferFrameWithEnd → serverCustomIntMessage(169, …)）

TVarList 值序列（外层 `0x1E | u16 个数 | [tag 值]…`，首值为 i32 msgId）：

| # | 类型 | 值 | 说明 |
|---|---|---|---|
| 0 | i32 | `169` | msgId |
| 1 | i32 | `!parrying` | **end 标志**：按下=0（添加）、松开=1（结束） |
| 2 | string | `"BuffInParry"` | buff ID |
| 3 | object | `(玩家 oid, owner)` | 参考服常量 `(0x11000001, 0x7A5C0B)` |
| 4 | object | 同上 | |
| 5 | string | Actor.Snapshot 串 | 本服用 `actor.name` |
| 6 | string | 同上 | |
| 7 | i64 | `nowMs` | 时间戳 |
| 8 | i32 | `0` | |

### ② 0x10 多属性帧（sceneObjectProperties(玩家, 0, props)）

帧头：`10 00 | u32 oid | u32 owner | u16 属性数`，随后每属性 `u16 索引 + 值`
（**值不带类型字节**，线缆类型由 0x09 可见属性表决定；i32=4 字节，string=u32len+GBK+NUL）：

| 属性 | 参考服索引 | 本服（official_property_table.txt）索引 | 类型 | 值 |
|---|---|---|---|---|
| InParry | 471 | **470** | i32 | 1=格挡中 / 0 |
| InManualParry | 474 | **473** | i32 | 同上 |
| BufferInfo11 | 639 | **638** | string | 见下 |

> ⚠️ 参考新 exe 的 0x09 表比本服官服表在 1..470 间多一条，其后索引整体 +1。
> **0x10 帧索引必须与服务器自己下发的 0x09 表一致**，两边各自会话内自洽，不可直接抄 exe 数字。

`BufferInfo11`（仅 engage 有值，release 后为空串）格式（对齐 Go `bufferInfoLocked`）：

```
%X,%X,%X,%X,%X,%X  →  StaticData,OwnerID,OwnerSerial,Level,EndTimeMs,Count（全 16 进制）
BuffInParry 实例：  11CE,1,1,1,<到期毫秒 HEX>,1      （4558=0x11CE）
```

## 3. Java 实现要点（SceneFlow.handleManualParry，2026-10-31）

1. 参数按值幂等落态（0→engaged / 非 0→released）；**与当前状态相同直接 return**（对齐参考去重）。
2. engage 且处于技能锁（`skillLockUntilMs`）→ 忽略（对齐施法门禁）。
3. 状态变化时：`activateBuff(11, 4558, 24h, "BuffInParry", 1)` / `clearBuff(11, 4558)`，
   回 `CustomPackets.intMessage(169, …)` + `S2CPackets.objectProperties(…, 470, 473, 638)` 两帧。
4. 战斗侧沿用 `PlayerActor.parrying && parryEndurance > 0`（AiEventEngine 减伤）。

## 4. 待验证 / 后续

- [ ] 实机验证：重启 Java 服后按住右键——客户端应停止 500ms 重发、格挡动作与耐力条 UI 生效。
- [ ] 多人可见性：参考服回帧是否同时广播给同场景他人（单玩家会话无法观测）。
- [ ] 218 主动招架的 argument 语义（`active_parry.go` 已留钩子，含义待抓包）。
- [ ] 格挡被击的耐力扣减/破防（ParryValue 474/MaxParry 475 属性行为待抓包）。
