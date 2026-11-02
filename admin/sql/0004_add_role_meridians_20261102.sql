-- 0004_add_role_meridians_20261102.sql
-- 经脉修炼系统：新表 role_meridians（角色级快照，data/meridians.json 的 DB 双源）。
-- 结构对齐基线快照表 role_faculty（role_id PK + snapshot json + version + updated_at，
-- FK roles ON DELETE CASCADE）；MeridianStore.attachDatabase 里有同款
-- CREATE TABLE IF NOT EXISTS 运行期兜底——若服务端先于本脚本在裸库上建过表
-- （MEDIUMTEXT/无 FK 形态），本脚本幂等跳过，功能等价不受影响。

CREATE TABLE IF NOT EXISTS `role_meridians` (
  `role_id` bigint unsigned NOT NULL,
  `snapshot` json NOT NULL,
  `version` bigint unsigned NOT NULL DEFAULT '1',
  `updated_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`role_id`),
  CONSTRAINT `fk_meridians_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
