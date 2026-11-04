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
 * share/trade/shop.ini 商店目录（View61 商品数据源；客户端不读 shop.ini，
 * 全靠 0x15/0x18 下发——protocol-notes 坑 24）。
 *
 * <p>GBK INI，商品行 = 数字键行（值 CSV：{@code itemId,Amount,?,价格,?,?,?,条件,?,,}，
 * 尾部空槽）。键不可信——shop_khd_ywb 节内八行全是 "0"（§12 坑），别的节又是
 * 0,1,2 顺排；一律按文件出现顺序收集（View61 行号 = 顺序位+1，与 F 服抓包一致）。
 * 与 F 服开商店抓包（shop_khd_ywb）对拍结论：
 *
 * <ul>
 *   <li>f0=itemId → View61 属性行 ConfigID(105)</li>
 *   <li>f1=Amount(106)</li>
 *   <li>f3=价格 → SellPrice2(109)（抓包 item_khd_001 f3=10000 = SellPrice2=10000；
 *       SellPrice0/1 恒 0）</li>
 *   <li>f2/f4/f5/f6/f7/f8 语义未证（绑定/标志/排序/条件族），不下发——少发优于错发</li>
 *   <li>MaxAmount(110)=物品自身堆叠上限（ToolItemCatalog 口径，抓包 99 为缺省），
 *       由下发方查表填入，非 shop.ini 字段</li>
 * </ul>
 *
 * <p>视图头字段：ShopID(101)=节名原串、PageNum(102)=10（抓包常数，商品行/页；
 * shopconfig.ini 的 PageNum=500 是另一口径）、PageCount(103)=PageInfo 项数、
 * ShopType(104)=0（抓包为 0，ini Type=1 不一致，以抓包为准）。
 */
public final class ShopCatalog {

    /** 商品行（index=节内顺序位 0 起；f0/f1/f3 三槽即下发所需）。 */
    public record GoodsRow(int index, String itemId, int amount, int sellPrice2) {
    }

    /** 商店节：goods 按文件出现顺序（View61 行号 = index+1）。 */
    public record ShopSection(String shopId, String nameId, int pageCount, int type,
                              List<GoodsRow> goods) {
    }

    private static final Charset GBK = Charset.forName("GBK");

    private final Map<String, SectionBuilder> builders = new HashMap<>();
    private final Map<String, ShopSection> sections = new HashMap<>();

    public static ShopCatalog load(Path shopFile) {
        ShopCatalog catalog = new ShopCatalog();
        ResourceCache.ini(shopFile, GBK, (section, key, value) -> {
            if (section.startsWith("//") || section.startsWith(";")) {
                return; // 注释节
            }
            SectionBuilder builder = catalog.builders.computeIfAbsent(
                    section.toLowerCase(Locale.ROOT), s -> catalog.new SectionBuilder(section));
            if (isRowKey(key)) {
                builder.row(value);
            } else {
                builder.header(key, value);
            }
        });
        for (Map.Entry<String, SectionBuilder> entry : catalog.builders.entrySet()) {
            catalog.sections.put(entry.getKey(), entry.getValue().build());
        }
        return catalog;
    }

    /** 纯数字键视为商品行（其余按节头字段）。 */
    private static boolean isRowKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 节构建中间态（load 内部用）。 */
    private final class SectionBuilder {
        private final String shopId;
        private String nameId = "";
        private String pageInfo = "";
        private int type;
        private final List<String[]> rows = new java.util.ArrayList<>();

        SectionBuilder(String shopId) {
            this.shopId = shopId;
        }

        void header(String key, String value) {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "nameid" -> nameId = value;
                case "pageinfo" -> pageInfo = value;
                case "type" -> type = parseInt(value, 0);
                default -> {
                }
            }
        }

        void row(String value) {
            rows.add(value.split(",", -1));
        }

        ShopSection build() {
            int pageCount = 0;
            for (String part : pageInfo.split(",")) {
                if (!part.trim().isEmpty()) {
                    pageCount++;
                }
            }
            List<GoodsRow> goods = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                String[] fields = rows.get(i);
                goods.add(new GoodsRow(i,
                        field(fields, 0),
                        parseInt(field(fields, 1), 1),
                        parseInt(field(fields, 3), 0)));
            }
            return new ShopSection(shopId, nameId, pageCount, type, List.copyOf(goods));
        }

        private static String field(String[] fields, int index) {
            return index < fields.length ? fields[index].trim() : "";
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** 常驻缓存。 */
    public static ShopCatalog loadDefault() {
        Path shopFile = ServerPaths.projectPath("resources", "modern", "share", "trade", "shop.ini");
        return ResourceCache.of("shop-catalog:" + shopFile, () -> load(shopFile));
    }

    private ShopCatalog() {
    }

    /** 商店号（大小写不敏感）→ 商店节；未知返回 null。 */
    public ShopSection section(String shopId) {
        if (shopId == null || shopId.isEmpty()) {
            return null;
        }
        return sections.get(shopId.trim().toLowerCase(Locale.ROOT));
    }

    /** 商店节总数（测试口径）。 */
    public int sectionCount() {
        return sections.size();
    }
}
