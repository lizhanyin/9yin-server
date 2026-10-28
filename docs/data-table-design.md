# data/ 目录数据 → MySQL 表设计

依据 `nineyin` 库现有表规范，为 `F:\Game\Online\9yin\9yin_server\data` 下全部 JSON 数据文件设计的表结构。
DDL 见同目录 [data-table-design.sql](data-table-design.sql)（设计稿，未接入 migration 自动执行）。

## 现有表已覆盖的数据（本设计不动）

| 数据文件 | 现有表 |
| --- | --- |
| roles.json（账号/角色/外观/位置/捏脸） | accounts, roles, role_appearances, role_locations, role_customizing |
| equip.json（装备+背包） | role_equip_items, role_bag_items |
| faculty.json（内功） | role_faculty |
| 经脉.json（roles 部分） | role_jingmai |
| 快捷键方案.json | role_keybinds |
| 快捷栏位按键.json | role_shortcuts |
| cards.json（风物志收集/佩戴） | role_fwz, role_fwz_suits |
| 技能/道具静态库 | game_skills, role_skills, game_items |
| 坐骑/轻功 | role_mounts, role_qinggong |

> 现有库共 19 张业务表（accounts/roles + 15 张 role_* + game_items/game_skills），全部 InnoDB、utf8mb4_unicode_ci。

## 新增表总览（45 张，分三组）

沿用现有命名规范（DDL 已在临时库验证全部建表成功）：

- **`role_*`**（23 张）——玩家数据，主键含 `role_id`，外键 `roles(role_id) ON DELETE CASCADE`；
- **`game_*`**（15 张）——配置数据，随 data/ 配置文件整体重建，无外键；
- **`server_*` / 全局**（7 张）——跨角色运行时状态。

### 一、角色数据表 role_*

| 数据文件 | 表 | 说明 |
| --- | --- | --- |
| vip.json | role_vip | VIP/金币/观音，每角色一行 |
| mail.json | role_mails | 邮件；`(role_id, mail_serial)` 主键；附件拆两列 + 领取状态扩展列 |
| lifejob.json | role_lifejob_state + role_lifejob_jobs | 精力单行表 + 职业明细行 |
| binglu.json | role_binglu | 12 槽进度，每角色固定 12 行 |
| story_tasks.json | role_story_tasks | active/completed 合一，用 status 列区分；目标计数存 JSON |
| homepoints.json | role_homepoints + role_homepoint_state + role_homepoint_hires | 已开启点 / 槽位上限+租借到期 / 租借点三张 |
| clone.json | role_clone_usage | 按日期+场景记禁地次数；`scene_config=''` 表示当日汇总 |
| 武技数据.json | role_wuji_learned + role_wuji_unlocked + role_wuji_worn | learned 与 active 合一（is_active 列）；unlocked/worn 按分组 |
| 新版服装数据.json | role_fashion_suits | 套装收藏 `(role_id, suit_id)` |
| 称号数据.json（roles） | role_titles | owned+worn 合一（is_worn 列） |
| 梳妆阁数据.json | role_dressing | blend 键空间开放（含 16 进制哈希键），整体 JSON |
| 玩家buff.json | role_buffs | 快照 JSON |
| relive.json | role_relive | anchor 展开为结构化列（场景/坐标/UI名/回城点） |
| 玩家属性存档.json | role_stat_snapshots | 支持多份历史快照，中文属性键整体 JSON |
| 待机站姿.json | role_stances | 每角色一行 |
| skills.json（roles） | role_skill_progress | 修炼进度；JSON 存的是全技能库 1.7 万条静态副本，实际单角色可学上限 ≤3000、平均约 500，只导入角色真实技能集 |
| origin/身份进度.json | role_origin_progress | activated 键空间大且稀疏（千级身份），整体 JSON |
| repute/声望进度.json | role_repute | 按声望阵营一行 |

### 二、配置表 game_*

| 数据文件 | 表 | 说明 |
| --- | --- | --- |
| NPC商店数据.json | game_shops + game_shop_items + game_npc_shops | 766 商店 / 货架 / 1325 NPC 绑定 |
| giftbox.json | game_gift_boxes | 322 个掉落包，weight 保留兼容 |
| 道具配置.json | game_item_effects | heal/consume 拆列，rewards/apply_buff 存 JSON；defaults 进 server_config |
| 功能开关.json | game_feature_switches | 429 项开关 |
| card_models.json | game_cards | 1144 张风物志卡配置 |
| 经脉.json（meridians） | game_meridians | attrs/per_level 结构复杂，存 JSON |
| 称号数据.json（catalog） | game_titles | 2308 个称号目录 |
| 身份装数据.json | game_identity_suits | 548 套；cloth/hat/pants/shoes/hair 部件存 parts JSON |
| skill_hurt_reaction.json | game_skill_reactions | 当前两集合均为空，结构保留备用 |
| 属性.json | game_stat_defaults | 热重载默认值键值表 |
| origin/origins.json | game_origins | 1197 个身份；主字段拆列、条件组存 JSON |
| origin/conditions.json | game_origin_conditions | 条件文本字典 |
| ninjiuzhi_requirements.json | game_ninjiuzhi_overrides | 五类覆写合一张表（category 列区分） |

### 三、服务器全局表

| 数据文件 | 表 | 说明 |
| --- | --- | --- |
| （散键值） | server_config | gm default_level、ninjiuzhi initial_stage、道具 defaults、teams/bots next_id 等 |
| ninjiuzhi.json | server_ninjiuzhi | 全局单行（stage/active/points/events） |
| bots.json | server_bots | bot_key 唯一；scene/position/equipment/appearance 存 JSON |
| dummy_players.json | server_dummy_players | 31925 条；position/appearance 存 JSON，建 scene/name 索引；重建方式往后处理（见下） |
| teams.json | teams + team_members | 队伍当前为空，结构从简；成员表对 role_id 加唯一约束（一人一队） |
| gm权限.json | gm_permissions | account/role 两级覆写合一张（scope 列）；JSON 中 roles 键混有 role_id 与角色名，subject_key 用 VARCHAR 兼容 |

## 设计取舍

1. **结构化 vs JSON 快照**：需要按列查询/聚合的（VIP、邮件、任务、商店、次数）拆列；键空间开放或纯嵌套的（梳妆阁 blend、身份进度、部件配置）整体存 JSON。与现有库 role_currency/role_faculty 的快照风格一致。
2. **时间**：沿用 `TIMESTAMP(6)` + `updated_at`；源数据里的 unix 秒保持 BIGINT 不转 datetime（`*_unix` 列名），避免迁移时做时区换算。
3. **级联删除**：所有 role_* 外键 `ON DELETE CASCADE`，与现有 15 张 role_* 表一致。
4. **扩展列**：邮件附件领取状态（attachment_claimed）是源数据没有、运营需要的新列，参照 roles.role_ext 的"扩展列"先例。
5. **数据量**：`skills.json` 每角色存的是全技能库（约 1.76 万条）的静态副本，并非角色真实技能量——按游戏规则单角色可学上限 ≤3000、平均约 500，`role_skill_progress` 只导入角色真实技能集（level>0 或可学集合内），表规模可控。`server_dummy_players`（3.2 万条）为纯配置镜像，无外键，重建 = TRUNCATE + 从 JSON 批量重导；具体重建方案（DB 为真源反向导出 JSON、还是保留 JSON 为真源定期同步）**往后处理**。
6. **主键选择**：配置表沿用 game_items/game_skills 的 VARCHAR 业务主键习惯；数值 ID 类（称号/卡片/身份）直接用源数据 INT 主键。

## 落地方式（待定，确认后执行）

- 方案一：作为 `src/main/resources/nineyin/db/` 下新的带序号 migration（如 `0016_data_tables.sql`），由服务端启动时自动建表；
- 方案二：DBA 手工执行 `docs/data-table-design.sql`，应用侧另行接入读写代码。
