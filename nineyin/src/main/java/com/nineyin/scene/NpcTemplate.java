package com.nineyin.scene;

/**
 * NPC 模板（npcconfig TSV 一行）。名字按 GBK 解码为 Java 字符串。
 * shopId/depotId/doorPackageId/playerTransferPosition/springRange 为服务端
 * 业务字段（门路由与商店/仓库菜单来源），模板缺省时由 creator 实例属性覆盖。
 */
public record NpcTemplate(String configId, String resource, String name,
                          String scriptClass, String actionSet, String weaponMode,
                          int level, int maxHp,
                          String shopId, String depotId,
                          String doorPackageId, String playerTransferPosition,
                          float springRange) {

    public static final NpcTemplate MISSING = new NpcTemplate("", "", "", "", "", "", 0, 0,
            "", "", "", "", 0f);
}
