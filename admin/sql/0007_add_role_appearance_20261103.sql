-- 0007_add_role_appearance_20261103.sql
-- 三套外观显示偏好（江湖装/身份装/时装）：新表 role_appearance 存玩家属性
-- ShowEquipType（0=江湖装 1=身份装 2=时装，custom 61 上行切换），
-- data/appearance.json 的 DB 双源。结构对齐 0006 role_skins 的快照表方言
-- （role_id PK + version + updated_at，FK roles ON DELETE CASCADE）；
-- AppearanceStore.attachDatabase 里有同款 CREATE TABLE IF NOT EXISTS 运行期兜底。

CREATE TABLE IF NOT EXISTS `role_appearance` (
  `role_id` bigint unsigned NOT NULL,
  `show_equip_type` tinyint unsigned NOT NULL DEFAULT '0',
  `version` bigint unsigned NOT NULL DEFAULT '1',
  `updated_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`role_id`),
  CONSTRAINT `fk_appearance_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
