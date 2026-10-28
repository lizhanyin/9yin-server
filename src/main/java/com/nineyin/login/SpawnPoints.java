package com.nineyin.login;

import java.util.Map;

/**
 * 剧本出生点表（来源：客户端 books.ini RelivePos + sencerelive.ini）。
 * book1/3 共用千灯镇，其余各对应一个场景。
 */
public final class SpawnPoints {

    public record SpawnPoint(String config, String resource,
                             float x, float y, float z, float orient, String label) {
    }

    private static final SpawnPoint BOOK1 = new SpawnPoint(
            "ini\\scene\\born04_QianDengZhen", "born04",
            874.933f, -32.392f, 746.688f, 0.335f, "千灯镇(book1)");
    private static final SpawnPoint BOOK2 = new SpawnPoint(
            "ini\\scene\\born03_YanYuZhuang", "born03",
            460.629f, 12.811f, 659.731f, 5.580f, "烟雨庄(book2)");
    private static final SpawnPoint BOOK4 = new SpawnPoint(
            "ini\\scene\\city05_ChengDu", "city05",
            915.753f, 31.192f, 538.193f, 6.170f, "成都(book4)");
    private static final SpawnPoint BOOK5 = new SpawnPoint(
            "ini\\scene\\born01_MoHeZhen", "born01",
            693.908f, 24.694f, 404.350f, 3.611f, "鸡鸣驿(book5)");
    private static final SpawnPoint BOOK6 = new SpawnPoint(
            "ini\\scene\\city04_LuoYang", "city04",
            1141.058f, -15.236f, 649.011f, 4.500f, "洛阳(book6)");
    private static final SpawnPoint BOOK8 = new SpawnPoint(
            "ini\\scene\\born02_ERenGU", "born02",
            905.069f, 10.810f, 196.980f, 1.610f, "恶人谷(book8)");

    private static final Map<String, SpawnPoint> BY_BOOK = Map.of(
            "book1", BOOK1,
            "book2", BOOK2,
            "book3", BOOK1,
            "book4", BOOK4,
            "book5", BOOK5,
            "book6", BOOK6,
            "book8", BOOK8);

    private SpawnPoints() {
    }

    public static SpawnPoint forBook(String book) {
        return BY_BOOK.getOrDefault(book, BOOK1);
    }
}
