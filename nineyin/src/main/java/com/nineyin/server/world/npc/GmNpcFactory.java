package com.nineyin.server.world.npc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nineyin.server.protocol.NpcSchema;
import com.nineyin.util.ServerPaths;

/**
 * GM 运行时 NPC 摆放工厂：从 npcconfig 模板表克隆一个模板（defaults → 模板行 →
 * 覆盖属性 → 摆放坐标，与场景目录同一条 {@link NpcResolver#resolve} 解析链），
 * 供 spawn_dummy（训练木桩）/ spawn_chest（世界宝箱）等动作在玩家身边生成对象。
 *
 * <p>覆盖键 = NPC schema 属性名（MaxHP/WalkSpeed/Name…，线型由官方表解析）；
 * 结构属性 x/y/z/ay 走 {@link NpcCreator.Transform}。模板表按路径进程级缓存
 * （attacknpc 等表体量大，GM 反复生成不重复读盘）。
 */
public final class GmNpcFactory {

    private static final Map<Path, NpcTemplateTable> TABLES = new ConcurrentHashMap<>();

    /** npcconfig 根（与 NineyinServer.openNpcRegistry 同源）。 */
    public static Path npcConfigRoot() {
        return ServerPaths.projectPath("resources", "modern", "share", "npc", "npcconfig");
    }

    /**
     * 克隆模板为一次 GM 摆放（生产 npcconfig 根）。
     *
     * @param tableRelative npcconfig 相对表路径（如 worldnpc/attacknpc.txt）
     * @param templateId    模板 ConfigID（如 monv010100 木桩）
     * @param overrides     属性覆盖（schema 名 → 官方方言文本，如 MaxHP → "100000"）
     * @throws IOException 表缺失 / 模板不在表内 / 覆盖线型不可解析（带明确信息）
     */
    public static NpcSpawn spawnFromTemplate(String tableRelative, String templateId,
                                             float x, float y, float z, float orient,
                                             Map<String, String> overrides) throws IOException {
        return clone(npcConfigRoot().resolve(tableRelative), templateId, x, y, z, orient, overrides);
    }

    /** 测试注入：指定 npcconfig 根（表缓存键含绝对路径，不同根互不污染）。 */
    static NpcSpawn spawnFromTemplate(Path configRoot, String tableRelative, String templateId,
                                      float x, float y, float z, float orient,
                                      Map<String, String> overrides) throws IOException {
        return clone(configRoot.resolve(tableRelative), templateId, x, y, z, orient, overrides);
    }

    private static NpcSpawn clone(Path file, String templateId,
                                  float x, float y, float z, float orient,
                                  Map<String, String> overrides) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        NpcTemplateTable table;
        try {
            table = TABLES.computeIfAbsent(key, path -> {
                try {
                    return NpcTemplateTable.load(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new IOException("GM 模板表加载失败 " + file + ": " + e.getMessage());
        }
        if (!hasTemplate(table, templateId)) {
            throw new IOException("模板 " + templateId + " 不在 " + file);
        }
        NpcCreator.Transform transform = new NpcCreator.Transform();
        transform.x = x;
        transform.y = y;
        transform.z = z;
        transform.ay = orient;
        Map<String, NpcCreator.RawValue> attributes = new LinkedHashMap<>();
        attributes.put("x", new NpcCreator.RawValue(Float.toString(x), true));
        attributes.put("y", new NpcCreator.RawValue(Float.toString(y), true));
        attributes.put("z", new NpcCreator.RawValue(Float.toString(z), true));
        attributes.put("ay", new NpcCreator.RawValue(Float.toString(orient), true));
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            attributes.put(override.getKey(), new NpcCreator.RawValue(override.getValue(), true));
        }
        NpcCreator.Instance instance = new NpcCreator.Instance(
                "gm-spawn:" + file.getFileName() + ":" + templateId, 0, "gm", templateId,
                transform, attributes);
        NpcResolver.ResolvedNpc resolved;
        try {
            resolved = NpcResolver.resolve(NpcSchema.visibleNpcModernV1(), table, instance,
                    NpcResolver.spawnDefaultsCompatV1());
        } catch (RuntimeException e) {
            throw new IOException("模板 " + templateId + " 解析失败: " + e.getMessage());
        }
        return new NpcSpawn(resolved, x, y, z, orient);
    }

    /** lookup 兼容两种方言：缺模板返回 null 或抛 IllegalStateException（不同表版本）。 */
    private static boolean hasTemplate(NpcTemplateTable table, String templateId) {
        try {
            return table.lookup(templateId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private GmNpcFactory() {
    }
}
