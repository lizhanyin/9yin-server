-- ====================================================================
-- 九阴服务端 data/ 目录数据 → MySQL 表结构设计稿
-- 依据：nineyin 库现有表规范（role_* 级联 FK / game_* 配置表 /
--       BIGINT UNSIGNED 主键 / TIMESTAMP(6) / utf8mb4_unicode_ci）
-- 来源：F:\Game\Online\9yin\9yin_server\data 下全部 JSON 数据文件
-- 注意：本文件为设计稿，未接入 schema_migrations 自动执行；
--       确认后可用 mysql 客户端手工执行，或拆分为带序号的 migration。
-- ====================================================================

-- --------------------------------------------------------------------
-- 一、角色数据表 role_*（外键 roles.role_id，ON DELETE CASCADE）
-- --------------------------------------------------------------------

-- vip.json：VIP 与金币/观音（每角色一行）
CREATE TABLE IF NOT EXISTS role_vip (
  role_id BIGINT UNSIGNED NOT NULL,
  gold BIGINT UNSIGNED NOT NULL DEFAULT 0,
  guan_yin BIGINT UNSIGNED NOT NULL DEFAULT 0,
  vip_valid_until BIGINT NOT NULL DEFAULT 0,          -- unix 秒
  vip_total_time BIGINT NOT NULL DEFAULT 0,
  vip_flag INT NOT NULL DEFAULT 0,
  vip_prize_time BIGINT NOT NULL DEFAULT 0,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_vip_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- mail.json：角色邮件（1:N）
CREATE TABLE IF NOT EXISTS role_mails (
  role_id BIGINT UNSIGNED NOT NULL,
  mail_serial VARCHAR(96) NOT NULL,                   -- 如 sys-2-1787904906863113800-0
  created_unix BIGINT NOT NULL,
  sender_id VARCHAR(128) NOT NULL DEFAULT '',
  title_id VARCHAR(128) NOT NULL DEFAULT '',
  content_id VARCHAR(128) NOT NULL DEFAULT '',
  is_read TINYINT UNSIGNED NOT NULL DEFAULT 0,
  attachment_config_id VARCHAR(128) NULL,
  attachment_amount INT UNSIGNED NULL,
  attachment_claimed TINYINT UNSIGNED NOT NULL DEFAULT 0, -- 扩展列：附件领取状态
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, mail_serial),
  KEY ix_role_mails_created (role_id, created_unix),
  CONSTRAINT fk_mails_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- lifejob.json：生活职业精力（每角色一行）
CREATE TABLE IF NOT EXISTS role_lifejob_state (
  role_id BIGINT UNSIGNED NOT NULL,
  ene INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_lifejob_state_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- lifejob.json：生活职业明细（jobs[]，job_id 如 sh_tj）
CREATE TABLE IF NOT EXISTS role_lifejob_jobs (
  role_id BIGINT UNSIGNED NOT NULL,
  job_id VARCHAR(32) NOT NULL,
  level INT NOT NULL DEFAULT 1,
  exp BIGINT NOT NULL DEFAULT 0,
  next_exp BIGINT NOT NULL DEFAULT 0,
  formulas INT NOT NULL DEFAULT 0,
  points INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, job_id),
  CONSTRAINT fk_lifejob_jobs_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- binglu.json：冰露/兵录 12 槽进度（每角色 12 行，index 0-11）
CREATE TABLE IF NOT EXISTS role_binglu (
  role_id BIGINT UNSIGNED NOT NULL,
  binglu_index TINYINT UNSIGNED NOT NULL,
  cur_level INT NOT NULL DEFAULT 0,
  fill_value BIGINT NOT NULL DEFAULT 0,
  max_level INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, binglu_index),
  CONSTRAINT fk_binglu_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- story_tasks.json：剧情任务（active/completed 合一）
CREATE TABLE IF NOT EXISTS role_story_tasks (
  role_id BIGINT UNSIGNED NOT NULL,
  task_id INT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',        -- active | completed
  step INT NOT NULL DEFAULT 1,
  counts JSON NULL,                                   -- 目标计数 {"2006":0,...}
  completed_unix BIGINT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, task_id),
  KEY ix_role_story_tasks_status (role_id, status),
  CONSTRAINT fk_story_tasks_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- homepoints.json：已开启的回城点
CREATE TABLE IF NOT EXISTS role_homepoints (
  role_id BIGINT UNSIGNED NOT NULL,
  point_id VARCHAR(64) NOT NULL,                      -- 如 HomePointcity05B
  point_type TINYINT UNSIGNED NOT NULL DEFAULT 0,     -- 0=城市 1=主城? 2=师门
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, point_id),
  CONSTRAINT fk_homepoints_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- homepoints.json：槽位上限与租借到期（每角色一行）
CREATE TABLE IF NOT EXISTS role_homepoint_state (
  role_id BIGINT UNSIGNED NOT NULL,
  home_point_count INT NOT NULL DEFAULT 0,
  guild_count INT NOT NULL DEFAULT 0,
  jianghu_count INT NOT NULL DEFAULT 0,
  school_count INT NOT NULL DEFAULT 0,
  hire_expires_unix BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_homepoint_state_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- homepoints.json：租借的回城点
CREATE TABLE IF NOT EXISTS role_homepoint_hires (
  role_id BIGINT UNSIGNED NOT NULL,
  point_id VARCHAR(64) NOT NULL,
  point_type TINYINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, point_id),
  CONSTRAINT fk_homepoint_hires_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- clone.json：禁地（副本）每日次数。scene_config='' 表示角色当日汇总
CREATE TABLE IF NOT EXISTS role_clone_usage (
  role_id BIGINT UNSIGNED NOT NULL,
  usage_date DATE NOT NULL,
  scene_config VARCHAR(128) NOT NULL DEFAULT '',      -- 如 ini\scene\clone003
  used JSON NOT NULL,                                 -- 4 元素数组 [0,0,0,1]
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, usage_date, scene_config),
  CONSTRAINT fk_clone_usage_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 武技数据.json：已学武技（learned + active 合一）
CREATE TABLE IF NOT EXISTS role_wuji_learned (
  role_id BIGINT UNSIGNED NOT NULL,
  art_id INT UNSIGNED NOT NULL,
  level INT NOT NULL DEFAULT 0,
  is_active TINYINT UNSIGNED NOT NULL DEFAULT 0,      -- active 集合
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, art_id),
  CONSTRAINT fk_wuji_learned_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 武技数据.json：分组解锁数（unlocked {"1":8,"2":24}）
CREATE TABLE IF NOT EXISTS role_wuji_unlocked (
  role_id BIGINT UNSIGNED NOT NULL,
  group_id TINYINT UNSIGNED NOT NULL,
  unlocked_count INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, group_id),
  CONSTRAINT fk_wuji_unlocked_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 武技数据.json：佩戴中的武技（worn {"1":[201,...],"2":[...]}）
CREATE TABLE IF NOT EXISTS role_wuji_worn (
  role_id BIGINT UNSIGNED NOT NULL,
  group_id TINYINT UNSIGNED NOT NULL,
  slot_index TINYINT UNSIGNED NOT NULL,
  art_id INT UNSIGNED NOT NULL DEFAULT 0,             -- 0=空槽
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, group_id, slot_index),
  CONSTRAINT fk_wuji_worn_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 新版服装数据.json：角色服装套装收藏
CREATE TABLE IF NOT EXISTS role_fashion_suits (
  role_id BIGINT UNSIGNED NOT NULL,
  suit_id VARCHAR(128) NOT NULL,                      -- 如 FASHION_SUIT_G027_gui
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, suit_id),
  CONSTRAINT fk_fashion_suits_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 称号数据.json：角色称号（owned + worn）
CREATE TABLE IF NOT EXISTS role_titles (
  role_id BIGINT UNSIGNED NOT NULL,
  title_id INT UNSIGNED NOT NULL,
  title_type SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  is_worn TINYINT UNSIGNED NOT NULL DEFAULT 0,
  obtained_unix BIGINT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, title_id),
  KEY ix_role_titles_worn (role_id, is_worn),
  CONSTRAINT fk_titles_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 梳妆阁数据.json：捏脸混合与展示（每角色一行）
CREATE TABLE IF NOT EXISTS role_dressing (
  role_id BIGINT UNSIGNED NOT NULL,
  blend JSON NOT NULL,                                -- {"Cloth":"Tab_b_cloth_...","Hat":...}
  show_type INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_dressing_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 玩家buff.json：角色 buff 快照
CREATE TABLE IF NOT EXISTS role_buffs (
  role_id BIGINT UNSIGNED NOT NULL,
  snapshot JSON NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_buffs_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- relive.json：复活锚点（每角色一行，anchor 展开为列）
CREATE TABLE IF NOT EXISTS role_relive (
  role_id BIGINT UNSIGNED NOT NULL,
  scene_config VARCHAR(255) NOT NULL DEFAULT '',
  scene_resource VARCHAR(64) NOT NULL DEFAULT '',
  pos_x FLOAT NOT NULL DEFAULT 0,
  pos_y FLOAT NOT NULL DEFAULT 0,
  pos_z FLOAT NOT NULL DEFAULT 0,
  orient FLOAT NOT NULL DEFAULT 0,
  ui_name VARCHAR(128) NOT NULL DEFAULT '',
  home_point_id VARCHAR(64) NOT NULL DEFAULT '',
  is_manual TINYINT UNSIGNED NOT NULL DEFAULT 0,
  affiliation VARCHAR(64) NOT NULL DEFAULT '',        -- 如 school_shaolin
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_relive_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 玩家属性存档.json：属性面板快照（支持保留多份历史）
CREATE TABLE IF NOT EXISTS role_stat_snapshots (
  role_id BIGINT UNSIGNED NOT NULL,
  archived_unix BIGINT NOT NULL,
  snapshot JSON NOT NULL,                             -- 含中文键（臂力/体魄/...）整体存 JSON
  PRIMARY KEY (role_id, archived_unix),
  CONSTRAINT fk_stat_snapshots_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 待机站姿.json：角色待机站姿（每角色一行）
CREATE TABLE IF NOT EXISTS role_stances (
  role_id BIGINT UNSIGNED NOT NULL,
  idle_stance VARCHAR(64) NOT NULL DEFAULT '',        -- 如 khbd_stand_01
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_stances_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- skills.json：角色技能修炼进度（JSON 里存的是全技能库 1.7 万条的静态副本；
-- 实际游戏内单角色可学技能上限 ≤3000、平均约 500，只导入角色真实技能集即可）
CREATE TABLE IF NOT EXISTS role_skill_progress (
  role_id BIGINT UNSIGNED NOT NULL,
  skill_config_id VARCHAR(128) NOT NULL,
  level INT NOT NULL DEFAULT 1,
  max_level INT NOT NULL DEFAULT 1,
  fill BIGINT NOT NULL DEFAULT 0,
  total BIGINT NOT NULL DEFAULT 0,
  extra JSON NULL,                                    -- pause_time/max_distance/cool_down_time/inner_force 等静态副本
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, skill_config_id),
  CONSTRAINT fk_skill_progress_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- origin/身份进度.json：身份系统进度（activated 的 id→参数，键空间大且稀疏，整体 JSON）
CREATE TABLE IF NOT EXISTS role_origin_progress (
  role_id BIGINT UNSIGNED NOT NULL,
  activated JSON NOT NULL,                            -- {"1":{},"10003":{},...}
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id),
  CONSTRAINT fk_origin_progress_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- repute/声望进度.json：各声望阵营数值
CREATE TABLE IF NOT EXISTS role_repute (
  role_id BIGINT UNSIGNED NOT NULL,
  repute_id VARCHAR(64) NOT NULL,                     -- 如 repute_shaolin
  repute_value BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, repute_id),
  CONSTRAINT fk_repute_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------------------
-- 二、游戏配置表 game_*（随 data/ 配置文件重建，无外键）
-- --------------------------------------------------------------------

-- NPC商店数据.json：商店主表（766 家）
CREATE TABLE IF NOT EXISTS game_shops (
  shop_id VARCHAR(64) NOT NULL,
  shop_name VARCHAR(128) NOT NULL DEFAULT '',
  PRIMARY KEY (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- NPC商店数据.json：货架明细
CREATE TABLE IF NOT EXISTS game_shop_items (
  shop_id VARCHAR(64) NOT NULL,
  seq INT UNSIGNED NOT NULL,
  config_id VARCHAR(128) NOT NULL,
  amount INT NOT NULL DEFAULT 1,
  capital_type TINYINT UNSIGNED NOT NULL DEFAULT 0,   -- 货币类型
  price INT NOT NULL DEFAULT 0,
  PRIMARY KEY (shop_id, seq),
  KEY ix_game_shop_items_config (config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- NPC商店数据.json：NPC → 商店绑定（1325 个 NPC）
CREATE TABLE IF NOT EXISTS game_npc_shops (
  npc_config_id VARCHAR(64) NOT NULL,                 -- 如 AbductNpc01
  npc_name VARCHAR(128) NOT NULL DEFAULT '',
  shop_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (npc_config_id),
  KEY ix_game_npc_shops_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- giftbox.json：礼包掉落表（322 个包）
CREATE TABLE IF NOT EXISTS game_gift_boxes (
  box_id VARCHAR(128) NOT NULL,
  seq INT UNSIGNED NOT NULL,
  item_id VARCHAR(128) NOT NULL,
  min_amount INT NOT NULL DEFAULT 1,
  max_amount INT NOT NULL DEFAULT 1,
  weight INT NOT NULL DEFAULT 0,
  PRIMARY KEY (box_id, seq),
  KEY ix_game_gift_boxes_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 道具配置.json：道具效果配置（rewards/apply_buff 等 JSON）
CREATE TABLE IF NOT EXISTS game_item_effects (
  config_id VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL DEFAULT '',
  heal_hp INT NULL,
  heal_mp INT NULL,
  consume TINYINT UNSIGNED NOT NULL DEFAULT 0,
  effects JSON NULL,                                  -- rewards[] / apply_buff 等
  PRIMARY KEY (config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 功能开关.json：功能开关（429 项）
CREATE TABLE IF NOT EXISTS game_feature_switches (
  switch_id INT UNSIGNED NOT NULL,
  desc_key VARCHAR(64) NOT NULL DEFAULT '',
  label VARCHAR(128) NOT NULL DEFAULT '',
  enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (switch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- card_models.json：风物志卡片配置（1144 张）
CREATE TABLE IF NOT EXISTS game_cards (
  card_id INT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL DEFAULT '',
  config_id INT UNSIGNED NOT NULL DEFAULT 0,
  item_type INT NOT NULL DEFAULT 0,
  level INT NOT NULL DEFAULT 0,
  main_type INT NOT NULL DEFAULT 0,
  sub_type INT NOT NULL DEFAULT 0,
  flag INT NOT NULL DEFAULT 0,
  female_model_data VARCHAR(255) NOT NULL DEFAULT '',
  male_model_data VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (card_id),
  KEY ix_game_cards_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 经脉.json：经脉配置（35 条，attrs/per_level 结构复杂存 JSON）
CREATE TABLE IF NOT EXISTS game_meridians (
  meridian_id VARCHAR(64) NOT NULL,                   -- 如 jm_shoushaoyin
  name VARCHAR(128) NOT NULL DEFAULT '',
  static_data INT NOT NULL DEFAULT 0,
  item_type INT NOT NULL DEFAULT 0,
  neigong VARCHAR(64) NOT NULL DEFAULT '',
  max_level INT NOT NULL DEFAULT 0,
  attrs JSON NULL,
  per_level JSON NULL,
  PRIMARY KEY (meridian_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 称号数据.json：称号目录（2308 个）
CREATE TABLE IF NOT EXISTS game_titles (
  title_id INT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL DEFAULT '',
  title_type SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (title_id),
  KEY ix_game_titles_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 身份装数据.json：身份套装配置（548 套，cloth/hat/pants/shoes/hair 部件存 JSON）
CREATE TABLE IF NOT EXISTS game_identity_suits (
  suit_id VARCHAR(128) NOT NULL,                      -- 如 cloth_nightwar_001
  name VARCHAR(128) NOT NULL DEFAULT '',
  qname VARCHAR(128) NOT NULL DEFAULT '',
  item_type INT NOT NULL DEFAULT 0,
  need_sex TINYINT UNSIGNED NOT NULL DEFAULT 0,
  color_level INT NOT NULL DEFAULT 0,
  condition_id INT NOT NULL DEFAULT 0,
  parts JSON NULL,                                    -- {"cloth":{"art":..,"male":..},"hat":null,...}
  PRIMARY KEY (suit_id),
  KEY ix_game_identity_suits_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- skill_hurt_reaction.json：技能位移受击覆写（当前 reactions/final_hit_only 均为空表，结构保留）
CREATE TABLE IF NOT EXISTS game_skill_reactions (
  skill_id VARCHAR(128) NOT NULL,
  reaction VARCHAR(16) NULL,                          -- up | down | back | normal
  final_hit_only TINYINT UNSIGNED NOT NULL DEFAULT 0, -- 仅最后一跳位移
  PRIMARY KEY (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 属性.json：人物属性默认值（热重载配置）
CREATE TABLE IF NOT EXISTS game_stat_defaults (
  stat_key VARCHAR(64) NOT NULL,                      -- 如 MaxHP / Str
  stat_value BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (stat_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- origin/origins.json：身份配置（1197 个身份）
CREATE TABLE IF NOT EXISTS game_origins (
  origin_id INT UNSIGNED NOT NULL,
  name VARCHAR(128) NOT NULL DEFAULT '',
  category VARCHAR(64) NOT NULL DEFAULT '',
  sub_category VARCHAR(64) NOT NULL DEFAULT '',
  type1 INT NOT NULL DEFAULT 0,
  type2 VARCHAR(64) NOT NULL DEFAULT '',
  level INT NOT NULL DEFAULT 0,
  material INT NOT NULL DEFAULT 0,
  body_type INT NOT NULL DEFAULT 0,
  line JSON NULL,                                     -- [1,2]
  conditions JSON NULL,                               -- activeCondIDs/activeCondText/getCondIDs/getCondText 等
  PRIMARY KEY (origin_id),
  KEY ix_game_origins_name (name),
  KEY ix_game_origins_type2 (type2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- origin/conditions.json：身份条件文本字典
CREATE TABLE IF NOT EXISTS game_origin_conditions (
  condition_id INT UNSIGNED NOT NULL,
  text VARCHAR(512) NOT NULL DEFAULT '',
  use_count INT NOT NULL DEFAULT 0,
  note VARCHAR(255) NOT NULL DEFAULT '',
  PRIMARY KEY (condition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ninjiuzhi_requirements.json：九阴志阶段要求覆写（features/tasks/npcs/scenes/inner_books）
CREATE TABLE IF NOT EXISTS game_ninjiuzhi_overrides (
  category VARCHAR(16) NOT NULL,                      -- features|tasks|npcs|scenes|inner_books
  item_key VARCHAR(128) NOT NULL,
  required_stage TINYINT UNSIGNED NOT NULL DEFAULT 1, -- 1..9
  PRIMARY KEY (category, item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- --------------------------------------------------------------------
-- 三、服务器全局表 server_*（跨角色运行时状态）
-- --------------------------------------------------------------------

-- 服务器散配置（gm default_level / ninjiuzhi initial_stage / 道具配置 defaults /
-- teams next_id / bots next_id 等）
CREATE TABLE IF NOT EXISTS server_config (
  config_key VARCHAR(128) NOT NULL,
  config_value JSON NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ninjiuzhi.json：九阴志全局进度（单行，id 恒为 1）
CREATE TABLE IF NOT EXISTS server_ninjiuzhi (
  id TINYINT UNSIGNED NOT NULL DEFAULT 1,
  stage TINYINT UNSIGNED NOT NULL DEFAULT 1,          -- 1..9
  active TINYINT UNSIGNED NOT NULL DEFAULT 0,
  points BIGINT NOT NULL DEFAULT 0,
  events JSON NULL,                                   -- {"kill":3,"task":2}
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- bots.json：NPC 机器人
CREATE TABLE IF NOT EXISTS server_bots (
  bot_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  bot_key VARCHAR(128) NOT NULL,                      -- 如 public-chengdu-companion-v1
  name VARCHAR(128) NOT NULL DEFAULT '',
  enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
  scene JSON NULL,                                    -- {config,resource,instance}
  position JSON NULL,                                 -- {X,Y,Z,Orient}
  equipment JSON NULL,
  appearance JSON NULL,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (bot_id),
  UNIQUE KEY uq_server_bots_key (bot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- dummy_players.json：场景假人（31925 条）
CREATE TABLE IF NOT EXISTS server_dummy_players (
  dummy_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL DEFAULT '',
  sex TINYINT UNSIGNED NOT NULL DEFAULT 0,
  school VARCHAR(64) NOT NULL DEFAULT '',
  level INT NOT NULL DEFAULT 0,
  power_level INT NOT NULL DEFAULT 0,
  character_type INT NOT NULL DEFAULT 0,             -- 源字段 character（保留字）
  scene VARCHAR(64) NOT NULL DEFAULT '',
  action VARCHAR(32) NOT NULL DEFAULT '',
  neigong_buff VARCHAR(64) NOT NULL DEFAULT '',
  position JSON NULL,
  appearance JSON NULL,
  PRIMARY KEY (dummy_id),
  KEY ix_server_dummy_players_scene (scene),
  KEY ix_server_dummy_players_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- teams.json：队伍（teams 当前为空，字段按 next_id=27 的历史结构从简）
CREATE TABLE IF NOT EXISTS teams (
  team_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  leader_role_id BIGINT UNSIGNED NULL,
  extra JSON NULL,                                    -- 预留：名称/场景/掉落分配等
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS team_members (
  team_id INT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  joined_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (team_id, role_id),
  UNIQUE KEY uq_team_members_role (role_id),          -- 一名角色同一时间只能在一个队
  CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams(team_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- gm权限.json：GM 权限（account/role 两级覆写；default_level 存 server_config）
CREATE TABLE IF NOT EXISTS gm_permissions (
  scope VARCHAR(8) NOT NULL,                          -- account | role
  subject_key VARCHAR(128) NOT NULL,                  -- account_key 哈希 或 role_id/角色名
  gm_level INT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (scope, subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
