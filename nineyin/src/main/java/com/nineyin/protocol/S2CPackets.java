package com.nineyin.protocol;

/**
 * S2C 包构造器，逐字节对齐 C++ 工程的 make_* / encode_* 家族。
 * 每个方法的字节布局在注释中标注偏移。
 */
public final class S2CPackets {

    public static final int PLAYER_OBJECT_ID = 1;
    public static final int PLAYER_OWNER_ID = 1;

    private S2CPackets() {
    }

    // ---------- 0x10 ServerObjectProperty ----------
    // 头 12 字节：[0]=0x10 [1]=0 u32 oid@2 u32 owner@6 u16 count=1@10，随后 u16 属性索引 + 值

    public static byte[] objectPropertyInt(int objectId, int ownerId, int propIdx, int value) {
        return objectPropertyHeader(objectId, ownerId)
                .u16(propIdx)
                .i32(value)
                .toByteArray();
    }

    public static byte[] objectPropertyInt(int propIdx, int value) {
        return objectPropertyInt(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, propIdx, value);
    }

    public static byte[] objectPropertyByte(int objectId, int ownerId, int propIdx, int value) {
        return objectPropertyHeader(objectId, ownerId)
                .u16(propIdx)
                .u8(value)
                .toByteArray();
    }

    public static byte[] objectPropertyByte(int propIdx, int value) {
        return objectPropertyByte(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, propIdx, value);
    }

    public static byte[] objectPropertyString(int objectId, int ownerId, int propIdx, String value) {
        return objectPropertyHeader(objectId, ownerId)
                .u16(propIdx)
                .string(value)
                .toByteArray();
    }

    public static byte[] objectPropertyString(int propIdx, String value) {
        return objectPropertyString(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, propIdx, value);
    }

    private static PacketWriter objectPropertyHeader(int objectId, int ownerId) {
        return new PacketWriter()
                .u8(0x10)
                .u8(0)
                .u32(objectId)
                .u32(ownerId)
                .u16(1);
    }

    // ---------- 0x10 多属性批量更新 ----------
    // 头 12 字节：[0]=0x10 [1]=0 u32 oid@2 u32 owner@6 u16 个数@10（build 时回填），
    // 随后 (u16 属性索引 + 按协商类型的裸值)*。值编码由属性在 0x09 表中的类型决定。

    public static final class Batch {

        private final PacketWriter w;
        private int count;

        Batch(int objectId, int ownerId) {
            w = new PacketWriter().u8(0x10).u8(0).u32(objectId).u32(ownerId).u16(0);
        }

        public Batch u8(int propIdx, int value) {
            count++;
            w.u16(propIdx).u8(value);
            return this;
        }

        public Batch i32(int propIdx, int value) {
            count++;
            w.u16(propIdx).i32(value);
            return this;
        }

        public Batch i64(int propIdx, long value) {
            count++;
            w.u16(propIdx).i64(value);
            return this;
        }

        public Batch f32(int propIdx, float value) {
            count++;
            w.u16(propIdx).f32(value);
            return this;
        }

        public Batch str(int propIdx, String value) {
            count++;
            w.u16(propIdx).string(value);
            return this;
        }

        public Batch wide(int propIdx, String value) {
            count++;
            w.u16(propIdx);
            appendWideStringProp(w, value);
            return this;
        }

        public Batch obj(int propIdx, int objectId, int ownerId) {
            count++;
            w.u16(propIdx).u32(objectId).u32(ownerId);
            return this;
        }

        public byte[] build() {
            byte[] body = w.toByteArray();
            body[10] = (byte) (count & 0xFF);
            body[11] = (byte) ((count >> 8) & 0xFF);
            return body;
        }
    }

    /** 面向玩家的多属性 0x10 批量包。 */
    public static Batch playerBatch() {
        return new Batch(PLAYER_OBJECT_ID, PLAYER_OWNER_ID);
    }

    public static Batch batch(int objectId, int ownerId) {
        return new Batch(objectId, ownerId);
    }

    // ---------- 0x0E ServerRemoveObject（9 字节） ----------

    public static byte[] removeObject(int objectId, int ownerId) {
        return new PacketWriter().u8(0x0E).u32(objectId).u32(ownerId).toByteArray();
    }

    // ---------- 门派三元属性（School/Force/NewSchool 互斥） ----------

    /** factionProperties：原始门派写 School，force_ 写 Force，newschool_ 写 NewSchool，伴随清空其余两项。 */
    public static void appendFaction(Batch batch, String faction) {
        String school = faction;
        String force = "";
        String newSchool = "";
        if (faction != null && faction.startsWith("force_")) {
            school = "";
            force = faction;
        } else if (faction != null && faction.startsWith("newschool_")) {
            school = "";
            newSchool = faction;
        }
        batch.str(PropertyId.SCHOOL, school)
                .str(PropertyId.FORCE, force)
                .str(PropertyId.NEW_SCHOOL, newSchool);
    }

    // ---------- 0x0A 记录表 schema ----------
    // [0]=0x0A u16 表数@1，随后每表：name\0 + u16 列数 + 列类型字节

    public static byte[] recordTableEmpty() {
        PacketWriter w = new PacketWriter().u8(0x0A).u16(RecordId.SCHEMAS.length);
        for (RecordId.Schema schema : RecordId.SCHEMAS) {
            w.raw(schema.name().getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .u8(0)
                    .u16(schema.columnTypes().length);
            for (int type : schema.columnTypes()) {
                w.u8(type);
            }
        }
        return w.toByteArray();
    }

    // ---------- 0x11 ServerRecordAddRow ----------
    // 头 16 字节：[0]=0x11 [1]=0 u32 oid@2 u32 owner@6 u16 表索引@10 u16 行号@12 u16 行数=1@14，
    // 随后按列顺序写值：Integer→i32，Long→i64，String→长度前缀串

    public static byte[] recordAddRow(int recIdx, int row, Object... values) {
        PacketWriter w = recordRowHeader(0x11, recIdx, row)
                .u16(1);
        appendRecordValues(w, values);
        return w.toByteArray();
    }

    public static byte[] shortcutAdd(int index, String kind, String configId) {
        return recordAddRow(RecordId.SHORTCUT_REC, 0, index, kind, configId);
    }

    // ---------- 0x12 ServerRecordDelRow ----------
    // 头 14 字节：[0]=0x12 [1]=0 u32 oid@2 u32 owner@6 u16 表索引@10 u16 行号@12

    public static byte[] recordDelRow(int recIdx, int row) {
        return recordRowHeader(0x12, recIdx, row).toByteArray();
    }

    private static PacketWriter recordRowHeader(int opcode, int recIdx, int row) {
        return new PacketWriter()
                .u8(opcode)
                .u8(0)
                .u32(PLAYER_OBJECT_ID)
                .u32(PLAYER_OWNER_ID)
                .u16(recIdx)
                .u16(row);
    }

    private static void appendRecordValues(PacketWriter w, Object[] values) {
        for (Object v : values) {
            if (v instanceof Integer i) {
                w.i32(i);
            } else if (v instanceof Long l) {
                w.i64(l);
            } else if (v instanceof String s) {
                w.string(s);
            } else {
                throw new IllegalArgumentException(
                        "unsupported record value type: " + (v == null ? "null" : v.getClass().getName()));
            }
        }
    }

    // ---------- 0x1F ServerLocation（25 字节） ----------
    // [0]=0x1F u32 oid@1 u32 owner@5 f32 x@9 y@13 z@17 orient@21

    public static byte[] serverLocation(int objectId, int ownerId,
                                        float x, float y, float z, float orient) {
        return new PacketWriter()
                .u8(0x1F)
                .u32(objectId)
                .u32(ownerId)
                .f32(x).f32(y).f32(z).f32(orient)
                .toByteArray();
    }

    // ---------- 0x20 ServerMoving（45 字节，尾部 20 字节保留 0） ----------

    public static byte[] serverMoving(int objectId, int ownerId,
                                      float destX, float destY, float destZ, float destOrient) {
        PacketWriter w = new PacketWriter()
                .u8(0x20)
                .u32(objectId)
                .u32(ownerId)
                .f32(destX).f32(destY).f32(destZ).f32(destOrient);
        byte[] msg = new byte[45];
        byte[] head = w.toByteArray();
        System.arraycopy(head, 0, msg, 0, head.length);
        return msg;
    }

    // ---------- 0x38 时间同步（30 字节） ----------
    // [0]=0x38 u32 tick_ms@1 [5]=0 u16 seq@6 [8..9]=0 u16 常量4@10 [12]=0
    // u32 wall_sec@13 [17..20]=0 [21]=1 u32 wall_sec 副本@22 [26..29]=0

    public static byte[] timeSync(int tickMs, int seq, long wallSec) {
        PacketWriter w = new PacketWriter()
                .u8(0x38)
                .u32(tickMs & 0xFFFFFFFFL)
                .u8(0)
                .u16(seq)
                .u16(0)
                .u16(4)
                .u8(0)
                .u32(wallSec & 0xFFFFFFFFL)
                .u32(0)
                .u8(1)
                .u32(wallSec & 0xFFFFFFFFL)
                .u32(0);
        byte[] msg = new byte[30];
        byte[] head = w.toByteArray();
        System.arraycopy(head, 0, msg, 0, head.length);
        return msg;
    }

    public static byte[] timeSync(int tickMs, int seq) {
        return timeSync(tickMs, seq, System.currentTimeMillis() / 1000L);
    }

    // ---------- 0x1D ClearMenu（单字节） ----------

    public static byte[] clearMenu() {
        return new byte[]{0x1D};
    }

    // ---------- 0x05 ServerWorldInfo ----------
    // [0]=0x05 u16 info_type@1，随后 UTF-16LE 文本 + 0x0000 终止

    public static byte[] worldInfo(int infoType, String text) {
        byte[] units = text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        return new PacketWriter()
                .u8(0x05)
                .u16(infoType)
                .raw(units)
                .u16(0)
                .toByteArray();
    }

    /** 全量剧本（静态缺省，与 Go/C++ 一致；运行时按九阴志阶段过滤后下发）。 */
    public static final java.util.List<String> ALL_OPEN_BOOKS =
            java.util.List.of("book1", "book2", "book4", "book5", "book8");

    /** 开放剧本列表（与 Go/C++ 一致）。 */
    public static byte[] openBooksWorldInfo() {
        return openBooksWorldInfo(ALL_OPEN_BOOKS);
    }

    /** 开放剧本列表（九阴志 scenes 门控后的 bookN 列表）：(0,book) 对 + (0,0) 结尾。 */
    public static byte[] openBooksWorldInfo(java.util.List<String> books) {
        StringBuilder sb = new StringBuilder(books.size() * 10 + 4);
        for (String book : books) {
            sb.append("0,").append(book).append(',');
        }
        sb.append("0,0");
        return worldInfo(0, sb.toString());
    }

    // ---------- 0x04 ServerPlayerRoles ----------
    // 头 0x29 字节：[0]=0x04 u32=1@1，年@0x09 月@0x0D 日@0x11 时@0x15 分@0x19 秒@0x1D，角色数 u32@0x25

    public static byte[] loginSuccessNoRole(long epochSec) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochSecond(epochSec)
                .atZone(java.time.ZoneId.systemDefault());
        return new PacketWriter()
                .u8(0x04)
                .u32(1)                    // @0x01
                .u32(0)                    // @0x05-0x08 保留
                .u32(t.getYear())          // @0x09
                .u32(t.getMonthValue())    // @0x0D
                .u32(t.getDayOfMonth())    // @0x11
                .u32(t.getHour())          // @0x15
                .u32(t.getMinute())        // @0x19
                .u32(t.getSecond())        // @0x1D
                .u32(0)                    // @0x21-0x24 保留
                .u32(0)                    // @0x25 角色数 = 0
                .toByteArray();
    }

    /**
     * 单角色登录成功包。角色条目为 6 个类型化字段：
     * (2,i32=0) (2,i32=0) (7,ucs2 角色名) (7,ucs2 role_para 36 段) (2,i32=0) (5,f64=0)。
     * sceneConfig/sceneResource 为角色所在（下线）场景，f[24] 背景名键由 scenes.ini 解析。
     */
    public static byte[] loginSuccessOneRole(String name, java.util.List<String> appearance,
                                             String sceneConfig, String sceneResource, long epochSec) {
        byte[] base = loginSuccessNoRole(epochSec);
        base[0x25] = 1; // 角色数 u32 @0x25

        Appearance.RoleVisual visual = Appearance.resolveVisual(appearance);
        String book = Appearance.get(appearance, 0, "book1");

        // 字段位对齐 Go oneRoleLoginSuccess：门派取 appearance[8]（force_yihua 缺省），
        // f24 场景名用客户端本地化键，f25/f32 下发 book 原值，f33 发外观 hair 原值。
        String[] fields = new String[36];
        java.util.Arrays.fill(fields, "");
        fields[0] = String.valueOf(visual.sex());
        fields[1] = Appearance.faction(appearance);
        fields[2] = "0";
        fields[3] = "0";
        fields[4] = "1";
        fields[5] = Appearance.get(appearance, 2, "");
        fields[6] = "1";
        fields[7] = visual.hair();
        fields[9] = visual.cloth();
        fields[10] = visual.pants();
        fields[11] = visual.shoes();
        fields[21] = "stand";
        fields[22] = "127.0.0.1";
        fields[23] = "title001";
        fields[24] = SceneNames.sceneNameKey(sceneConfig, sceneResource);
        fields[25] = book;
        fields[31] = "0";
        fields[32] = book;
        fields[33] = Appearance.get(appearance, 4, "obj\\char\\b_hair\\b_hair1");
        fields[35] = "0";
        String rolePara = String.join(",", fields);

        PacketWriter full = new PacketWriter().raw(base);
        full.u16(6);                       // 字段数
        full.u8(2).u32(0);
        full.u8(2).u32(0);
        full.u8(7);
        appendUcs2(full, name);
        full.u8(7);
        appendUcs2(full, rolePara);
        full.u8(2).u32(0);
        full.u8(5).f64(0.0);
        return full.toByteArray();
    }

    // ---------- 0x09 属性表 ----------

    public static byte[] propertyTable() {
        return OfficialPropertyTable.packet();
    }

    // ---------- 0x0B EntryScene ----------
    // 头 15 字节：[0]=0x0B u32 oid@1 u32 owner@5 u32=1@9 u16=3@13，
    // 随后 string 属性 1439(ConfigID) 1440(Resource)，u16 1460 + u32 1000000

    public static byte[] entryScene(String config, String resource) {
        return new PacketWriter()
                .u8(0x0B)
                .u32(PLAYER_OBJECT_ID)
                .u32(PLAYER_OWNER_ID)
                .u32(1)
                .u16(3)
                .u16(1439).string(config)
                .u16(1440).string(resource)
                .u16(1460)
                .u32(1000000)
                .toByteArray();
    }

    // ---------- 0x0D 玩家 AddObject ----------
    // 头 0x3F 字节：[0]=0x0D u32 oid@1 u32 owner@5 f32 x@9 y@0x0D z@0x11 orient@0x15，
    // 属性数 u16@0x3D，随后 u16 索引 + 值 列表

    public static byte[] playerAddObject(String name, java.util.List<String> appearance,
                                         float x, float y, float z, float orient) {
        Appearance.RoleVisual visual = Appearance.resolveVisual(appearance);
        PacketWriter w = new PacketWriter();
        w.u8(0x0D)
                .u32(PLAYER_OBJECT_ID)
                .u32(PLAYER_OWNER_ID)
                .f32(x).f32(y).f32(z).f32(orient);
        // 头部固定 0x3F 字节（0x3D/0x3E 为属性数，最后回填），值从 0x3F 起追加
        int headerSize = 0x3F;
        while (w.size() < headerSize) {
            w.u8(0);
        }
        int count = 0;
        count++; w.u16(181).u8(2);                       // Type = player
        count++; w.u16(182).u8(visual.sex());            // Sex
        count++; w.u16(184).string(visual.photo());      // Photo
        count++; w.u16(183).string("stand");             // State
        count++; w.u16(188).string(visual.hair());       // Hair
        count++; w.u16(185).string(visual.face());       // Face
        count++; w.u16(392).string(visual.cloth());      // Cloth
        count++; w.u16(393).string(visual.pants());      // Pants
        count++; w.u16(394).string(visual.shoes());      // Shoes
        count++; w.u16(405).string(visual.actionSet());  // ActionSet
        count++; w.u16(390).string(visual.hair());       // Hat fallback
        count++; w.u16(383).f32(5.0f);                   // MoveSpeed
        count++; w.u16(384).f32(2.5f);                   // WalkSpeed
        count++; w.u16(385).f32(7.0f);                   // RunSpeed
        count++; w.u16(680).f32(8.0f);                   // JumpSpeed
        count++; w.u16(389).i32(100);                    // SpeedRatio
        count++; w.u16(468).i32(100);                    // HPRatio
        count++; w.u16(469).i32(100);                    // MPRatio
        count++; w.u16(577).i32(1000);                   // MaxHP
        count++; w.u16(578).i32(500);                    // MaxMP
        count++; w.u16(464).i32(1000);                   // HP
        count++;
        w.u16(178);
        appendWideStringProp(w, name);                   // Name（宽字符属性形态）
        count++; w.u16(1464).u8(1);                      // Level
        count++;
        w.u16(413).u32(0).u32(0);                        // LastObject 清空（对象值 8 字节）

        byte[] msg = new byte[headerSize + (w.size() - headerSize)];
        byte[] body = w.toByteArray();
        System.arraycopy(body, 0, msg, 0, body.length);
        msg[0x3D] = (byte) (count & 0xFF);
        msg[0x3E] = (byte) ((count >> 8) & 0xFF);
        return msg;
    }

    // ---------- 0x11 轻功记录行（QingGongRec 单 string 列） ----------

    public static byte[] qinggongRowAdd(String id) {
        return recordAddRow(RecordId.QING_GONG_REC, 0, id);
    }

    // ---------- 清选区帧组（ESC 系统面板依赖，LastObject 为对象形态） ----------

    public static java.util.List<byte[]> clearSelectionFrames() {
        return java.util.List.of(
                SkillPackets.lastObject(PLAYER_OBJECT_ID, PLAYER_OWNER_ID, 0, 0),
                objectPropertyInt(PropertyId.AUTO_SELECT_OBJECT, 0),
                objectPropertyInt(PropertyId.CUR_SKILL_TARGET, 0),
                objectPropertyByte(PropertyId.SELECT_STATE, 0),
                clearMenu());
    }

    /** ucs2 编码：u32 字节数(含 NUL) + UTF-16LE + 0x0000（对齐 C++ append_ucs2）。 */
    static void appendUcs2(PacketWriter w, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        w.u32(bytes.length + 2).raw(bytes).u16(0);
    }

    /** 宽字符属性值（对齐 C++ append_widestr_prop：索引 + u32 字节数 + UTF-16LE + NUL）。 */
    static void appendWideStringProp(PacketWriter w, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        w.u32(bytes.length + 2).raw(bytes).u16(0);
    }
}
