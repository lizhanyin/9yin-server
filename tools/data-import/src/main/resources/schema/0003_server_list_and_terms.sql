-- 0003：区服列表表（后续接入 4000 端口 svrlist 应答数据源）+ 账号同意用户须签字段
-- 幂等：server_list 用 CREATE TABLE IF NOT EXISTS；accounts 加列前先查
-- information_schema，已存在则跳过（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）

CREATE TABLE IF NOT EXISTS server_list (
  server_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL COMMENT '区服显示名，svrlist # 后的未转义原文',
  host VARCHAR(255) NOT NULL COMMENT '游戏网关地址',
  port INT UNSIGNED NOT NULL COMMENT '游戏网关端口',
  status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=可进入（svrlist 尾部标志实测为 0） 1=维护 2=隐藏',
  sort_order INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '列表展示顺序，小在前',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (server_id),
  UNIQUE KEY uq_server_list_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='区服列表';

-- 引导一行与当前硬编码行为一致的数据（表为空时才写入）
INSERT INTO server_list (name, host, port, status, sort_order)
SELECT '本地测试', '127.0.0.1', 19061, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM server_list);

-- accounts.agreed_terms：同意用户须知（MySQL 无原生 boolean，用 TINYINT(1)，0=未同意 1=已同意）
SET @has_agreed_terms = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'accounts'
    AND COLUMN_NAME = 'agreed_terms'
);
SET @ddl_agreed_terms = IF(@has_agreed_terms = 0,
  'ALTER TABLE accounts ADD COLUMN agreed_terms TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''同意用户须知：0=未同意 1=已同意'' AFTER status',
  'SELECT 1');
PREPARE stmt_agreed_terms FROM @ddl_agreed_terms;
EXECUTE stmt_agreed_terms;
DEALLOCATE PREPARE stmt_agreed_terms;
