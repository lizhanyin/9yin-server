package com.nineyin.tools.mpq.res;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.nineyin.tools.mpq.MpqException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 资源清单 res-manifest.json —— 外置于 MPQ，描述三层布局：
 *
 * <pre>
 * {
 *   "base":  { "file": "res-base.rep",  "version": "0001" },
 *   "patch": { "file": "res-patch.rep", "version": "0003" },   // 可缺省：无补丁层
 *   "local": "resources",                                       // 本地散文件根（缺省 resources）
 *   "deleted": ["resources\\ini\\old.ini"]                      // 墓碑：三层皆视为不存在
 * }
 * </pre>
 *
 * <p>版本号用于启动校验与热重载判断；deleted 表达 patch 链无法表达的删除。
 */
final class ResManifest {

    static final String DEFAULT_BASE_FILE = "res-base.rep";
    static final String DEFAULT_PATCH_FILE = "res-patch.rep";
    static final String DEFAULT_LOCAL_DIR = "resources";
    static final String FILE_NAME = "res-manifest.json";

    final String baseFile;
    final String baseVersion;
    /** null = 无补丁层。 */
    final String patchFile;
    final String patchVersion;
    /** 本地散文件根（相对清单目录）。 */
    final String localDir;
    /** 墓碑集合（规范化小写）。 */
    final Set<String> deleted;

    private ResManifest(String baseFile, String baseVersion, String patchFile, String patchVersion,
                        String localDir, Set<String> deleted) {
        this.baseFile = baseFile;
        this.baseVersion = baseVersion;
        this.patchFile = patchFile;
        this.patchVersion = patchVersion;
        this.localDir = localDir;
        this.deleted = deleted;
    }

    /** 无清单时的缺省布局：只有 base + 本地层。 */
    static ResManifest defaults() {
        return new ResManifest(DEFAULT_BASE_FILE, "", null, null, DEFAULT_LOCAL_DIR, Set.of());
    }

    static ResManifest load(Path file) {
        JSONObject root;
        try {
            root = JSONObject.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            throw new MpqException("清单不可读：" + file + "（" + e.getMessage() + "）", e);
        }

        JSONObject base = root.getJSONObject("base");
        if (base == null) {
            throw new MpqException("清单缺少 base 段：" + file);
        }
        String patchFile = null;
        String patchVersion = null;
        JSONObject patch = root.getJSONObject("patch");
        if (patch != null) {
            patchFile = patch.getString("file");
            patchVersion = patch.getString("version");
        }

        Set<String> deleted = new HashSet<>();
        JSONArray arr = root.getJSONArray("deleted");
        if (arr != null) {
            for (Object o : arr) {
                // 统一小写：MPQ 名字不区分大小写，查找与移除都以小写为准。
                deleted.add(normalizeLower(String.valueOf(o)));
            }
        }

        return new ResManifest(
                base.getString("file"),
                base.getString("version"),
                patchFile,
                patchVersion,
                root.getString("local"),
                deleted);
    }

    /** 规范化资源名：/ → \，去首尾分隔符。 */
    static String normalize(String name) {
        String n = name.replace('/', '\\');
        while (n.startsWith("\\")) {
            n = n.substring(1);
        }
        while (n.endsWith("\\")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** 本地根目录名规范化为干净的相对路径（去 ./ 与空段，/ 分隔）。 */
    static String localDirName(String localDir) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : localDir.replace('\\', '/').split("/")) {
            if (!p.isEmpty() && !p.equals(".")) {
                parts.add(p);
            }
        }
        return String.join("/", parts);
    }

    /** 规范化 + 小写（MPQ 名字不区分大小写）。 */
    static String normalizeLower(String name) {
        return normalize(name).toLowerCase(Locale.ROOT);
    }

    /** 序列化为清单 JSON 文本（工具侧生成用）。 */
    String toJson() {
        JSONObject root = new JSONObject();
        JSONObject base = new JSONObject();
        base.put("file", baseFile);
        base.put("version", baseVersion);
        root.put("base", base);
        if (patchFile != null) {
            JSONObject patch = new JSONObject();
            patch.put("file", patchFile);
            patch.put("version", patchVersion);
            root.put("patch", patch);
        }
        root.put("local", localDir);
        if (!deleted.isEmpty()) {
            root.put("deleted", new JSONArray(deleted.stream().sorted().toList()));
        }
        return root.toJSONString();
    }
}
