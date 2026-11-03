-- 0008_add_keybind_operate_mode_20261104.sql
-- 操作模式持久化：role_keybinds.operate_mode（键位域同表扩展）。
-- 协议：C2S 99 CLIENT_CUSTOMMSG_SET_OPERATEMODE（custom_set_operate_mode(int)，
-- form_system_interface_setting.switch_mode → game_visual:CustomSend(99, mode)），
-- 0=3D（WASD）/1=2.5D（点击）。玩家属性 OperateMode（0x09 表索引 223，type3 int32）
-- 下发恢复——跨服重连 lua 直接 QueryProp("OperateMode")；不存则重登回落本地默认
-- 2.5D，与存档键位方案错配，表现为快捷键"保存了但异常"。
-- 幂等：information_schema 探测列存在才 ALTER（MySQL 8.0 无 ADD COLUMN IF NOT EXISTS）。

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_keybinds'
    AND COLUMN_NAME = 'operate_mode');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `role_keybinds` ADD COLUMN `operate_mode` tinyint NULL DEFAULT NULL AFTER `shortcut_key2`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
