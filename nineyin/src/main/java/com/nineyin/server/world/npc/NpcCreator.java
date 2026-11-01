package com.nineyin.server.world.npc;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * NPC creator XML（funcnpc.xml 等），逐行移植 Go clientdata.LoadNPCCreator。
 * 文件为 UTF-8 或 GBK（按字节有效性判定）；&lt;creator&gt; 可作为随机生成器容器，
 * 其 &lt;item&gt; 缺 x 时按 amount/position 展开为多个实例。
 */
public final class NpcCreator {

    /** Go RawValue。 */
    public record RawValue(String raw, boolean present) {
    }

    /** Go NPCTransform：X/Y/Z 必填，轴/缩放可选。 */
    public static final class Transform {
        public float x;
        public float y;
        public float z;
        public Float ax;
        public Float ay;
        public Float az;
        public Float sx;
        public Float sy;
        public Float sz;
    }

    /** Go NPCCreatorInstance。 */
    public static final class Instance {
        public final String source;
        public final int ordinal;
        public final String no;
        public final String templateId;
        public final Transform transform;
        public final Map<String, RawValue> attributes;

        Instance(String source, int ordinal, String no, String templateId,
                 Transform transform, Map<String, RawValue> attributes) {
            this.source = source;
            this.ordinal = ordinal;
            this.no = no;
            this.templateId = templateId;
            this.transform = transform;
            this.attributes = attributes;
        }

        public String instanceKey() {
            return source + "#" + ordinal;
        }
    }

    public static final class Catalog {
        public final String source;
        public final List<Instance> instances;
        private final Map<String, List<Integer>> byId;

        Catalog(String source) {
            this.source = source;
            this.instances = new ArrayList<>();
            this.byId = new HashMap<>();
        }

        public List<Instance> instancesForTemplate(String configId) {
            List<Integer> indices = byId.getOrDefault(configId, List.of());
            List<Instance> result = new ArrayList<>(indices.size());
            for (int index : indices) {
                result.add(instances.get(index));
            }
            return result;
        }
    }

    public static Catalog load(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        String encoding = StandardCharsets.UTF_8.name();
        if (!isValidUtf8(data)) {
            encoding = "GBK";
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // 外部实体禁用（本地资源文件无需也绝不应解析外部 DTD）。
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        String source = file.toString();
        Catalog catalog = new Catalog(source);
        try {
            String text = new String(data, Charset.forName(encoding));
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                // 部分 creator 带 UTF-8 BOM：StAX 视其为序言内容。
                text = text.substring(1);
            }
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(text));
            Map<String, RawValue> randomCreator = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "creator" -> randomCreator = attributeMap(reader);
                        case "item" -> {
                            Map<String, RawValue> attributes = attributeMap(reader);
                            if (attributes.containsKey("x")) {
                                if (!isEmptyPlaceholder(attributes)) {
                                    appendInstance(catalog, source, attributes);
                                }
                            } else {
                                if (randomCreator == null) {
                                    throw new IOException("NPC creator " + source + " item "
                                            + (catalog.instances.size() + 1) + ": missing x outside random creator");
                                }
                                List<Map<String, RawValue>> positions = readRandomItemPositions(reader);
                                if (!isEmptyPlaceholder(attributes)) {
                                    for (Map<String, RawValue> expanded
                                            : expandRandomCreatorItem(randomCreator, attributes, positions)) {
                                        appendInstance(catalog, source, expanded);
                                    }
                                }
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "creator".equals(reader.getLocalName())) {
                    randomCreator = null;
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("parse NPC creator " + source + ": " + e.getMessage(), e);
        }
        return catalog;
    }

    /** 占位条目：空 id 且 amount=0（现代导出器用它承载未使用的随机点位）。 */
    static boolean isEmptyPlaceholder(Map<String, RawValue> attributes) {
        if (!attributeText(attributes, "id").trim().isEmpty()) {
            return false;
        }
        RawValue raw = attributes.get("amount");
        if (raw == null || !raw.present()) {
            return false;
        }
        try {
            return Float.parseFloat(raw.raw().trim()) == 0f;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, RawValue> attributeMap(XMLStreamReader reader) {
        Map<String, RawValue> result = new LinkedHashMap<>();
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            result.put(reader.getAttributeLocalName(i), new RawValue(reader.getAttributeValue(i), true));
        }
        return result;
    }

    private static void appendInstance(Catalog catalog, String source, Map<String, RawValue> attributes)
            throws IOException {
        int ordinal = catalog.instances.size() + 1;
        String templateId = attributeText(attributes, "id");
        if (templateId.isEmpty()) {
            throw new IOException("NPC creator " + source + " item " + ordinal + " has no id");
        }
        Transform transform = new Transform();
        transform.x = requiredFloat(attributes, "x", source, ordinal);
        transform.y = requiredFloat(attributes, "y", source, ordinal);
        transform.z = requiredFloat(attributes, "z", source, ordinal);
        Float[] axes = new Float[6];
        String[] axisNames = {"ax", "ay", "az", "sx", "sy", "sz"};
        for (int i = 0; i < axisNames.length; i++) {
            axes[i] = optionalFloat(attributes, axisNames[i]);
        }
        transform.ax = axes[0];
        transform.ay = axes[1];
        transform.az = axes[2];
        transform.sx = axes[3];
        transform.sy = axes[4];
        transform.sz = axes[5];
        Instance instance = new Instance(source, ordinal, attributeText(attributes, "No"),
                templateId, transform, attributes);
        int index = catalog.instances.size();
        catalog.instances.add(instance);
        catalog.byId.computeIfAbsent(templateId, id -> new ArrayList<>()).add(index);
    }

    /** 消费随机 item 的子元素直到其闭合标签，收集 &lt;position&gt; 属性。 */
    private static List<Map<String, RawValue>> readRandomItemPositions(XMLStreamReader reader)
            throws XMLStreamException {
        List<Map<String, RawValue>> positions = new ArrayList<>();
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if ("position".equals(reader.getLocalName())) {
                    positions.add(attributeMap(reader));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return positions;
    }

    /** Go expandRandomCreatorItem：无点位时按 amount 在 radius*0.65 圆环上展开。 */
    static List<Map<String, RawValue>> expandRandomCreatorItem(
            Map<String, RawValue> creator, Map<String, RawValue> item,
            List<Map<String, RawValue>> positions) {
        Map<String, RawValue> base = new LinkedHashMap<>(creator);
        base.putAll(item);
        String creatorNo = attributeText(creator, "No");
        String itemNo = attributeText(item, "No");
        if (positions.isEmpty()) {
            int amount = 1;
            String raw = attributeText(item, "amount");
            if (!raw.isEmpty()) {
                try {
                    int parsed = Integer.parseInt(raw.trim());
                    if (parsed > 0 && parsed <= 128) {
                        amount = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Go 同样忽略不可解析 amount，回退 1。
                }
            }
            float radius = optionalFloat(creator, "radius") == null ? 0f : optionalFloat(creator, "radius");
            Float centerX = optionalFloat(creator, "x");
            Float centerZ = optionalFloat(creator, "z");
            positions = new ArrayList<>(amount);
            for (int i = 0; i < amount; i++) {
                Map<String, RawValue> position = new LinkedHashMap<>();
                if (amount > 1 && radius > 0) {
                    double angle = 2 * Math.PI * i / amount;
                    position.put("x", new RawValue(String.format(Locale.ROOT, "%.3f",
                            (centerX == null ? 0 : centerX) + radius * 0.65 * Math.cos(angle)), true));
                    position.put("z", new RawValue(String.format(Locale.ROOT, "%.3f",
                            (centerZ == null ? 0 : centerZ) + radius * 0.65 * Math.sin(angle)), true));
                }
                positions.add(position);
            }
        }
        List<Map<String, RawValue>> result = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            Map<String, RawValue> expanded = new LinkedHashMap<>(base);
            expanded.putAll(positions.get(i));
            expanded.put("No", new RawValue(creatorNo + ":" + itemNo + ":" + (i + 1), true));
            result.add(expanded);
        }
        return result;
    }

    static String attributeText(Map<String, RawValue> attributes, String name) {
        RawValue value = attributes.get(name);
        return value == null ? "" : value.raw();
    }

    private static float requiredFloat(Map<String, RawValue> attributes, String name,
                                       String source, int ordinal) throws IOException {
        Float value = optionalFloat(attributes, name);
        if (value == null) {
            throw new IOException("NPC creator " + source + " item " + ordinal + ": missing " + name);
        }
        return value;
    }

    static Float optionalFloat(Map<String, RawValue> attributes, String name) {
        RawValue raw = attributes.get(name);
        if (raw == null || !raw.present()) {
            return null;
        }
        float value;
        try {
            value = Float.parseFloat(raw.raw().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + "=" + raw.raw() + " is not float32", e);
        }
        if (value != value || value == Float.POSITIVE_INFINITY || value == Float.NEGATIVE_INFINITY) {
            throw new IllegalStateException(name + "=" + raw.raw() + " is not finite");
        }
        return value;
    }

    private static boolean isValidUtf8(byte[] data) {
        try {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(buffer);
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }
}
