package com.nineyin.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 控制效果目录（§3.25 数据考古钉死）：
 * buff_static.ini 的 StateType 列（列名表 [-1] 节 StateType=27）把 buff 分为
 * 十类控制（StateType 1..10，实测 187 个控制 buff），非 0 即控制效果。
 *
 * 免疫位段（buff_varprop 的 Immunity 属性设位）：
 * StateType=1（眩晕族）↔ 位 33..38——eqp_ImmStun 六级 varprop 行 110221-110226
 * 逐级设位 33-38 互证（§3.17 装备特效链）。其余九类的位段映射待后续钉死，
 * 未映射前 {@link #blocks} 对它们返回 false（不误伤）。
 */
public final class ControlCatalog {

    /** 十类控制里的眩晕族（eqp_ImmStun 直指）。 */
    public static final int STATE_STUN = 1;

    /** StateType=1 的免疫位段（33..38）。 */
    static final int[] STUN_IMMUNITY_BITS = {33, 34, 35, 36, 37, 38};

    private static final Logger log = LoggerFactory.getLogger(ControlCatalog.class);

    private final Map<Integer, Integer> stateTypeByStaticData = new HashMap<>();

    /** 装载 buff_static.ini 的 StateType 列（仅保留非 0 的控制 buff）。 */
    public void load(Path skillRoot) {
        for (Map<String, String> sec : AiRuleData.sections(skillRoot.resolve("buff_static.ini"))) {
            String id = sec.get("");
            if (id == null || id.isEmpty()) {
                continue;
            }
            String st = sec.get("StateType");
            if (st == null) {
                continue;
            }
            try {
                int sid = Integer.parseInt(id.strip());
                if (sid < 0) {
                    continue;   // [-1] 列名表头节
                }
                int stateType = Integer.parseInt(st.strip());
                if (stateType != 0) {
                    stateTypeByStaticData.put(sid, stateType);
                }
            } catch (NumberFormatException ignore) {
                // 表头/枚举节等非数值行跳过
            }
        }
        log.info("control catalog ready: control buffs={} (stateType 1..10)",
                stateTypeByStaticData.size());
    }

    /** buff_static 行号 → StateType（0 = 非控制 buff）。 */
    public int stateTypeOf(int staticData) {
        return stateTypeByStaticData.getOrDefault(staticData, 0);
    }

    public boolean isControl(int staticData) {
        return stateTypeOf(staticData) != 0;
    }

    public int size() {
        return stateTypeByStaticData.size();
    }

    /**
     * 免疫位集合是否屏蔽该控制。当前钉死 StateType=1 ↔ 位 33..38；
     * 其余控制类的位段未映射（返回 false，不误伤）。
     */
    public boolean blocks(int stateType, Set<Integer> immunityBits) {
        if (immunityBits.isEmpty() || stateType != STATE_STUN) {
            return false;
        }
        for (int bit : STUN_IMMUNITY_BITS) {
            if (immunityBits.contains(bit)) {
                return true;
            }
        }
        return false;
    }
}
