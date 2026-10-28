package com.nineyin.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店配置加载（trade/shop.ini，GBK）：
 * [Shop_id] 节 = NameID / PageInfo / Type / Condition + 编号行（N=物品,页,槽位,...）。
 * 编号行键有重复（同键多行），按出现顺序保留。
 * 购买协议（msg 64/70/71）的 S2C 响应格式待官服抓包钉死后接入。
 */
public final class ShopRepository {

    public record ShopItem(String shopId, int rowKey, List<String> columns) {
    }

    public record Shop(String id, String nameId, String pageInfo, int type, String condition,
                       List<ShopItem> items) {
    }

    private static final Logger log = LoggerFactory.getLogger(ShopRepository.class);

    private final Map<String, Shop> shops = new LinkedHashMap<>();

    public void load(Path shopIni) {
        if (!Files.isRegularFile(shopIni)) {
            log.warn("shop.ini missing: {}", shopIni);
            return;
        }
        try {
            String data = new String(Files.readAllBytes(shopIni), Charset.forName("GBK"));
            String section = "";
            String nameId = "";
            String pageInfo = "";
            int type = 0;
            String condition = "";
            List<ShopItem> items = new ArrayList<>();
            for (String raw : data.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.charAt(0) == ';' || line.charAt(0) == '#') {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    if (!section.isEmpty()) {
                        shops.put(section, new Shop(section, nameId, pageInfo, type, condition, items));
                    }
                    section = line.substring(1, line.length() - 1);
                    nameId = "";
                    pageInfo = "";
                    type = 0;
                    condition = "";
                    items = new ArrayList<>();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0 || section.isEmpty()) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1);
                if (key.chars().allMatch(Character::isDigit) && !key.isEmpty()) {
                    // 编号行：物品,页,槽位,价格,?,?,?,名称ID,?,,
                    List<String> cols = new ArrayList<>();
                    for (String col : value.split(",", -1)) {
                        cols.add(col.trim());
                    }
                    items.add(new ShopItem(section, Integer.parseInt(key), cols));
                } else {
                    switch (key) {
                        case "NameID" -> nameId = value;
                        case "PageInfo" -> pageInfo = value;
                        case "Type" -> type = parseIntOr(value, 0);
                        case "Condition" -> condition = value;
                        default -> {
                        }
                    }
                }
            }
            if (!section.isEmpty()) {
                shops.put(section, new Shop(section, nameId, pageInfo, type, condition, items));
            }
            log.info("shops loaded={} items={} from {}", shops.size(),
                    shops.values().stream().mapToInt(s -> s.items().size()).sum(), shopIni);
        } catch (IOException e) {
            log.error("shop.ini read failed: {}", shopIni, e);
        }
    }

    public Shop get(String shopId) {
        return shops.get(shopId);
    }

    public int size() {
        return shops.size();
    }

    private static int parseIntOr(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
