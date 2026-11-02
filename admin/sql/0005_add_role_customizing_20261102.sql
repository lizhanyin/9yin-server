-- 0005_add_role_customizing_20261102.sql
-- 个性配置（按键/操作习惯）持久化：新表 role_customizing。
-- 协议（52795 抓包钉死）：客户端引擎 customizing_manager 登录期上传 C2S 107
-- （[107, configStr]），服务端回发 S2C 705（on_parser_customizing）恢复配置对象；
-- 客户端本地不落盘，服务端不存则每次重置（bug.txt「个性设置没修」根因）。
-- 串为纯 ASCII+控制字节（0x01-0x65，UTF-8 无损），列用 VARBINARY(65535)；
-- CustomizingStore.attachDatabase 有同款 CREATE TABLE IF NOT EXISTS 运行期兜底。
-- 2026-11-03 修正：列名 customizing_value 对齐 F 数据同步的存量表结构
-- （3306/3307 实测均为 customizing_value 且无 version 列；早先用列名 config
-- 与存量表分叉，DB 读写全部静默失败退化为 JSON 单源）。

CREATE TABLE IF NOT EXISTS `role_customizing` (
  `role_id` bigint unsigned NOT NULL,
  `customizing_value` varbinary(65535) NOT NULL,
  `updated_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
