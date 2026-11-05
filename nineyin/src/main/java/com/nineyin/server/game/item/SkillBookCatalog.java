package com.nineyin.server.game.item;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 秘籍目录（msg31 使用链 script=SkillBook/SkillPage/SkillScroll 分支的数据源）：
 * tool_item.ini（GBK）内携带 {@code LearnSkillPack=<packId>} 的条目（三类 script 全量
 * 24917/6116/498 与 item_learn_skill_static 全对上），pack 查
 * share/item/item_learn_skill_static.ini（GBK）的 {@code [packId] TragetID}——
 * 目标技能 1..N 个，分隔符 ',' 与 ';' 混用（如大轻功书
 * {@code qinggong_20,..,qinggong_25;QG_JH_gj_001}）。TragetID 官方拼写即如此（非 TargetID）。
 *
 * <p>道具自带分层字段（官方修炼上限模型，rule/condition.ini 门槛同源）：
 * {@code SkillMaxLevel=N}=该书把目标修炼上限解锁到 N（第X页/入门第N篇=N，
 * 篇章汇总书=该篇顶层：入门 9/进阶 18/中阶 30/高阶 36…各家数值随表）；
 * SkillScroll 传承心法走 {@code AddLevel=1}/{@code AddMaxLevel=N}（直升 1 层+上限抬到 N）。
 *
 * <p>学习侧按 game_skills.skill_type 分发：wuxue/anqi→View40、neigong→View43、
 * qinggong→View46、zhenfa 暂未接。文件缺失/脏行容错为空目录（使用链按未知秘籍
 * 拒绝并记日志）。
 */
public final class SkillBookCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillBookCatalog.class);

    private static final Charset GBK = Charset.forName("GBK");

    /** 学习类 script（tool_item.ini 实证三类都带 LearnSkillPack 且全表可解析）。 */
    private static final List<String> LEARN_SCRIPTS = List.of("SkillBook", "SkillPage", "SkillScroll");

    /**
     * 秘籍条目：pack 指向 + 分层字段。skillMaxLevel/addMaxLevel ≤0 = 无该字段
     * （纯学会型书，如门派基础书/大轻功书）；useCondition=0 = 无门槛（入门篇/第一页）。
     */
    public record BookEntry(String pack, List<String> targets, int skillMaxLevel,
                            int addLevel, int addMaxLevel, int useCondition) {
    }

    /** configId（小写）→ 条目（load 收尾已回填 targets）。 */
    private final Map<String, BookEntry> entries = new HashMap<>();
    /** LearnSkillPack id → 目标技能列表（保持声明序）。 */
    private final Map<String, List<String>> targets = new HashMap<>();

    /** 解析 item 根（tool_item.ini 秘籍节 + item_learn_skill_static.ini 目标表）。 */
    public static SkillBookCatalog load(Path itemRoot) {
        SkillBookCatalog catalog = new SkillBookCatalog();
        catalog.loadBooks(itemRoot.resolve("tool_item.ini"));
        catalog.loadTargets(itemRoot.resolve("item_learn_skill_static.ini"));
        // 目标回填（pack → TragetID 列表）；无目标表行的书移除（使用链按未知拒绝）。
        catalog.entries.entrySet().removeIf(e ->
                catalog.targets.get(e.getValue().pack()) == null);
        Map<String, BookEntry> filled = new HashMap<>();
        for (Map.Entry<String, BookEntry> entry : catalog.entries.entrySet()) {
            BookEntry value = entry.getValue();
            filled.put(entry.getKey(), new BookEntry(value.pack(),
                    catalog.targets.get(value.pack()),
                    value.skillMaxLevel(), value.addLevel(), value.addMaxLevel(),
                    value.useCondition()));
        }
        catalog.entries.clear();
        catalog.entries.putAll(filled);
        log.info("skill book catalog ready: books={} packs={}",
                catalog.entries.size(), catalog.targets.size());
        return catalog;
    }

    /** 常驻缓存（键加前缀避免与同目录其他消费方互污）。 */
    public static SkillBookCatalog loadDefault() {
        Path itemRoot = ServerPaths.projectPath("resources", "modern", "share", "item");
        return ResourceCache.of("skill-book-catalog:" + itemRoot, () -> load(itemRoot));
    }

    private void loadBooks(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("skill book table unavailable ({}): not a regular file", ini);
            return;
        }
        // fields: [0]=script [1]=LearnSkillPack [2]=SkillMaxLevel [3]=AddLevel
        //         [4]=AddMaxLevel [5]=UseCondition（键到达序不定，逐节攒齐再收）
        Map<String, String[]> raw = new HashMap<>();
        ResourceCache.ini(ini, GBK, (section, key, value) -> {
            if (section.isEmpty() || section.startsWith("//") || section.startsWith(";")) {
                return;
            }
            int slot = switch (key.toLowerCase(Locale.ROOT)) {
                case "script" -> 0;
                case "learnskillpack" -> 1;
                case "skillmaxlevel" -> 2;
                case "addlevel" -> 3;
                case "addmaxlevel" -> 4;
                case "usecondition" -> 5;
                default -> -1;
            };
            if (slot < 0) {
                return;
            }
            String[] fields = raw.computeIfAbsent(
                    section.trim().toLowerCase(Locale.ROOT), s -> new String[6]);
            fields[slot] = value.trim();
        });
        for (Map.Entry<String, String[]> entry : raw.entrySet()) {
            String[] f = entry.getValue();
            if (f[0] == null || f[1] == null || f[1].isEmpty()
                    || LEARN_SCRIPTS.stream().noneMatch(s -> s.equalsIgnoreCase(f[0]))) {
                continue;
            }
            entries.put(entry.getKey(), new BookEntry(
                    f[1], List.of(),
                    parseInt(f[2]), parseInt(f[3]), parseInt(f[4]), parseInt(f[5])));
        }
    }

    private void loadTargets(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("item_learn_skill_static table unavailable ({}): not a regular file", ini);
            return;
        }
        ResourceCache.ini(ini, GBK, (section, key, value) -> {
            if (section.isEmpty() || section.startsWith("-")) {
                return; // [-1] 字段说明节
            }
            if (!key.equalsIgnoreCase("TragetID") || value.isBlank()) {
                return;
            }
            java.util.List<String> ids = new java.util.ArrayList<>(4);
            for (String token : value.replace(';', ',').split(",")) {
                String id = token.trim();
                if (!id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                targets.putIfAbsent(section.trim(), List.copyOf(ids));
            }
        });
    }

    /** 秘籍 configId → 条目（目标+分层字段）；非秘籍/无目标表行返回 null。 */
    public BookEntry resolve(String configId) {
        if (configId == null || configId.isEmpty()) {
            return null;
        }
        return entries.get(configId.trim().toLowerCase(Locale.ROOT));
    }

    /** script 是否学习类（msg31 分派用：三类都在 useItemEffect 之前拦截）。 */
    public static boolean isLearnScript(String script) {
        return script != null && LEARN_SCRIPTS.stream().anyMatch(s -> s.equalsIgnoreCase(script));
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public int bookCount() {
        return entries.size();
    }

    private SkillBookCatalog() {
    }
}
