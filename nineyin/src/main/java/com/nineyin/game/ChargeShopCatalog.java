package com.nineyin.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 充值商城货品表（ini/charge_shop_goods.ini，GBK；参考服自建表）。
 * 参考服证据串：`charge shop catalog ready items=%d file=%s`、
 * `charge shop catalog unavailable (%s): %v`——
 * [Goods] 节每行一个货品，key = 商城 ChargeID（购买协议回传用），value 9 个逗号字段：
 * ConfigID, category, sex, priceType, price, saleType, discount, quotaTotal, quotaAccount。
 * priceType=0 为金币计价，其余参考服直接拒绝（`price_type=%d not supported`）。
 */
public final class ChargeShopCatalog {

    /** 单件购买数量上限（参考服 `buy nums=%d outside 1..%d` 的上界未从字符串钉死，先取 99）。 */
    public static final int MAX_NUMS = 99;

    public record Goods(int chargeId, String configId, String category, int sex,
                        int priceType, long price, int saleType, int discount,
                        int quotaTotal, int quotaAccount) {
    }

    private static final Logger log = LoggerFactory.getLogger(ChargeShopCatalog.class);

    private final Map<Integer, Goods> goods = new HashMap<>();

    /** 解析货品表；文件缺失/不可读保持空目录（购买链按 `no goods charge_id` 拒绝）。 */
    public static ChargeShopCatalog load(Path ini) {
        ChargeShopCatalog catalog = new ChargeShopCatalog();
        if (!Files.isRegularFile(ini)) {
            log.warn("charge shop catalog unavailable ({}): not a regular file", ini);
            return catalog;
        }
        try {
            String data = new String(Files.readAllBytes(ini), Charset.forName("GBK"));
            for (String raw : data.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith(";")
                        || line.startsWith("#") || line.startsWith("[")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String[] cols = line.substring(eq + 1).split(",", -1);
                if (cols.length < 5) {
                    continue;
                }
                try {
                    int chargeId = Integer.parseInt(line.substring(0, eq).trim());
                    catalog.goods.put(chargeId, new Goods(chargeId,
                            cols[0].trim(), cols[1].trim(), parse(cols[2]), parse(cols[3]),
                            parse(cols[4]), parse(cols[5]), parse(cols[6]),
                            parse(cols[7]), parse(cols[8])));
                } catch (NumberFormatException e) {
                    // 脏行跳过（对齐参考服逐行容错）
                }
            }
            log.info("charge shop catalog ready items={} file={}", catalog.goods.size(), ini);
        } catch (IOException e) {
            log.warn("charge shop catalog unavailable ({}): {}", ini, e.getMessage());
        }
        return catalog;
    }

    public Goods byChargeId(int chargeId) {
        return goods.get(chargeId);
    }

    public int size() {
        return goods.size();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
