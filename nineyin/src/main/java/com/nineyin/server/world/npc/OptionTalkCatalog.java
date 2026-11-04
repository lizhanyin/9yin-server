package com.nineyin.server.world.npc;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * optiontalk 对话链目录（NPC 点击 → 0x1C 菜单的数据源），三个 GBK INI：
 *
 * <ul>
 *   <li>share/rule/optiontalk_npc.ini：节名 = creator XML 的 id 属性（= NpcSpawn
 *       的 configId，如 born04 千灯镇 WorldNpc00002 → DialogID=62）</li>
 *   <li>share/rule/optiontalk_dialog_pack.ini：对话节点 {@code MenuID/TitleID/
 *       NullTitleID/NextPage=63,64(可多值)/Option=描述,描述}，描述 = 9 槽
 *       {@code 类型|文本key|目标|条件|karma|…}（类型 task/shop/giveitem/trans…，
 *       文本 key 客户端 desc.idres 解析，如 biaoti00007=玩家台词）</li>
 *   <li>share/rule/optiontalk_operater_type.ini：类型名→官方枚举（task=1 shop=2
 *       trans=3 giveitem=10…，本期仅日志参考）</li>
 * </ul>
 *
 * <p>optiontalk_operate.ini（Menu→Operate 命名菜单）是另一套并行入口，其引用的
 * 商店号（如 Shop_00302）在 shop.ini 无对应节，本期不走该链。
 */
public final class OptionTalkCatalog {

    /** 对话选项（前四槽够路由，余槽 IsKarma/条件细节留任务系统期）。 */
    public record Option(String type, String textKey, String target, String condition) {

        /** giveitem 目标形如 Box_qyjl_gcd_009*1（itemId*数量）。 */
        public String itemId() {
            int star = target.indexOf('*');
            return star > 0 ? target.substring(0, star) : target;
        }

        public int itemCount() {
            int star = target.indexOf('*');
            try {
                return star > 0 ? Integer.parseInt(target.substring(star + 1)) : 1;
            } catch (NumberFormatException e) {
                return 1;
            }
        }
    }

    /** 对话节点：剧情链（仅 NextPage）或选项页（Option 列表）。 */
    public record Node(int id, String menuId, String titleId, String nullTitleId,
                       List<Integer> nextPages, List<Option> options) {
    }

    private static final Charset GBK = Charset.forName("GBK");

    private final Map<String, Integer> npcDialogs = new HashMap<>();
    private final Map<Integer, Node> nodes = new HashMap<>();
    private final Map<String, Integer> operateTypes = new HashMap<>();
    private Map<Integer, Builder> builders = new HashMap<>();

    public static OptionTalkCatalog load(Path ruleRoot) {
        OptionTalkCatalog catalog = new OptionTalkCatalog();
        ResourceCache.ini(ruleRoot.resolve("optiontalk_npc.ini"), GBK, (section, key, value) -> {
            if (key.equalsIgnoreCase("DialogID")) {
                try {
                    catalog.npcDialogs.put(section.toLowerCase(Locale.ROOT), Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    // 非数字 DialogID 忽略
                }
            }
        });
        ResourceCache.ini(ruleRoot.resolve("optiontalk_dialog_pack.ini"), GBK, (section, key, value) -> {
            int id;
            try {
                id = Integer.parseInt(section.trim());
            } catch (NumberFormatException e) {
                return; // 注释/异常节
            }
            catalog.builders.computeIfAbsent(id, Builder::new).accept(key, value);
        });
        for (Map.Entry<Integer, Builder> entry : catalog.builders.entrySet()) {
            catalog.nodes.put(entry.getKey(), entry.getValue().build());
        }
        catalog.builders = null;
        ResourceCache.ini(ruleRoot.resolve("optiontalk_operater_type.ini"), GBK, (section, key, value) -> {
            if (key.equalsIgnoreCase("type")) {
                try {
                    catalog.operateTypes.put(section.toLowerCase(Locale.ROOT), Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
        });
        return catalog;
    }

    /** 节点构建中间态（load 内部用）。 */
    private static final class Builder {
        private final int id;
        private String menuId = "";
        private String titleId = "";
        private String nullTitleId = "";
        private String nextPage = "";
        private final List<String> optionLines = new java.util.ArrayList<>();

        Builder(int id) {
            this.id = id;
        }

        void accept(String key, String value) {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "menuid" -> menuId = value;
                case "titleid" -> titleId = value;
                case "nulltitleid" -> nullTitleId = value;
                case "nextpage" -> nextPage = value;
                case "option" -> optionLines.add(value);
                default -> {
                }
            }
        }

        Node build() {
            List<Integer> nextPages = new java.util.ArrayList<>();
            for (String part : nextPage.split(",")) {
                try {
                    nextPages.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException e) {
                    // 空/非数字段跳过
                }
            }
            List<Option> options = new java.util.ArrayList<>();
            for (String line : optionLines) {
                for (String descriptor : line.split(",")) {
                    Option option = parseOption(descriptor);
                    if (option != null) {
                        options.add(option);
                    }
                }
            }
            return new Node(id, menuId, titleId, nullTitleId,
                    List.copyOf(nextPages), List.copyOf(options));
        }
    }

    /** `类型|文本key|目标|条件|…`（槽不足 4 按空补齐；类型空则非选项行）。 */
    private static Option parseOption(String descriptor) {
        String[] slots = descriptor.split("\\|", -1);
        if (slots.length < 2 || slots[0].trim().isEmpty()) {
            return null;
        }
        return new Option(slots[0].trim(), slot(slots, 1), slot(slots, 2), slot(slots, 3));
    }

    private static String slot(String[] slots, int index) {
        return index < slots.length ? slots[index].trim() : "";
    }

    /** 常驻缓存。 */
    public static OptionTalkCatalog loadDefault() {
        Path ruleRoot = ServerPaths.projectPath("resources", "modern", "share", "rule");
        return ResourceCache.of("option-talk-catalog:" + ruleRoot, () -> load(ruleRoot));
    }

    private OptionTalkCatalog() {
    }

    /** creator id（大小写不敏感）→ DialogID；无对话数据返回 null。 */
    public Integer dialogIdFor(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return null;
        }
        return npcDialogs.get(npcName.trim().toLowerCase(Locale.ROOT));
    }

    /** DialogID → 对话节点；未知返回 null。 */
    public Node node(int dialogId) {
        return nodes.get(dialogId);
    }

    /** 官方 operate 枚举（日志/文档参考；shop=2 task=1 …），未知 0。 */
    public int operateType(String type) {
        return operateTypes.getOrDefault(type == null ? "" : type.toLowerCase(Locale.ROOT), 0);
    }

    /** 对话节总数（测试口径）。 */
    public int nodeCount() {
        return nodes.size();
    }
}
