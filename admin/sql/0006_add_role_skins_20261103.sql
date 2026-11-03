-- 0006_add_role_skins_20261103.sql
-- 风物志皮肤系统（V3「风物志挂载」，风物志=装备的皮肤外观）：新表 role_skins
-- （角色级收集/挂载状态，data/skins.json 的 DB 双源）。结构对齐 0004
-- role_meridians 的快照表方言（role_id PK + version + updated_at，
-- FK roles ON DELETE CASCADE）；SkinStore.attachDatabase 里有同款
-- CREATE TABLE IF NOT EXISTS 运行期兜底。

CREATE TABLE IF NOT EXISTS `role_skins` (
  `role_id` bigint unsigned NOT NULL,
  `collected` json NOT NULL,
  `mounted` json NOT NULL,
  `version` bigint unsigned NOT NULL DEFAULT '1',
  `updated_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`role_id`),
  CONSTRAINT `fk_skins_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
