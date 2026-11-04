package com.nineyin.server.game.item;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 道具使用目录（INI 数据驱动的回退层；scripts/item/&lt;configId&gt;.lua 的 on_use 优先）：
 * <ul>
 *   <li>礼包：share/item/giftbox.ini（项目自建 UTF-8 表，{@code [configId] DropID=...}）→ 开包；</li>
 *   <li>tool_item.ini（GBK）内 {@code script=BoxItem} 且带 DropID 的条目 → 同上开包
 *      （原版 BoxItem 的 DropID 即包名，如 qy_chengdu_009）；</li>
 *   <li>{@code script=CompensateItem}（奇遇锦盒，如 Box_qycdu_009）→ 每个
 *       {@code @CompensateItemRec=<条件Id>,<ref>} 的 ref 各视作一个掉落包开一次。
 *       原版按条件（func-56 状态，如 18658/18659）门控单分支，本服未接入该状态，
 *       简化为全发（单机服口径，日志可追）；</li>
 *   <li>使用技能：share/item/item_use_skill_static.ini（GBK，{@code [itemType] SkillID/SkillLevel}）
 *       → 回血/回蓝阶梯（2101 系药品 → yaopinhp1）。</li>
 * </ul>
 * 文件缺失/脏行容错为空目录（使用链按 unknown kind 拒绝并记日志）。
 */
public final class ItemUseCatalog {

    private static final Logger log = LoggerFactory.getLogger(ItemUseCatalog.class);

    private static final Charset GBK = Charset.forName("GBK");

    public enum UseKind { BOX, USE_SKILL }

    /**
     * 解析后的使用语义：BOX 携带 1..N 个掉落包 id（各 roll 一次）；USE_SKILL 携带
     * 技能档位（yaopinhp* 无 varprop 数值，走 {@link #fallbackHealHp} 文档化阶梯）。
     */
    public record UseEntry(UseKind kind, List<String> dropIds, String skillId, int skillLevel) {
    }

    private final Map<String, UseEntry> boxes = new HashMap<>();
    private final Map<Integer, UseEntry> useSkills = new HashMap<>();

    /** 解析 share/item 根（giftbox.ini + tool_item.ini 的箱类 + item_use_skill_static.ini）。 */
    public static ItemUseCatalog load(Path itemRoot) {
        ItemUseCatalog catalog = new ItemUseCatalog();
        catalog.loadGiftboxes(itemRoot.resolve("giftbox.ini"));
        catalog.loadToolItemBoxes(itemRoot.resolve("tool_item.ini"));
        catalog.loadUseSkills(itemRoot.resolve("item_use_skill_static.ini"));
        log.info("item use catalog ready: boxes={} useSkillTypes={}",
                catalog.boxes.size(), catalog.useSkills.size());
        return catalog;
    }

    /** 常驻缓存（键加前缀避免与同目录其他消费方互污）。 */
    public static ItemUseCatalog loadDefault() {
        Path itemRoot = ServerPaths.projectPath("resources", "modern", "share", "item");
        return ResourceCache.of("item-use-catalog:" + itemRoot, () -> load(itemRoot));
    }

    private void loadGiftboxes(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("giftbox table unavailable ({}): not a regular file", ini);
            return;
        }
        ResourceCache.ini(ini, StandardCharsets.UTF_8, (section, key, value) -> {
            if (section.isEmpty() || section.startsWith("-") || section.startsWith("//")) {
                return;
            }
            if (!key.equalsIgnoreCase("DropID") || value.isBlank()) {
                return;
            }
            boxes.putIfAbsent(section.toLowerCase(Locale.ROOT),
                    new UseEntry(UseKind.BOX, List.of(value.trim()), "", 0));
        });
    }

    /** tool_item.ini 逐节收集 script/DropID/@CompensateItemRec 三键（其余键忽略）。 */
    private void loadToolItemBoxes(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("tool_item box table unavailable ({}): not a regular file", ini);
            return;
        }
        // fields: [0]=script [1]=DropID [2]=@CompensateItemRec refs（';' 追加，重复键逐条到达）
        Map<String, String[]> raw = new LinkedHashMap<>();
        ResourceCache.ini(ini, GBK, (section, key, value) -> {
            if (section.startsWith("//") || section.startsWith(";")) {
                return;
            }
            String k = key.toLowerCase(Locale.ROOT);
            if (!k.equals("script") && !k.equals("dropid") && !k.startsWith("@compensateitemrec")) {
                return;
            }
            String[] fields = raw.computeIfAbsent(section.toLowerCase(Locale.ROOT), s -> new String[3]);
            if (k.equals("script")) {
                fields[0] = value;
            } else if (k.equals("dropid")) {
                fields[1] = value;
            } else {
                int comma = value.indexOf(',');
                String ref = comma >= 0 ? value.substring(comma + 1).trim() : value.trim();
                fields[2] = fields[2] == null ? ref : fields[2] + ";" + ref;
            }
        });
        for (Map.Entry<String, String[]> e : raw.entrySet()) {
            String[] f = e.getValue();
            if (f[0] == null) {
                continue;
            }
            String script = f[0].trim();
            if (script.equalsIgnoreCase("BoxItem") && f[1] != null && !f[1].isBlank()) {
                boxes.putIfAbsent(e.getKey(), new UseEntry(UseKind.BOX, List.of(f[1].trim()), "", 0));
            } else if (script.equalsIgnoreCase("CompensateItem") && f[2] != null && !f[2].isBlank()) {
                Set<String> refs = new LinkedHashSet<>();
                for (String ref : f[2].split(";")) {
                    if (!ref.isBlank()) {
                        refs.add(ref.trim());
                    }
                }
                if (!refs.isEmpty()) {
                    boxes.putIfAbsent(e.getKey(), new UseEntry(UseKind.BOX, List.copyOf(refs), "", 0));
                }
            }
        }
    }

    private void loadUseSkills(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("item_use_skill table unavailable ({}): not a regular file", ini);
            return;
        }
        // fields: [0]=SkillID [1]=SkillLevel
        Map<Integer, String[]> raw = new LinkedHashMap<>();
        ResourceCache.ini(ini, GBK, (section, key, value) -> {
            int itemType;
            try {
                itemType = Integer.parseInt(section.trim());
            } catch (NumberFormatException ex) {
                return;   // [-1] 字段映射表等非数字节
            }
            String[] fields = raw.computeIfAbsent(itemType, s -> new String[2]);
            if (key.equalsIgnoreCase("SkillID")) {
                fields[0] = value;
            } else if (key.equalsIgnoreCase("SkillLevel")) {
                fields[1] = value;
            }
        });
        for (Map.Entry<Integer, String[]> e : raw.entrySet()) {
            String[] f = e.getValue();
            if (f[0] == null || f[0].isBlank()) {
                continue;
            }
            int level = Math.max(1, Math.min(9, parseInt(f[1], 1)));
            useSkills.putIfAbsent(e.getKey(), new UseEntry(UseKind.USE_SKILL, List.of(),
                    f[0].trim(), level));
        }
    }

    /** 按 configId（礼包/箱类）→ itemType（使用技能）顺序解析；未知返回 null。 */
    public UseEntry resolve(String configId, int itemType) {
        UseEntry box = configId == null || configId.isEmpty()
                ? null : boxes.get(configId.trim().toLowerCase(Locale.ROOT));
        if (box != null) {
            return box;
        }
        return useSkills.get(itemType);
    }

    public int boxCount() {
        return boxes.size();
    }

    public int useSkillTypeCount() {
        return useSkills.size();
    }

    /**
     * 使用技能回血阶梯兜底（skill_new/varprop 无 yaopinhp* 数值条目时的文档化默认；
     * 节奏对齐打坐 10 秒回满与药品分度描述，L1 轻伤 → L9 大还）。
     */
    public static int fallbackHealHp(int skillLevel) {
        return switch (Math.max(1, Math.min(9, skillLevel))) {
            case 1 -> 100;
            case 2 -> 200;
            case 3 -> 400;
            case 4 -> 600;
            case 5 -> 800;
            case 6 -> 1000;
            case 7 -> 1500;
            case 8 -> 2000;
            default -> 3000;
        };
    }

    /** 回蓝阶梯（约为回血一半）。 */
    public static int fallbackHealMp(int skillLevel) {
        return fallbackHealHp(skillLevel) / 2;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private ItemUseCatalog() {
    }
}
