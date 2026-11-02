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

## 五、遗留

- 240（打坐 SITCROSS）与 217/218 仍在 backlog（打坐有独立状态机，另行批次）。
- 多段伤害（varprop HitCount 分次结算，抓包 8.7 msg24/招）与攻击距离闸门为下一批
  （见对账清单缺口①②）。
