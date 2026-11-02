package com.nineyin.server.game.avatar.neigong;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

/**
 * 演武卡价格表（share/faculty/actfacultyinfo.ini）：按武学 configId + 当前层取卡基价。
 *
 * <p>ini 结构（F 盘客户端 res 同源，服务端 SERVER_ROOT resources/modern/share/faculty）：
 * <pre>[ng_wd_001]
 * r=1,11,10,40,130
 * r=31,11,530,1600,4810</pre>
 * 每行 {@code r=<level>,<band>,<card0>,<card1>,<card2>}——第一列是层号（1..maxLevel-1），
 * 卡 0/1/2 基价即客户端 form_wuxue_act 卡片下方的数值显示（GetBaseValue），
 * 悬浮提示成本 = 基价 × 价格系数（facultysale 促销，默认 1）。
 *
 * <p>单位口径：基价即文（最小货币单位，1 两 = 1000 文）——客户端成本提示
 * trans_capital_string(基价) 直接格式化、F 抓包 ActCostSuiYin=660 与低层卡价
 * 10/40/130 的整数和吻合（如 130×2+40×10）。额度账本（ActDayValue 族）同文刻度
 * （客户端可用显示 = (ActDayValue-ActCostSuiYin)/1000 两），入账按基价原值。
 */
public final class ActFacultyCatalog {

    /** configId -> (level -> [card0, card1, card2] 基价，两)。 */
    private final Map<String, Map<Integer, int[]>> prices = new HashMap<>();

    /** 目录级缓存（serverRoot 变化自动换表）；文件缺失 = 空表（cost 0，演武照常、只免扣费）。 */
    public static ActFacultyCatalog load() {
        return ResourceCache.of("act-faculty-catalog:" + ServerPaths.projectPath("resources", "modern", "share"),
                () -> loadShare(ServerPaths.projectPath("resources", "modern", "share")));
    }

    /** 整目录解析（测试注入临时 share 根用）。 */
    public static ActFacultyCatalog loadShare(Path shareRoot) {
        ActFacultyCatalog catalog = new ActFacultyCatalog();
        ResourceCache.ini(shareRoot.resolve(Path.of("faculty", "actfacultyinfo.ini")),
                StandardCharsets.ISO_8859_1, catalog::loadLine);
        return catalog;
    }

    private void loadLine(String section, String key, String value) {
        if (!key.equals("r")) {
            return;
        }
        String[] parts = value.split(",");
        // r=<level>,<band>,<card0>,<card1>,<card2>；结构不符行跳过（缺表环境保持空表）。
        if (parts.length < 5) {
            return;
        }
        try {
            int level = Integer.parseInt(parts[0].trim());
            int[] cards = {
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim())};
            prices.computeIfAbsent(section, id -> new HashMap<>()).put(level, cards);
        } catch (NumberFormatException ignored) {
            // 与 NeiGongCatalog 同策略：脏行 best-effort 跳过。
        }
    }

    /**
     * 卡基价（文）。缺表/缺层（超 maxLevel-1 的层）就近取最大已知层；card 越界 0。
     */
    public int cardBaseWen(String configId, int level, int card) {
        Map<Integer, int[]> levels = prices.get(configId);
        if (levels == null || card < 0 || card > 2) {
            return 0;
        }
        int best = -1;
        for (int known : levels.keySet()) {
            if (known >= best && known <= level) {
                best = known;
            }
        }
        // 请求层低于首层（如 0 层陈旧请求）取首层兜底。
        if (best < 0 && !levels.isEmpty()) {
            best = levels.keySet().stream().min(Integer::compare).orElse(-1);
        }
        return best < 0 ? 0 : levels.get(best)[card];
    }

    /** 已载入的武学条目数（测试/诊断）。 */
    public int size() {
        return prices.size();
    }

    private ActFacultyCatalog() {
    }
}
