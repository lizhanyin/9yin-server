package com.nineyin.tools.mpq;

/** 文件条目的数据来源。 */
enum TableSource {
    /** 经典 block table。 */
    CLASSIC,
    /** BET 表（v3+）。 */
    BET
}

/**
 * 归档内的一个文件，统一表示经典表与 BET 表两种来源。
 *
 * @param name        名字（未知时为 null，来自 (listfile)）
 * @param locale      经典 hash 条目的 locale（BET 来源恒为 0）
 * @param blockIndex  在源表中的索引
 * @param offset      相对归档起始处的数据偏移
 * @param compressedSize 压缩后大小
 * @param size        未压缩大小
 * @param flags       块标志
 * @param source      来源表
 */
public record FileEntry(String name, int locale, int blockIndex, long offset,
                        int compressedSize, int size, int flags, TableSource source) {

    // 块标志位
    static final int FILE_EXISTS = 0x8000_0000;
    static final int FILE_SINGLE_UNIT = 0x0100_0000;
    static final int FILE_DELETE_MARKER = 0x0200_0000;
    static final int FILE_SECTOR_CRC = 0x0400_0000;
    static final int FILE_SIGNATURE = 0x1000_0000;
    static final int FILE_PATCH = 0x0010_0000;
    static final int FILE_ENCRYPTED = 0x0001_0000;
    static final int FILE_KEY_V2 = 0x0002_0000;
    static final int FILE_COMPRESS = 0x0000_0200;
    static final int FILE_IMPLODE = 0x0000_0100;
    static final int FILE_COMPRESS_MASK = 0x0000_FF00;
    /** 自定义扩展（Rust 库的密码 AEAD 方案），本模块不支持。 */
    static final int FILE_PASSWORD_AEAD = 0x0800_0000;

    public boolean exists() {
        return (flags & FILE_EXISTS) != 0;
    }

    public boolean isSingleUnit() {
        return (flags & FILE_SINGLE_UNIT) != 0;
    }

    public boolean isEncrypted() {
        return (flags & FILE_ENCRYPTED) != 0;
    }

    public boolean isCompressed() {
        return (flags & FILE_COMPRESS_MASK) != 0;
    }

    /** 用于显示的名字；未知时为 &lt;unnamed #index&gt;。 */
    public String displayName() {
        return name != null ? name : "<unnamed #" + blockIndex + ">";
    }
}
