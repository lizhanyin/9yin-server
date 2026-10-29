package com.nineyin.game;

import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.MenuPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.NpcTemplateRepository;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import com.nineyin.server.GameScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 副本宝箱开启（boxnpc：BOSS 死亡由 AI 事件 OpEvent=25 掉落）：
 * 点击出"打开宝箱"菜单 → 距离校验（OpenRange）→ 播放开箱动作（OpenAction）
 * → DelayTime 读条 → 移除宝箱 + 掉落 worldInfo（掉落池按攻略近似，
 * 真实掉落组合表 comb_PassDrop_* 被引用未定义、已丢失）。
 */
public final class CloneLoot {

    private static final Logger log = LoggerFactory.getLogger(CloneLoot.class);

    public static final int MARK_OPEN_BOX = 0x2103;
    public static final int FUNC_OPEN_BOX = MenuPackets.funcIdForMark(MARK_OPEN_BOX);

    /** 掉落池（17173/蝶梦攻略掉落清单近似）：珍稀 / 常规。 */
    private static final List<String> RARE = List.of("上天梯", "雁行功", "三才阵秘籍",
            "门派一阶内功书页", "天魔追魂刀残卷", "铜制装备图谱", "铁制装备图谱");
    private static final List<String> COMMON = List.of("寒鸦草", "粗铁矿石", "细麻布",
            "黄铜矿石", "止血散", "碎银");

    /** 测试注入：覆盖表内 DelayTime。 */
    static volatile long delayOverrideMs = -1;

    private CloneLoot() {
    }

    /** boxnpc（含 BossBox*）→ 打开宝箱菜单 mark。 */
    static Integer menuMarkFor(NpcInstance npc, NpcTemplateRepository templates) {
        if (npc == null || npc.dead) {
            return null;
        }
        String script = templates.lookup(npc.configId).scriptClass();
        return "boxnpc".equalsIgnoreCase(script) ? MARK_OPEN_BOX : null;
    }

    /** 0x07 菜单动作分发；非开箱 func 返回 false。 */
    static boolean handleMenuAction(FxSession fx, LoginSession session, SceneFlow.SceneState st,
                                    SceneManager sceneManager, int oid, int func) {
        if (func != FUNC_OPEN_BOX) {
            return false;
        }
        fx.send(CustomPackets.cinematicClose());
        SceneWorld world = st.world;
        NpcInstance box = world != null ? world.npcById(oid) : null;
        if (box == null || box.dead) {
            return true;
        }
        NpcTemplate tmpl = sceneManager.templates().lookup(box.configId);
        NpcTemplate.BoxInfo info = tmpl.box();
        float range = info != null && info.openRange() > 0 ? info.openRange() : 3f;
        float dx = session.x - box.x;
        float dz = session.z - box.z;
        if (dx * dx + dz * dz > range * range) {
            fx.send(S2CPackets.worldInfo(0, "需要离宝箱更近一些"));
            log.info("open box rejected (out of range {}m) npc={}", range, box.configId);
            return true;
        }
        String action = info != null ? info.openAction() : "interact041";
        long delay = CloneLoot.delayOverrideMs >= 0 ? delayOverrideMs
                : (info != null ? info.delayMs() : 5000L);
        fx.send(CustomPackets.nativeAction(1, 1, action));
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, action));
        int ownerId = box.ownerId;
        String loot = rollLoot(box.configId, tmpl.name(), world);
        GameScheduler.schedule(delay, () -> {
            if (world.npcById(oid) == null) {
                return;   // 已被其他会话开启
            }
            world.removeNpc(oid);
            st.sentIds.remove(oid);
            fx.send(S2CPackets.removeObject(oid, ownerId));
            fx.send(S2CPackets.worldInfo(0, loot));
            fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, "stand"));
            log.info("boss box opened npc={} loot={}", box.configId, loot);
        });
        return true;
    }

    /**
     * 掉落掷取：评价档（configId 尾字母 A/B/C → 甲/乙/丙）决定件数与珍稀数；
     * 地狱/修罗难度 +1 件。
     */
    static String rollLoot(String configId, String bossName, SceneWorld world) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int grade = switch (configId.isEmpty() ? 'C' : Character.toUpperCase(
                configId.charAt(configId.length() - 1))) {
            case 'A' -> 3;
            case 'B' -> 2;
            default -> 1;
        };
        int difficulty = world != null ? world.dungeonDifficulty : 1;
        int count = grade + (difficulty >= 3 ? 1 : 0);
        int rares = grade >= 3 ? 2 : grade == 2 ? 1 : (r.nextInt(100) < 30 ? 1 : 0);
        List<String> got = new ArrayList<>();
        for (int i = 0; i < rares && i < count; i++) {
            got.add(RARE.get(r.nextInt(RARE.size())));
        }
        while (got.size() < count) {
            got.add(COMMON.get(r.nextInt(COMMON.size())));
        }
        String boxName = bossName.isEmpty() ? configId : bossName;
        return String.format(Locale.ROOT, "打开「%s的宝箱」，获得了：%s", boxName, String.join("、", got));
    }
}
