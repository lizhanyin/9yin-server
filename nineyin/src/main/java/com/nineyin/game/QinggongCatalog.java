package com.nineyin.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 轻功目录（share/skill/qinggong/qgdefine.ini，GBK）：
 * 节名 = 轻功 ConfigID，键 Type/UseType/Consume/BufferID。
 * 216 计费用 Consume；水上漂/踏水跳按 BufferID 映射原生 Buff 槽。
 */
public final class QinggongCatalog {

    private static final Logger log = LoggerFactory.getLogger(QinggongCatalog.class);

    public record Definition(String id, int type, int useType, int consume, String bufferId) {
    }

    /** BufferID → Buff 槽规格（Go qinggongBuffFor）。 */
    public record BuffSpec(int slot, int staticData, long durationMs, boolean clearOnEnd) {
    }

    private static final Map<String, BuffSpec> BUFF_BY_BUFFER_ID = Map.of(
            "buf_qg_drift", new BuffSpec(PlayerActor.BUFF_SLOT_WATER_DRIFT,
                    PlayerActor.WATER_DRIFT_STATIC, 10 * 60 * 1000L, true),
            "buf_qg_dst", new BuffSpec(PlayerActor.BUFF_SLOT_WATER_JUMP,
                    PlayerActor.WATER_JUMP_STATIC, 3000L, false));

    private final Map<String, Definition> definitions = new HashMap<>();

    public void load(Path qgdefineIni) {
        if (!Files.isRegularFile(qgdefineIni)) {
            log.warn("qgdefine.ini missing: {}", qgdefineIni);
            return;
        }
        try {
            String data = new String(Files.readAllBytes(qgdefineIni), Charset.forName("GBK"));
            String section = "";
            Map<String, String> values = new HashMap<>();
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                        || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    flush(section, values);
                    section = line.substring(1, line.length() - 1).trim();
                    values.clear();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && !section.isEmpty()) {
                    values.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                            line.substring(eq + 1).trim());
                }
            }
            flush(section, values);
        } catch (IOException e) {
            log.warn("qgdefine.ini read failed: {}", qgdefineIni, e);
        }
        log.info("qinggong definitions total={} from {}", definitions.size(), qgdefineIni);
    }

    private void flush(String section, Map<String, String> values) {
        if (section.isEmpty()) {
            return;
        }
        definitions.put(section, new Definition(section,
                parseInt(values.get("type")),
                parseInt(values.get("usetype")),
                parseInt(values.get("consume")),
                orEmpty(values.get("bufferid"))));
    }

    public Definition lookup(String id) {
        return definitions.get(id);
    }

    public int size() {
        return definitions.size();
    }

    public static BuffSpec buffSpec(Definition definition) {
        return definition == null ? null : BUFF_BY_BUFFER_ID.get(definition.bufferId());
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
