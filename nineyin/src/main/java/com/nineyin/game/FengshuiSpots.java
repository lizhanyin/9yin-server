package com.nineyin.game;

/**
 * 风水宝地（Fsbd_* 场景标记）→ 内修速度加成表。
 *
 * <p>宝地类型（commonnpc.xml 摆放 id {@code Fsbd_<type>_01}）：山 shan／林 lin／水 shui／洞 dong／雪 xue。
 * 武学属性（ui_wuxue_prop_*，{@code FacultyState.Book.wuXing}）：
 * 1刚 2柔 3阴 4阳 5太极 6阴柔 7阳刚。
 *
 * <p>加成数值表（新浪官方合作站 2012-01-31《武功在宝地加成速度百分比》）：
 * 最宜 +20%、较宜 +10%，不匹配无加成；「市」（城内摆摊区）对所有属性 -8%——
 * 市无 Fsbd 标记数据，本表暂不实现惩罚。
 *
 * <p>环境对真气几率/经脉聚气的另一层效果（ui_xl_*：山→火阳、林→木阳、水→水阴、
 * 洞→土阴阳、雪→金阴）属真气/经脉系统，待该系统接入时在此扩展。
 */
public final class FengshuiSpots {

    private FengshuiSpots() {
    }

    /** 标记触发半径（creator WakeupRange=12.0）。 */
    public static final float SPOT_RADIUS = 12f;

    /** configId → 宝地类型（"Fsbd_shan_01"→"shan"），非标记返回 null。 */
    public static String typeOf(String configId) {
        if (configId == null || configId.length() < 10) {
            return null;
        }
        String low = configId.toLowerCase(java.util.Locale.ROOT);
        if (!low.startsWith("fsbd_")) {
            return null;
        }
        int second = low.indexOf('_', 5);
        if (second <= 5) {
            return null;
        }
        String type = low.substring(5, second);
        return switch (type) {
            case "shan", "lin", "shui", "dong", "xue" -> type;
            default -> null;
        };
    }

    /** 宝地类型 × 武学属性 → 内修速度加成百分比（0/10/20）。 */
    public static int bonusPercent(String spotType, int wuXing) {
        if (spotType == null) {
            return 0;
        }
        return switch (spotType) {
            // 山：太极+20%；刚/柔/阴/阳+10%
            case "shan" -> wuXing == 5 ? 20 : (wuXing >= 1 && wuXing <= 4) ? 10 : 0;
            // 水：阴柔+20%；阴/柔+10%
            case "shui" -> wuXing == 6 ? 20 : (wuXing == 2 || wuXing == 3) ? 10 : 0;
            // 洞：刚/阴+20%；太极/阴柔/阳刚+10%
            case "dong" -> (wuXing == 1 || wuXing == 3) ? 20 : (wuXing >= 5 && wuXing <= 7) ? 10 : 0;
            // 林：阳刚+20%；刚/阳+10%
            case "lin" -> wuXing == 7 ? 20 : (wuXing == 1 || wuXing == 4) ? 10 : 0;
            // 雪：柔/阳+20%；太极/阴柔/阳刚+10%
            case "xue" -> (wuXing == 2 || wuXing == 4) ? 20 : (wuXing >= 5 && wuXing <= 7) ? 10 : 0;
            default -> 0;
        };
    }
}
