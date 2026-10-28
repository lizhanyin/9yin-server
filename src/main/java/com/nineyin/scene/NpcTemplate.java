package com.nineyin.scene;

/** NPC 模板（npcconfig TSV 一行）。名字按 GBK 解码为 Java 字符串。 */
public record NpcTemplate(String configId, String resource, String name,
                          String scriptClass, String actionSet, String weaponMode,
                          int level, int maxHp) {

    public static final NpcTemplate MISSING = new NpcTemplate("", "", "", "", "", "", 0, 0);
}
