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
 * 修炼经验曲线（share/faculty/wuxuelevelinfo.ini，GBK）：
 * 节名 = 技能 ConfigID，行 `r=等级,该级修炼度,速度`。
 * 语义（与 role_faculty 快照实测对齐）：当前等级 L 的 total 即 r[L]
 * ——升到 L+1 需要积累 r[L] 点修炼度；末级行 +1 为数据侧等级上限。
 * 缺节/缺行返回 -1，调用方走旧公式回退（total+=200 / maxPower+=20）。
 */
public final class WuxueLevelInfo {

    private static final Logger log = LoggerFactory.getLogger(WuxueLevelInfo.class);

    /** 单本曲线：等级 → 该级修炼度；行尾速度列全表恒 172，仅保留作诊断。 */
    static final class Curve {
        final int[] totalByLevel = new int[MAX_LEVEL_SLOTS];
        int lastLevel;

        long total(int level) {
            return level >= 1 && level <= lastLevel ? totalByLevel[level] : -1L;
        }
    }

    /** 曲线行最大等级槽位（wuxuelevelinfo 实测最大 85，留余量）。 */
    static final int MAX_LEVEL_SLOTS = 256;

    private final Map<String, Curve> curves = new HashMap<>();

    public void load(Path wuxuelevelinfoIni) {
        if (!Files.isRegularFile(wuxuelevelinfoIni)) {
            log.warn("wuxuelevelinfo.ini missing: {}", wuxuelevelinfoIni);
            return;
        }
        try {
            String data = new String(Files.readAllBytes(wuxuelevelinfoIni), Charset.forName("GBK"));
            String section = "";
            Curve curve = null;
            for (String rawLine : data.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")
                        || line.startsWith("//")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    curve = new Curve();
                    curves.put(section, curve);
                    continue;
                }
                if (!line.startsWith("r=") || curve == null || section.isEmpty()) {
                    continue;
                }
                String[] parts = line.substring(2).split(",");
                if (parts.length < 2) {
                    continue;
                }
                int level = parseInt(parts[0]);
                long total = parseLong(parts[1]);
                if (level < 1 || level >= MAX_LEVEL_SLOTS || total <= 0) {
                    continue;
                }
                curve.totalByLevel[level] = (int) Math.min(total, Integer.MAX_VALUE);
                if (level > curve.lastLevel) {
                    curve.lastLevel = level;
                }
            }
        } catch (IOException e) {
            log.warn("wuxuelevelinfo.ini read failed: {}", wuxuelevelinfoIni, e);
        }
        log.info("wuxue level curves total={} from {}", curves.size(), wuxuelevelinfoIni);
    }

    /** 等级 L 的修炼度需求；未知返回 -1。 */
    public long total(String configId, int level) {
        Curve curve = curves.get(configId);
        return curve == null ? -1L : curve.total(level);
    }

    /** 数据侧等级上限（末级行 + 1）；无曲线返回 -1。 */
    public int maxLevel(String configId) {
        Curve curve = curves.get(configId);
        return curve == null || curve.lastLevel == 0 ? -1 : curve.lastLevel + 1;
    }

    public int size() {
        return curves.size();
    }

    private static int parseInt(String s) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long parseLong(String s) {
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return -1L;
        }
    }
}
