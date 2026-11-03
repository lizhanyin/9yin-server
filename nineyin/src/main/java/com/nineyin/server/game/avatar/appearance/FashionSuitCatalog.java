package com.nineyin.server.game.avatar.appearance;

import com.nineyin.commom.cache.ResourceCache;
import com.nineyin.util.ServerPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 身份装/时装目录：客户端 {@code ini/form/fashion_suit.ini}（角色面板「时装」
 * 界面的总目录）。两组段：
 *
 * <ul>
 *   <li><b>身份装</b>（shengfen 组）：[school_*]/[force_*]/[newschool_*] 与
 *       世家/帮会/生活/活动段，段内 {@code r=<configid>} 按身份等级排布——
 *       equipment.ini 里 ItemType=180/181、EquipType=Suit/NewSuit，穿戴占纸娃娃
 *       槽 16；角色按 faction 自动拥有所属门派/势力/新门派段。</li>
 *   <li><b>时装</b>（fashion 组）：[hat]/[coat]/[shoes]/[suit] 四段，
 *       EquipType=FashionHat/FashionCoat/FashionShoes，穿戴占槽 17-19。</li>
 * </ul>
 *
 * <p>表经 {@link ResourceCache} 按目录缓存；configid 均为 ASCII，Latin-1 无损读
 * （同 equipment.ini 约定，GBK 注释不截断）。
 */
public final class FashionSuitCatalog {

    /** 时装四段段名（[base_info] fashion= 行钉死的组名）。 */
    private static final List<String> FASHION_SECTIONS = List.of("hat", "coat", "shoes", "suit");
    /** 身份装通用段名（[base_info] shengfen= 行中不带前缀列表的组）。 */
    private static final List<String> IDENTITY_PLAIN_SECTIONS =
            List.of("shijia", "guild", "life", "activity");

    private final Path formRoot;
    /** 段名（原样大小写）→ configid 顺序列表（r= 行序）。 */
    private final Map<String, List<String>> sections = new LinkedHashMap<>();

    public static FashionSuitCatalog load(Path formRoot) {
        FashionSuitCatalog catalog = new FashionSuitCatalog(formRoot);
        ResourceCache.ini(formRoot.resolve("fashion_suit.ini"), StandardCharsets.ISO_8859_1,
                (section, key, value) -> {
                    if (!key.equalsIgnoreCase("r") || value.isEmpty()) {
                        return;
                    }
                    catalog.sections.computeIfAbsent(section, s -> new ArrayList<>()).add(value);
                });
        return catalog;
    }

    /** 常驻缓存（serverRoot 变化自动换表，同 SkinCatalog.loadDefault 键策略）。 */
    public static FashionSuitCatalog loadDefault() {
        Path formRoot = ServerPaths.projectPath("resources", "modern", "ini", "form");
        return ResourceCache.of(formRoot, () -> load(formRoot));
    }

    private FashionSuitCatalog(Path formRoot) {
        this.formRoot = formRoot;
    }

    /** 目录是否可用（fashion_suit.ini 缺失/未解析时为空目录，GM 动作据此报错）。 */
    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /**
     * 角色所属身份装清单：faction 值即段名（force_yihua→[force_yihua]、
     * newschool_tianya→[newschool_tianya]）；旧门派 faction 不带前缀
     * （如 shaolin），补 school_ 前缀命中 [school_shaolin]。未知门派返回空。
     */
    public List<String> identitySuitsOf(String faction) {
        if (faction == null || faction.isEmpty()) {
            return List.of();
        }
        String trimmed = faction.trim();
        List<String> direct = sections.get(trimmed);
        if (direct != null) {
            return List.copyOf(direct);
        }
        List<String> schoolPrefixed = sections.get("school_" + trimmed);
        return schoolPrefixed == null ? List.of() : List.copyOf(schoolPrefixed);
    }

    /** 时装四段合集（hat→coat→shoes→suit 段序，configid 去重）。 */
    public List<String> fashionSuits() {
        Set<String> suits = new LinkedHashSet<>();
        for (String section : FASHION_SECTIONS) {
            List<String> ids = sections.get(section);
            if (ids != null) {
                suits.addAll(ids);
            }
        }
        return List.copyOf(suits);
    }

    /** 全部身份装（shengfen 组各段合集，configid 去重；GM/校准用）。 */
    public List<String> allIdentitySuits() {
        Set<String> suits = new LinkedHashSet<>();
        for (String section : sections.keySet()) {
            if (isIdentitySection(section)) {
                suits.addAll(sections.get(section));
            }
        }
        return List.copyOf(suits);
    }

    /** 是否时装目录 configid（四段内；穿戴校验用）。 */
    public boolean isFashionSuit(String configId) {
        if (configId == null || configId.isEmpty()) {
            return false;
        }
        String normalized = configId.trim().toLowerCase(Locale.ROOT);
        for (String suit : fashionSuits()) {
            if (suit.toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 段名 → configid 列表（测试/校准只读视图）。 */
    public Map<String, List<String>> sections() {
        Map<String, List<String>> view = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            view.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return view;
    }

    Path formRoot() {
        return formRoot;
    }

    /** 身份装段判定：school_/force_/newschool_ 前缀 + 世家/帮会/生活/活动通用段名。 */
    private boolean isIdentitySection(String section) {
        String lower = section.toLowerCase(Locale.ROOT);
        return lower.startsWith("school_") || lower.startsWith("force_") || lower.startsWith("newschool_")
                || IDENTITY_PLAIN_SECTIONS.contains(lower);
    }
}
