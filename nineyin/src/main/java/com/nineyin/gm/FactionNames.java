package com.nineyin.gm;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.nineyin.cache.ResourceCache;
import com.nineyin.game.ServerPaths;

/**
 * GM 面板门派显示名（同 {@link com.nineyin.protocol.SceneNames} 的取数方式）：
 * faction 编码（appearance[8]，如 force_yihua / school_wudang / newschool_huashan）→
 * ini/form_main_player.ini [school_name] 本地化键（ui_neigong_category_*）→
 * text/chineses/interface.idres 中文名（移花宫 / 武当 / 华山派…）。
 *
 * <p>表缺失或编码未收录时回退原编码（面板仍可读出编码本身）；空编码返回空串。
 * 全部表经 {@link ResourceCache} 按目录缓存，首次使用后不再读盘。
 */
public final class FactionNames {

    private static final Charset GBK = Charset.forName("GBK");

    private record Tables(Map<String, String> locKeyByCode, Map<String, String> nameByLocKey) {

        static Tables build(Path modernRoot) {
            Map<String, String> locKeyByCode = new HashMap<>();
            ResourceCache.ini(modernRoot.resolve("ini").resolve("form_main_player.ini"), GBK,
                    (section, key, value) -> {
                        // [school_name] 段含一个空键（=ui_task_school_null），跳过。
                        if ("school_name".equalsIgnoreCase(section) && !key.isEmpty()) {
                            locKeyByCode.put(key.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT));
                        }
                    });
            Map<String, String> nameByLocKey = new HashMap<>(ResourceCache.idresLowerCased(
                    modernRoot.resolve("text").resolve("chineses").resolve("interface.idres")));
            return new Tables(locKeyByCode, nameByLocKey);
        }
    }

    private FactionNames() {
    }

    private static Tables tables() {
        Path modernRoot = ServerPaths.projectPath("resources", "modern");
        return ResourceCache.of("gm-faction-names:" + modernRoot, () -> Tables.build(modernRoot));
    }

    /** 门派显示名；无映射回退原编码，空编码返回空串（面板显示 "-"）。 */
    public static String displayName(String faction) {
        if (faction == null || faction.isBlank()) {
            return "";
        }
        Tables tables = tables();
        String locKey = tables.locKeyByCode().get(faction.toLowerCase(Locale.ROOT));
        if (locKey == null || locKey.isBlank()) {
            return faction;
        }
        String name = tables.nameByLocKey().get(locKey);
        return name != null && !name.isBlank() ? name : faction;
    }
}
