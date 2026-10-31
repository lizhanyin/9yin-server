package com.nineyin.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * NPC 模板仓库：递归加载 npcconfig 目录下全部 .txt（GBK TSV，
 * 首行中文表头、次行英文字段名）。字段名兼容多拼写（ID/Id/ConfigID、
 * script/Script/ScriptClass 等），跳过 npc_type。
 */
public final class NpcTemplateRepository {

    public static final Charset GBK = Charset.forName("GBK");

    private static final Logger log = LoggerFactory.getLogger(NpcTemplateRepository.class);

    private final Map<String, NpcTemplate> templates = new HashMap<>();
    private volatile boolean loaded;

    public NpcTemplateRepository() {
        // 风水宝地标记（Fsbd_*，commonnpc.xml 常驻摆放，desc=「风水宝地标记（山/林/水/洞/雪）」）。
        // 无 npcconfig 表行：模板缺行会被 SceneFlow.visibleNpcs 当 MISSING 丢弃，
        // 这里按 configId 合成最简模板（脚本类留空＝非 eventnpc，客户端按 ConfigID 自行渲染）。
        // 环境效果（ui_xl_*）：山→火/阳、林→木/阳、水→水/阴、洞→土/阴阳、雪→金/阴，
        // 提高对应属性真气出现几率与武学经脉聚气速度（修炼界面「当前所在地的风水情况」数据源）。
        for (String[] fsbd : new String[][]{
                {"Fsbd_shan_01", "风水宝地·山"},
                {"Fsbd_lin_01", "风水宝地·林"},
                {"Fsbd_shui_01", "风水宝地·水"},
                {"Fsbd_dong_01", "风水宝地·洞"},
                {"Fsbd_xue_01", "风水宝地·雪"}}) {
            templates.put(fsbd[0], new NpcTemplate(fsbd[0], "", fsbd[1], "", "", "",
                    0, 1000, 0, 0, 0, 0, "", "", "", "", 0f, List.of(), 0, 0, 0, null));
        }
    }

    public synchronized void load(Path npcconfigRoot) {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isDirectory(npcconfigRoot)) {
            log.warn("npcconfig missing: {}", npcconfigRoot);
            return;
        }
        try (Stream<Path> files = Files.walk(npcconfigRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("npc_type.txt")
                            && !fileName(p).equals("npc_type"))
                    .forEach(this::loadOneFile);
        } catch (IOException e) {
            log.warn("npc template scan error: {}", npcconfigRoot, e);
        }
        log.info("npc templates total={} from {}", templates.size(), npcconfigRoot);
    }

    private static String fileName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private void loadOneFile(Path path) {
        try {
            int rows = 0;
            int idCol = -1, resCol = -1, nameCol = -1, levelCol = -1, hpCol = -1;
            int atkCol = -1, defCol = -1, accCol = -1, evaCol = -1;
            int scriptCol = -1, actionCol = -1, weaponCol = -1;
            int shopCol = -1, depotCol = -1, doorPkgCol = -1, transferCol = -1, springCol = -1;
            int skillCol = -1, openRangeCol = -1, openActionCol = -1, delayCol = -1;
            int npcTypeCol = -1, selStateCol = -1, cursorCol = -1;
            String data = new String(Files.readAllBytes(path), GBK);
            for (String line : data.split("\n")) {
                if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '/') {
                    continue;
                }
                String[] cols = splitTsv(line);
                if (idCol < 0) {
                    for (int i = 0; i < cols.length; i++) {
                        String h = stripBom(trim(cols[i]));
                        switch (h) {
                            case "ID", "Id", "id", "ConfigID" -> idCol = i;
                            case "Resource", "resource", "Res" -> resCol = i;
                            case "Name", "name" -> nameCol = i;
                            case "Level", "level" -> levelCol = i;
                            case "MaxHP", "maxhp", "HP" -> hpCol = i;
                            case "AttackPower", "Attack", "attack", "WaiGong" -> atkCol = i;
                            case "Defence", "Defense", "defence", "WaiFang" -> defCol = i;
                            case "Accuracy", "Hit", "hit" -> accCol = i;
                            case "Evasion", "Dodge", "dodge", "Miss" -> evaCol = i;
                            case "script", "Script", "ScriptClass" -> scriptCol = i;
                            case "ActionSet", "actionset" -> actionCol = i;
                            case "WeaponMode", "weaponmode" -> weaponCol = i;
                            case "ShopID", "ShopId", "shopid" -> shopCol = i;
                            case "DepotID", "DepotId", "depotid" -> depotCol = i;
                            case "DoorPackageID", "DoorPackageId", "doorpackageid" -> doorPkgCol = i;
                            case "PlayerTransferPosition", "playertransferposition" -> transferCol = i;
                            case "SpringRange", "springrange" -> springCol = i;
                            case "table@SkillRec" -> skillCol = i;
                            case "OpenRange", "openrange" -> openRangeCol = i;
                            case "OpenAction", "openaction" -> openActionCol = i;
                            case "DelayTime", "delaytime" -> delayCol = i;
                            case "NpcType", "npctype" -> npcTypeCol = i;
                            case "SelectState", "selectstate" -> selStateCol = i;
                            case "CursorShape", "cursorshape" -> cursorCol = i;
                            default -> {
                            }
                        }
                    }
                    continue;
                }
                if (idCol >= cols.length) {
                    continue;
                }
                String id = trim(cols[idCol]);
                if (id.length() >= 2 && id.startsWith("\"") && id.endsWith("\"")) {
                    id = id.substring(1, id.length() - 1);
                }
                if (id.isEmpty()) {
                    continue;
                }
                NpcTemplate info = new NpcTemplate(id,
                        cell(cols, resCol), cell(cols, nameCol), cell(cols, scriptCol),
                        cell(cols, actionCol), cell(cols, weaponCol),
                        parseIntOr(cell(cols, levelCol), 0), parseIntOr(cell(cols, hpCol), 0),
                        parseIntOr(cell(cols, atkCol), 0), parseIntOr(cell(cols, defCol), 0),
                        parseIntOr(cell(cols, accCol), 0), parseIntOr(cell(cols, evaCol), 0),
                        cell(cols, shopCol), cell(cols, depotCol),
                        cell(cols, doorPkgCol), cell(cols, transferCol),
                        parseFloatOr(cell(cols, springCol), 0f),
                        parseSkills(cell(cols, skillCol)),
                        parseIntOr(cell(cols, npcTypeCol), 0),
                        parseIntOr(cell(cols, selStateCol), 0),
                        parseIntOr(cell(cols, cursorCol), 0),
                        parseBox(cell(cols, openRangeCol),
                                cell(cols, openActionCol), cell(cols, delayCol)));
                templates.putIfAbsent(id, info);
                rows++;
            }
            if (rows > 0) {
                log.info("npc template file {} rows={}", path.getFileName(), rows);
            }
        } catch (IOException e) {
            log.warn("npc template read failed: {}", path, e);
        }
    }

    public NpcTemplate lookup(String configId) {
        NpcTemplate t = templates.get(configId);
        if (t != null) {
            return t;
        }
        int slash = configId.lastIndexOf('\\');
        if (slash >= 0 || (slash = configId.lastIndexOf('/')) >= 0) {
            return templates.getOrDefault(configId.substring(slash + 1), NpcTemplate.MISSING);
        }
        return NpcTemplate.MISSING;
    }

    public int size() {
        return templates.size();
    }

    private static String cell(String[] cols, int col) {
        return col >= 0 && col < cols.length ? trim(cols[col]) : "";
    }

    private static String trim(String s) {
        int end = s.length();
        while (end > 0 && isSpace(s.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == '\r' || c == ' ' || c == '\t';
    }

    /** 去除 UTF-8 BOM 字符（GBK 误读成 EF BB BF 的翻版 0xEF…）。 */
    private static String stripBom(String h) {
        return !h.isEmpty() && h.charAt(0) == 0xEF ? h.substring(1) : h;
    }

    private static int parseIntOr(String s, int def) {
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float parseFloatOr(String s, float def) {
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** table@SkillRec：`skillId,level,?;skillId,level,?`（引号内整段）。 */
    private static List<NpcTemplate.SkillRef> parseSkills(String cell) {
        if (cell.isEmpty()) {
            return List.of();
        }
        java.util.List<NpcTemplate.SkillRef> out = new java.util.ArrayList<>();
        for (String part : cell.split(";")) {
            String[] seg = part.trim().split(",");
            String id = seg.length > 0 ? trim(seg[0]) : "";
            if (id.isEmpty()) {
                continue;
            }
            out.add(new NpcTemplate.SkillRef(id, seg.length > 1 ? parseIntOr(trim(seg[1]), 1) : 1));
        }
        return out;
    }

    /** 宝箱表列：OpenRange/OpenAction/DelayTime（缺省 3m / interact041 / 5s）。 */
    private static NpcTemplate.BoxInfo parseBox(String openRange, String openAction, String delay) {
        boolean any = !openRange.isEmpty() || !openAction.isEmpty() || !delay.isEmpty();
        if (!any) {
            return null;
        }
        return new NpcTemplate.BoxInfo(
                parseFloatOr(openRange, 3f),
                openAction.isEmpty() ? "interact041" : openAction,
                parseLongOr(delay, 5000L));
    }

    private static long parseLongOr(String s, long def) {
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String[] splitTsv(String line) {        StringBuilder cur = new StringBuilder();
        java.util.List<String> cols = new java.util.ArrayList<>();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '\t') {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols.toArray(new String[0]);
    }
}
