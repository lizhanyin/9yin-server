package com.nineyin.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 道具使用目录（INI 数据驱动的回退层；scripts/item/&lt;configId&gt;.lua 优先）：
 * <ul>
 *   <li>礼包：share/item/giftbox.ini（项目自建 UTF-8 表，`[configId] DropID=...`）→ 开包；</li>
 *   <li>使用技能：share/item/item_use_skill_static.ini（GBK，`[itemType] SkillID/SkillLevel`）
 *       → 回血/回蓝阶梯（对应 game_items.item_type，如 2101 系药品 → yaopinhp1）。</li>
 * </ul>
 * 文件缺失/脏行容错为空条目（使用链按 unknown kind 拒绝并记日志）。
 */
public final class ItemUseCatalog {

    private static final Logger log = LoggerFactory.getLogger(ItemUseCatalog.class);

    public enum UseKind { BOX, USE_SKILL }

    /** 解析后的使用语义（kind + 对应参数）。 */
    public record UseEntry(UseKind kind, String dropId, String skillId, int skillLevel) {
    }

    private final Map<String, UseEntry> boxes = new HashMap<>();
    private final Map<Integer, UseEntry> useSkills = new HashMap<>();

    /** 解析 share 根（item/giftbox.ini + item/item_use_skill_static.ini）。 */
    public static ItemUseCatalog load(Path shareRoot) {
        ItemUseCatalog catalog = new ItemUseCatalog();
        catalog.loadGiftboxes(shareRoot.resolve("item").resolve("giftbox.ini"));
        catalog.loadUseSkills(shareRoot.resolve("item").resolve("item_use_skill_static.ini"));
        log.info("item use catalog ready: boxes={} useSkillTypes={}",
                catalog.boxes.size(), catalog.useSkills.size());
        return catalog;
    }

    private void loadGiftboxes(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("giftbox table unavailable ({}): not a regular file", ini);
            return;
        }
        Map<String, Map<String, String>> sections = parseSections(readText(ini));
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            String configId = e.getKey();
            if (configId.isEmpty() || configId.startsWith("-")) {
                continue;
            }
            String dropId = e.getValue().get("dropid");
            if (dropId == null || dropId.isBlank()) {
                continue;
            }
            boxes.put(configId, new UseEntry(UseKind.BOX, dropId.trim(), "", 0));
        }
    }

    private void loadUseSkills(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("item_use_skill table unavailable ({}): not a regular file", ini);
            return;
        }
        Map<String, Map<String, String>> sections = parseSections(readText(ini));
        for (Map.Entry<String, Map<String, String>> e : sections.entrySet()) {
            int itemType;
            try {
                itemType = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException ex) {
                continue;   // [-1] 字段映射表等非数字节
            }
            String skillId = e.getValue().get("skillid");
            int level = parseInt(e.getValue().get("skilllevel"), 1);
            if (skillId == null || skillId.isBlank()) {
                continue;
            }
            useSkills.put(itemType, new UseEntry(UseKind.USE_SKILL, "", skillId.trim(),
                    Math.max(1, Math.min(9, level))));
        }
    }

    /** 按 configId（礼包）→ itemType（使用技能）顺序解析；未知返回 null。 */
    public UseEntry resolve(String configId, int itemType) {
        UseEntry box = boxes.get(configId);
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

    // ---------- INI 解析辅助 ----------

    /** 分节解析 `[section]` + `Key=Value`（键统一小写；`//`、`;`、`#` 注释行跳过）。 */
    static Map<String, Map<String, String>> parseSections(String data) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String current = "";
        for (String raw : data.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith(";")
                    || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1).trim();
                out.computeIfAbsent(current, k -> new LinkedHashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.computeIfAbsent(current, k -> new LinkedHashMap<>())
                    .put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
        }
        return out;
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

    /** 编码探测读取：UTF-8 严格校验失败（share 表 GBK）则回退 GBK。 */
    static String readText(Path file) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GBK"));
        }
    }
}
