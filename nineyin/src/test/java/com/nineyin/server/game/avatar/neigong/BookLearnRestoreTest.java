package com.nineyin.server.game.avatar.neigong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nineyin.util.ServerPaths;

/**
 * 秘籍学习成果重登保持（2026-11-05 无妄神功书链实丢回归）：
 * restore 旧逻辑只在 newPlayerProgress 种子书表里找书，后学内功
 * book()==null 被静默跳过——学的内功、抬的修炼上限、curNeiGong 运功态
 * 重登全丢。验：学书→snapshot→新 PlayerProgress restore 书/上限/运功态
 * 全保持；FacultyStore JSON 往返 max_level 不丢；旧档六字段（无 maxLevel）
 * 保目录默认。自建最小 share 根（StaticData=1060 区间 1..45），不依赖
 * go-server1 资源（GO_ROOT varprop 缺 1060 会静默跳过的坑）。
 */
class BookLearnRestoreTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    /** 无妄神功（用户实学书链，不在 starter 种子表）；StaticData=1060 varprop 区间 1..45。 */
    private static final String LEARNED = "ng_jh_201";

    private Path previousRoot;

    @AfterEach
    void restoreRoot() {
        if (previousRoot != null) {
            ServerPaths.overrideServerRoot(previousRoot);
            previousRoot = null;
        }
    }

    /** 最小 share：neigong_static(1060 区间) + neigong.ini(QName) + wuxuelevelinfo(首级曲线)。 */
    private void overrideShareRoot(Path dir) throws IOException {
        // ServerPaths.projectPath("resources","modern","share") 前缀不可少——目录读盘根。
        Path share = dir.resolve(Path.of("resources", "modern", "share"));
        Files.createDirectories(share.resolve(Path.of("skill", "neigong")));
        Files.createDirectories(share.resolve("faculty"));
        Files.writeString(share.resolve(Path.of("skill", "neigong", "neigong_static.ini")),
                "[1060]\nMinVarPropNo=1\nMaxVarPropNo=45\n");
        Files.writeString(share.resolve(Path.of("skill", "neigong", "neigong.ini")),
                "[mind_ng_jh_201]\nItemType=1002\nQName=ng_jh_201\nStaticData=1060\n");
        Files.writeString(share.resolve(Path.of("faculty", "wuxuelevelinfo.ini")),
                "[ng_jh_201]\nr=1,700,120\n");
        previousRoot = ServerPaths.serverRoot();
        ServerPaths.overrideServerRoot(dir);
    }

    @Test
    void learnedBookSurvivesRestoreWithCapAndCurNeiGong(@TempDir Path dir) throws IOException {
        overrideShareRoot(dir);
        NeiGongCatalog.NeiGongDef def = NeiGongCatalog.load().book(LEARNED);
        assertNotNull(def, "最小 share 须含 " + LEARNED);

        PlayerProgress learned = PlayerProgress.create(Clock.systemUTC());
        assertNull(learned.book(LEARNED), LEARNED + " 不在种子表（前提）");
        assertTrue(learned.learnOrRaiseBook(def, 9, 0), "入门书建书");
        assertEquals(9, learned.book(LEARNED).maxLevel, "入门后上限=9");
        assertTrue(learned.learnOrRaiseBook(def, 45, 0), "篇章书抬上限");
        assertEquals(45, learned.book(LEARNED).maxLevel, "篇章后上限=45");

        PlayerProgress.Snapshot snapshot = learned.snapshot();
        // 运功态/修炼名指向后学书（旧契约构造同 Go JSON 侧）。
        snapshot = new PlayerProgress.Snapshot(LEARNED, snapshot.faculty(),
                snapshot.facultyState(), snapshot.facultyStyle(), LEARNED,
                snapshot.fillSpeed(), snapshot.lastAdvanceUtc(), snapshot.books());

        PlayerProgress relogin = PlayerProgress.create(Clock.systemUTC());
        relogin.restore(snapshot, NOW);

        PlayerProgress.Book restored = relogin.book(LEARNED);
        assertNotNull(restored, "后学内功重登必须还在（旧逻辑此处丢书）");
        assertEquals(45, restored.maxLevel, "书链抬升的修炼上限不回退");
        assertEquals(learned.book(LEARNED).level, restored.level);
        assertEquals(learned.book(LEARNED).fill, restored.fill);
        assertEquals(learned.book(LEARNED).total, restored.total);
        assertEquals(LEARNED, relogin.curNeiGong, "运功态恢复（旧逻辑因查不到书不恢复）");
        assertEquals(LEARNED, relogin.facultyName(), "修炼名恢复");
    }

    @Test
    void facultyStoreRoundTripKeepsMaxLevel(@TempDir Path dir) throws IOException {
        overrideShareRoot(dir);
        PlayerProgress learned = PlayerProgress.create(Clock.systemUTC());
        NeiGongCatalog.NeiGongDef def = NeiGongCatalog.load().book(LEARNED);
        assertNotNull(def, "最小 share 须含 " + LEARNED);
        learned.learnOrRaiseBook(def, 45, 0);

        Path file = dir.resolve("faculty.json");
        FacultyStore.open(file).save(618L, learned.snapshot());
        PlayerProgress.Snapshot reloaded = FacultyStore.open(file).load(618L);
        assertNotNull(reloaded);
        PlayerProgress.BookSnapshot book = null;
        for (PlayerProgress.BookSnapshot item : reloaded.books()) {
            if (LEARNED.equals(item.configId())) {
                book = item;
            }
        }
        assertNotNull(book, "JSON 往返后学书仍在");
        assertEquals(45, book.maxLevel(), "max_level 序列化往返不丢");
        // 旧档六字段构造（无 maxLevel）= 0 → restore 保目录默认。
        assertEquals(0, new PlayerProgress.BookSnapshot(LEARNED, 1, 0, 700, 120, 120).maxLevel());
    }

    @Test
    void oldArchiveWithoutMaxLevelKeepsCatalogDefault(@TempDir Path dir) throws IOException {
        overrideShareRoot(dir);
        NeiGongCatalog.NeiGongDef def = NeiGongCatalog.load().book(LEARNED);
        assertNotNull(def, "最小 share 须含 " + LEARNED);

        java.util.List<PlayerProgress.BookSnapshot> oldStyle = new ArrayList<>();
        oldStyle.add(new PlayerProgress.BookSnapshot(LEARNED, 2, 100, 1500, 160, 160));
        PlayerProgress.Snapshot old = new PlayerProgress.Snapshot(
                "", 0, 0, 0, "", 200, NOW, oldStyle);

        PlayerProgress relogin = PlayerProgress.create(Clock.systemUTC());
        relogin.restore(old, NOW);
        PlayerProgress.Book restored = relogin.book(LEARNED);
        assertNotNull(restored, "旧档书也须建书恢复");
        assertEquals(def.maxLevel(), restored.maxLevel, "无 max_level 旧档保目录默认上限");
        assertEquals(2, restored.level);
    }
}
