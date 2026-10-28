package com.nineyin.protocol;

import java.nio.charset.Charset;

/**
 * 线上窄字符串编码：客户端按本地 ANSI（GBK）解释窄串（TVarList type 6、
 * string 型属性、记录格 string 列）。原生 C++/Go 侧对窄串按 GBK 原样字节
 * 透传；这里统一 GBK 编解码。ASCII 子集与 UTF-8 完全一致，存量 ASCII 包
 * 字节不变。
 */
public final class FxText {

    public static final Charset GBK = Charset.forName("GBK");

    private FxText() {
    }

    public static byte[] narrow(String s) {
        return s.getBytes(GBK);
    }

    public static String narrow(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, GBK);
    }
}
