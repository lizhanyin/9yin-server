-- 0004：背包扩容包锚点表（数据驱动扩容，替代登录链写死的 4 个 ITEMBAG_006 对象）
-- 语义对齐 F 盘参考服 2026-10-31 00:32 抓包：
--   使用 = msg30 把 script=Bag 的物品拖进扩展页；每页 2 个锚点；
--   expires = 装备时刻 + tool_item.ini MaxHardiness 小时；同页同 config 再用 = 续期（+时长）
-- 幂等：CREATE TABLE IF NOT EXISTS + 种子仅对尚无任何锚点的存量角色执行

CREATE TABLE IF NOT EXISTS role_bagpacks (
  role_id BIGINT UNSIGNED NOT NULL,
  page TINYINT UNSIGNED NOT NULL COMMENT '页族 1道具 2装备 3材料 4任务（tool_item ViewID）',
  anchor TINYINT UNSIGNED NOT NULL COMMENT '锚点位 1/2（每页两个）',
  config_id VARCHAR(128) NOT NULL,
  boost INT NOT NULL COMMENT '该锚点扩容格数',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间；9999-12-31 为存量种子（永久）',
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (role_id, page, anchor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='背包扩容包锚点';

-- 存量角色种子：道具+装备两页各种 2 个 ITEMBAG_006（boost=36，即 19-54/55-90），
-- 与此前登录链写死行为一致，保证现有角色（含 19-90 格物品的）零回归
INSERT INTO role_bagpacks (role_id, page, anchor, config_id, boost, expires_at)
SELECT r.role_id, s.page, s.anchor, 'ITEMBAG_006', 36, '9999-12-31 00:00:00'
FROM roles r
JOIN (SELECT 1 AS page, 1 AS anchor
      UNION ALL SELECT 1, 2
      UNION ALL SELECT 2, 1
      UNION ALL SELECT 2, 2) s
WHERE NOT EXISTS (SELECT 1 FROM role_bagpacks b WHERE b.role_id = r.role_id);
