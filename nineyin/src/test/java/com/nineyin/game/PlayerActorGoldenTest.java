package com.nineyin.game;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nineyin.game.avatar.PlayerActor;
import com.nineyin.game.avatar.RoleVisual;
import com.nineyin.game.avatar.neigong.PlayerProgress;
import com.nineyin.game.avatar.equipment.EquipmentResolver;
import com.nineyin.game.avatar.neigong.NeiGongCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.role.RoleStoreFacade;

/**
 * 与 Go golden_dump_test.go 相同固定输入：PlayerActor 登录时刻 0x0D/0x10 帧逐字节一致。
 *
 * <p>golden 构造：buff 激活于固定过去时刻 GOLDEN_NOW（restoreFaculty 第二参），帧构造时
 * 的读取时刻是真实墙钟（Go time.Now()）——因此 golden 里内功 Buff 已过期、BufferListStr
 * 为空。Java 侧用系统时钟 actor + 固定 restore 时刻复现同一语义。
 */
class PlayerActorGoldenTest {

    private static final Path GO_SERVER_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");
    private static final Instant GOLDEN_NOW = Instant.parse("2026-01-02T15:04:05Z");

    private static final List<String> CUSTOM_APPEARANCE = List.of(
            "book1", "icon\\players\\boy01_create.png",
            "333333333333333333333333333333333333333333X38\u0001", "",
            "obj\\char\\b_hair\\b_hair2", "cloth_b0002", "pants_b0002", "shoes_b0002",
            "force_wudang");

    private static RoleVisual visual;

    @BeforeAll
    static void prepare() {
        Assumptions.assumeTrue(
                Files.isDirectory(GO_SERVER_ROOT.resolve("resources/modern/share/item")),
                "Go 仓库 share 资源不可用，跳过 actor golden");
        ServerPaths.overrideServerRoot(GO_SERVER_ROOT);
        visual = RoleVisual.resolve(CUSTOM_APPEARANCE, EquipmentResolver.loadDefault());
    }

    private static byte[] golden(String name) throws Exception {
        try (InputStream in = PlayerActorGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            Objects.requireNonNull(in, "missing golden resource: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void defaultProgressAddObjectAndVital() throws Exception {
        PlayerActor player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
        PlayerProgress.Snapshot snapshot = withLastAdvance(player.facultySnapshot(), GOLDEN_NOW.minusSeconds(3600));
        player.restoreFaculty(snapshot, GOLDEN_NOW);

        assertArrayEquals(golden("player_add_object_default.bin"),
                player.addObject(RoleStoreFacade.defaultRoleLocation().position(), visual));
        assertArrayEquals(golden("vital_update_default.bin"), player.vitalUpdate());
    }

    @Test
    void progressViewFramesMatchGolden() throws Exception {
        // 与 golden_dump_test.go readyPlayer 相同固定恢复：View 43 容器 + 6 书帧逐字节。
        PlayerActor player = PlayerActor.create("李逍遥", 99999, Clock.systemUTC());
        PlayerProgress.Snapshot snapshot = withLastAdvance(player.facultySnapshot(), GOLDEN_NOW.minusSeconds(3600));
        player.restoreFaculty(snapshot, GOLDEN_NOW);
        List<byte[]> frames = player.progress().viewFrames();
        assertEquals(7, frames.size());
        assertArrayEquals(golden("view43_create.bin"), frames.get(0));
        assertArrayEquals(golden("view43_book_1.bin"), frames.get(1));
        assertArrayEquals(golden("view43_book_last.bin"), frames.get(6));
    }

    @Test
    void customFacultyAddObjectAndVital() throws Exception {
        PlayerActor player = PlayerActor.create("测试角色", 5000, Clock.systemUTC());
        PlayerProgress.Snapshot base = player.facultySnapshot();
        List<PlayerProgress.BookSnapshot> books = new ArrayList<>();
        for (PlayerProgress.BookSnapshot book : base.books()) {
            if (book.configId().equals("ng_yh_001")) {
                books.add(new PlayerProgress.BookSnapshot(book.configId(), 31, 999, 810630, 5000, 8180));
            } else {
                books.add(book);
            }
        }
        PlayerProgress.Snapshot snapshot = new PlayerProgress.Snapshot(
                "ng_yh_001", 123456, PlayerProgress.FACULTY_STATE_CONVERT, PlayerProgress.FACULTY_STYLE_NORMAL,
                "ng_yh_001", base.fillSpeed(), GOLDEN_NOW.minusSeconds(90 * 60), books);
        player.restoreFaculty(snapshot, GOLDEN_NOW);

        assertArrayEquals(golden("player_add_object_custom.bin"),
                player.addObject(RoleStoreFacade.defaultRoleLocation().position(), visual));
        assertArrayEquals(golden("vital_update_custom.bin"), player.vitalUpdate());
    }

    private static PlayerProgress.Snapshot withLastAdvance(PlayerProgress.Snapshot snapshot, Instant value) {
        return new PlayerProgress.Snapshot(snapshot.curNeiGong(), snapshot.faculty(), snapshot.facultyState(),
                snapshot.facultyStyle(), snapshot.facultyName(), snapshot.fillSpeed(), value, snapshot.books());
    }

    @Test
    void modernCatalogLoadedFromResources() {
        NeiGongCatalog.Effect effect = NeiGongCatalog.load().effect(5, 30);
        assertEquals("buf_ng_wd_001", effect.buffId());
        assertNotNull(effect.stats().get("MaxHPAdd"));
    }
}
