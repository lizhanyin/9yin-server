package com.nineyin.server.game.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nineyin.server.world.fight.CombatSkillCatalog;

/**
 * View40 技能行射程族官方对拍（2026-11-05「最大射程全是50」）：期望值逐条取自
 * 参考服（Go exe=官方值）抓包 127-0-0-1-63125 View40 207 行（tools/smoke/
 * view40_range_probe.py）。TargetShapePara2 即客户端「最大射程」显示与施法限距源。
 */
class SkillRowRangeTest {

    private static final Path GO_ROOT = Path.of("G:/pojie/ai/9yin-go-server1");

    @BeforeAll
    static void setUpRoot() {
        assumeTrue(Files.isRegularFile(
                GO_ROOT.resolve(Path.of("resources", "modern", "share", "skill",
                        "attack_targetshape.ini"))),
                "go-server1 skill 资源不可用，跳过射程对拍");
        com.nineyin.util.ServerPaths.overrideServerRoot(GO_ROOT);
    }

    @AfterAll
    static void restoreRoot() {
        com.nineyin.util.ServerPaths.overrideServerRoot(
                Path.of("G:/pojie/ai/9yin-server"));
    }

    /**
     * (configId, 官方 TargetShapePara2, 规则 HitShapePara2；0=不下发)。
     * HitShapePara2 取抓包规则值（HitShape 类型&gt;0 才发）：chz03 官方发 15 属
     * 表版本出入孤例（htype=0 规则外），按规则断 0。
     */
    private static final Object[][] EXPECTED = {
            {"CS_by_xhmdl01", 20.0f, 20.0f},
            {"CS_by_xhmdl02", 6.0f, 0.0f},
            {"CS_by_xhmdl04", 8.0f, 8.0f},
            {"CS_by_xhmdl05", 5.0f, 5.0f},
            {"CS_by_xhmdl08", 6.0f, 6.0f},
            {"CS_jh_chz03", 15.0f, 0.0f},
            {"CS_jh_chz05", 25.0f, 0.0f},
            {"CS_jh_cqgf01", 3.0f, 0.0f},
            {"CS_jh_dgjj01", 3.0f, 0.0f},
            {"CS_jh_dgjj04", 8.0f, 8.0f},
            {"CS_jh_dgjj06", 20.0f, 0.0f},
            {"CS_jh_jysg09", 4.0f, 4.0f},
            // sfgp07：本地表 HitShapePkg=17(圆20) 按规则发 20；官方抓包该行未发（表版本出入孤例）
            {"CS_jh_sfgp07", 20.0f, 20.0f},
    };

    @Test
    void targetShapePara2MatchesOfficialCapture() {
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        for (Object[] row : EXPECTED) {
            String id = (String) row[0];
            CombatSkillCatalog.SkillRowData data = catalog.skillRowData(id, 1);
            assertNotNull(data, id + " 应可编译出行数据");
            assertEquals((float) row[1], data.targetShapePara2(), 0.001f,
                    id + " TargetShapePara2(最大射程)");
            assertEquals((float) row[2], data.hitShapePara2(), 0.001f,
                    id + " HitShapePara2");
        }
    }

    /** 官方从不发 TargetShapePara1（207 行 0 次）：补集产物应为 0 或不影响下发。 */
    @Test
    void varPropNoPresentForOfficialRows() {
        CombatSkillCatalog catalog = CombatSkillCatalog.load();
        for (Object[] row : EXPECTED) {
            CombatSkillCatalog.SkillRowData data = catalog.skillRowData((String) row[0], 1);
            assertNotNull(data, row[0] + " 行数据");
            assumeTrue(data.varPropNo() > 0, row[0] + " varprop 行号应 >0（官方 207/207 全发）");
        }
    }
}
