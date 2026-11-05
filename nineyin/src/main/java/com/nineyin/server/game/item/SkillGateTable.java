package com.nineyin.server.game.item;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 使用条件表（rule/condition.ini，GBK ~40k 节）——秘籍 UseCondition 官方门槛：
 * <ul>
 *   <li>Type=3 SkillContainer/NeiGongContainer Para2=MaxLevel min/max：目标技能
 *       当前<b>修炼上限</b>须落在 [min,max]（第X页=cap==X-1、篇章汇总=前篇上限~
 *       本篇前一层，即"由低到高逐层解锁"）；</li>
 *   <li>Type=1 School（Para1="==" min=school_* 编码）：门派限定；</li>
 *   <li>Type=19 Func / Type=4 等九阴志时代/功能态：本服未接时代系统，放行记日志。</li>
 * </ul>
 * 条件表缺席的 UseCondition（1,222 个）同样放行——单机服宁松勿锁。
 */
public final class SkillGateTable {

    private static final Logger log = LoggerFactory.getLogger(SkillGateTable.class);

    private static final Charset GBK = Charset.forName("GBK");

    /** 解析后的条件行（min/max 保留原文：Type=1 的 min 是 school_* 编码）。 */
    public record Gate(String type, String name, String para1, String para2,
                       String min, String max) {

        /** 修炼上限类条件（Type=3 *Container MaxLevel）：currentCap ∈ [min,max]。 */
        public boolean containerCapInRange(int currentCap) {
            Integer lo = parseInt(min);
            Integer hi = parseInt(max);
            return lo != null && currentCap >= lo && (hi == null || currentCap <= hi);
        }

        /** 门派条件期望编码（非 Type=1 返回 null）。 */
        public String schoolCode() {
            return "1".equals(type) ? min : null;
        }
    }

    private final Map<Integer, Gate> gates = new HashMap<>();

    /** 解析 share 根下的 rule/condition.ini（只收 Type/Name/Para1/Para2/min/max 六键）。 */
    public static SkillGateTable load(Path shareRoot) {
        SkillGateTable table = new SkillGateTable();
        table.loadTable(shareRoot.resolve(Path.of("rule", "condition.ini")));
        log.info("skill gate table ready: {} conditions", table.gates.size());
        return table;
    }

    /** 常驻缓存（键加前缀避免互污）。 */
    public static SkillGateTable loadDefault() {
        Path shareRoot = ServerPaths.projectPath("resources", "modern", "share");
        return ResourceCache.of("skill-gate-table:" + shareRoot, () -> load(shareRoot));
    }

    private void loadTable(Path ini) {
        if (!Files.isRegularFile(ini)) {
            log.warn("condition table unavailable ({}): not a regular file", ini);
            return;
        }
        ResourceCache.ini(ini, GBK, (section, key, value) -> {
            int id;
            try {
                id = Integer.parseInt(section.trim());
            } catch (NumberFormatException e) {
                return;
            }
            if (id <= 0) {
                return; // [-1] 字段说明节
            }
            Gate existing = gates.get(id);
            String type = existing == null ? "" : existing.type();
            String name = existing == null ? "" : existing.name();
            String para1 = existing == null ? "" : existing.para1();
            String para2 = existing == null ? "" : existing.para2();
            String min = existing == null ? "" : existing.min();
            String max = existing == null ? "" : existing.max();
            switch (key.toLowerCase(java.util.Locale.ROOT)) {
                case "type" -> type = value;
                case "name" -> name = value;
                case "para1" -> para1 = value;
                case "para2" -> para2 = value;
                case "min" -> min = value;
                case "max" -> max = value;
                default -> {
                    return;
                }
            }
            gates.put(id, new Gate(type, name, para1, para2, min, max));
        });
    }

    /** 条件 id → 行；缺席返回 null（调用方放行）。 */
    public Gate gate(int conditionId) {
        return gates.get(conditionId);
    }

    public int size() {
        return gates.size();
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private SkillGateTable() {
    }
}
