package com.nineyin.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Buff 目录：skill/buff_new.ini（buffId → StaticData 编号）。
 * StaticData 指向 buff_static.ini 的数值定义（VarProp 属性区间、Category 等），
 * 本切片只保留映射与存在性；属性加成/视觉帧后续接入。
 */
public final class BuffCatalog {

    private static final Logger log = LoggerFactory.getLogger(BuffCatalog.class);

    private final Map<String, Integer> staticData = new HashMap<>();

    public void load(Path skillRoot) {
        for (Map<String, String> sec : AiRuleData.sections(skillRoot.resolve("buff_new.ini"))) {
            String id = sec.get("");
            if (id == null || id.isEmpty()) {
                continue;
            }
            String sd = sec.getOrDefault("StaticData", "");
            try {
                staticData.put(id, Integer.valueOf(sd.trim()));
            } catch (NumberFormatException e) {
                staticData.put(id, 0);
            }
        }
        log.info("buff catalog loaded: {} buffs", staticData.size());
    }

    /** buffId → StaticData（0=未知）。 */
    public int staticDataOf(String buffId) {
        return staticData.getOrDefault(buffId, 0);
    }

    public int size() {
        return staticData.size();
    }
}
