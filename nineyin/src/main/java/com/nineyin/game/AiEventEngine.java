package com.nineyin.game;

import com.nineyin.login.LoginFlow;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.rule.AiRuleData;
import com.nineyin.rule.AiRuleData.AiEvent;
import com.nineyin.rule.AiRuleData.AiPackage;
import com.nineyin.rule.BuffCatalog;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.NpcTemplate;
import com.nineyin.scene.NpcTemplateRepository;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import com.nineyin.server.GameScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * AI 事件引擎（数据驱动，规则来自 AiRuleData 四表）：
 * 挂 pump（每场景 1s）驱动 EventType 1=出生 / 4=周期 / 6=战斗中；
 * 战斗管线 hp=0 时回调 onNpcDeath 驱动 EventType=3（死亡掉宝箱矩阵），
 * 受击时回调 onNpcDamaged 记录仇恨目标。
 *
 * 本切片已执行 OpEvent：2=加 buff（记录到 NpcInstance.buffs）、19=设属性
 * （BornAction）、25=召唤 NPC/宝箱（动态 oid + 广播）、23/24=立刻对仇恨目标
 * 释放 Para1 技能。BOSS 出招循环按 table@SkillRec 轮转近似（原逻辑在丢失
 * 的服务器 Lua）：拼音后缀大招（如 sxks/byft）伤害加倍并触发嗜血回血。
 */
public final class AiEventEngine {

    private static final Logger log = LoggerFactory.getLogger(AiEventEngine.class);

    /**
     * BOSS → AI 包绑定。原映射在丢失的服务器 Lua（npc\clone\clone003\
     * bossclone003xxx.lua 引用包），按包 Text 备注（"暮色之村X（新）"/【挑战】）复原；
     * 黑白双煞真名为裘云/郭刚（拼音 qy/gg 即技能 ID 前缀）。
     */
    private static final Map<String, Integer> BOSS_PACKAGES = Map.ofEntries(
            Map.entry("BossClone003001", 4399),   // 周厉风（新）
            Map.entry("BossClone003002", 4400),   // 魏不羁（新）
            Map.entry("BossClone003003", 4401),   // 钱昔之（新）
            Map.entry("BossClone003004", 4402),   // 叶楼凯（新）
            Map.entry("BossClone003005", 4407),   // 韩似锦（新）
            Map.entry("BossClone003006", 4406),   // 赵子亟（新）
            Map.entry("BossClone003007", 4403),   // 黑白双煞之白＝裘云（新）
            Map.entry("BossClone003008", 4404),   // 黑白双煞之黑＝郭刚（新）
            Map.entry("BossCloneTZ003001", 5976), // 周厉风【挑战】
            Map.entry("BossCloneTZ003002", 5977), // 魏不羁【挑战】
            Map.entry("BossCloneTZ003003", 5978), // 钱昔之【挑战】
            Map.entry("BossCloneTZ003004", 5979), // 叶楼凯【挑战】
            Map.entry("BossCloneTZ003005", 5980)); // 韩似锦【挑战】

    /** 动态生成 NPC 已入世界：向该场景全部会话广播 0x0D + 0x1F。 */
    public interface Broadcaster {
        void onSpawned(SceneWorld world, NpcInstance n);

        /** BOSS 出招动作帧广播给同场景会话（含目标）。 */
        default void onBossAction(SceneWorld world, byte[] frame) {
        }
    }

    /** 每场景（世界对象为键，resetWorld 重建后自动换新状态）的 AI 状态。 */
    private static final class WorldState {
        long lastPumpMs;
        final Map<Integer, NpcState> npcs = new ConcurrentHashMap<>();
    }

    private static final class NpcState {
        volatile boolean bornFired;
        FxSession lastAttacker;
        long lastAttackMs;
        int rotationIdx;
        final Map<Integer, Long> lastFired = new ConcurrentHashMap<>();
    }

    private static final long PUMP_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long EVENT_REFIRE_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long COMBAT_WINDOW_MS = TimeUnit.SECONDS.toMillis(8);
    /** 出招间隔与伤害近似（原值在丢失 Lua；普通技 120 / 拼音大招 260）。 */
    private static final long ATTACK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(3);
    private static final int BOSS_ATTACK_DAMAGE = 120;
    private static final int BIG_SKILL_DAMAGE = 260;
    /** 嗜血被动：大招命中回 1000（WushuWiki：被格挡也回）。 */
    private static final int XIXUE_HEAL = 1000;
    private static final long PLAYER_REVIVE_MS = TimeUnit.SECONDS.toMillis(8);

    private final AiRuleData rules;
    private final NpcTemplateRepository templates;
    private final BuffCatalog buffs;
    private final Map<SceneWorld, WorldState> worlds =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private volatile Broadcaster broadcaster = (w, n) -> {
    };

    public AiEventEngine(AiRuleData rules, NpcTemplateRepository templates) {
        this(rules, templates, null);
    }

    public AiEventEngine(AiRuleData rules, NpcTemplateRepository templates, BuffCatalog buffs) {
        this.rules = rules;
        this.templates = templates;
        this.buffs = buffs;
    }

    public void setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /** BOSS 配置 ID 是否有绑定的 AI 包。 */
    public static boolean isBoundBoss(String configId) {
        return BOSS_PACKAGES.containsKey(configId);
    }

    /** 战斗管线回调：NPC 血量归零瞬间触发 EventType=3（playerLevel 未知传 0）。 */
    public void onNpcDeath(SceneWorld world, NpcInstance npc, int playerLevel) {
        AiPackage pkg = packageOf(npc);
        if (pkg == null) {
            return;
        }
        for (int eid : pkg.eventIds()) {
            AiEvent ev = rules.event(eid);
            if (ev == null || ev.eventType() != 3) {
                continue;
            }
            if (ev.selfCondition() == 0 || evaluate(ev.selfCondition(), world, npc, playerLevel)) {
                fire(ev, world, npc);
            }
        }
    }

    /** 战斗管线回调：NPC 受击（仇恨目标 = 攻击者会话）。 */
    public void onNpcDamaged(SceneWorld world, NpcInstance npc, FxSession attacker, long now) {
        npc.lastCombatMs = now;
        if (!isBoundBoss(npc.configId)) {
            return;
        }
        WorldState ws = worlds.computeIfAbsent(world, k -> new WorldState());
        ws.npcs.computeIfAbsent(npc.objectId, k -> new NpcState()).lastAttacker = attacker;
    }

    /** 每场景秒级泵：出生（含重生后再出生）、周期、战斗中事件、出招循环。 */
    public void pump(SceneWorld world, long now) {
        WorldState ws = worlds.computeIfAbsent(world, k -> new WorldState());
        synchronized (ws) {
            if (now - ws.lastPumpMs < PUMP_INTERVAL_MS) {
                return;
            }
            ws.lastPumpMs = now;
        }
        synchronized (world) {
            for (NpcInstance n : world.npcs) {
                AiPackage pkg = packageOf(n);
                if (pkg == null) {
                    continue;
                }
                NpcState ns = ws.npcs.computeIfAbsent(n.objectId, k -> new NpcState());
                if (n.dead) {
                    ns.bornFired = false;   // 重生后重新走出生事件
                    continue;
                }
                if (!ns.bornFired) {
                    ns.bornFired = true;
                    fireTyped(pkg, 1, world, n, 0, now, ns, 0);
                }
                fireTyped(pkg, 4, world, n, 0, now, ns, EVENT_REFIRE_MS);
                if (now - n.lastCombatMs < COMBAT_WINDOW_MS) {
                    regenTick(n, ns);
                    fireTyped(pkg, 6, world, n, 0, now, ns, EVENT_REFIRE_MS);
                    bossAttack(world, n, ns, now);
                }
            }
        }
    }

    private void fireTyped(AiPackage pkg, int eventType, SceneWorld world, NpcInstance npc,
                           int playerLevel, long now, NpcState ns, long refireMs) {
        for (int eid : pkg.eventIds()) {
            AiEvent ev = rules.event(eid);
            if (ev == null || ev.eventType() != eventType) {
                continue;
            }
            Long last = ns.lastFired.get(eid);
            if (last != null && now - last < refireMs) {
                continue;
            }
            if (ev.selfCondition() != 0 && !evaluate(ev.selfCondition(), world, npc, playerLevel)) {
                continue;
            }
            if (refireMs > 0) {
                ns.lastFired.put(eid, now);
            }
            fire(ev, world, npc);
        }
    }

    private AiPackage packageOf(NpcInstance npc) {
        Integer pkgId = BOSS_PACKAGES.get(npc.configId);
        return pkgId != null ? rules.pkg(pkgId) : null;
    }

    private void fire(AiEvent ev, SceneWorld world, NpcInstance npc) {
        if (ev.opRate() < 100 && ThreadLocalRandom.current().nextInt(100) >= ev.opRate()) {
            return;
        }
        switch (ev.opEvent()) {
            case 2 -> {
                if (!ev.para1().isEmpty()) {
                    npc.buffs.add(ev.para1());
                    log.info("ai buff {} (static={}) -> {} ({})", ev.para1(),
                            buffs != null ? buffs.staticDataOf(ev.para1()) : 0,
                            npc.configId, npc.objectId);
                }
            }
            case 19 -> {
                if ("BornAction".equals(ev.para1()) && !ev.para2().isEmpty()) {
                    npc.bornAction = ev.para2();
                } else {
                    log.debug("ai set-prop {}={} skipped npc={}", ev.para1(), ev.para2(), npc.configId);
                }
            }
            case 25 -> spawnDynamic(world, npc, ev.para1());
            case 23, 24 -> {
                // 技能使用/切换（原逻辑在丢失 Lua）：近似为立刻对仇恨目标释放
                if (!ev.para1().isEmpty()) {
                    WorldState ws = worlds.get(world);
                    NpcState ns = ws != null ? ws.npcs.get(npc.objectId) : null;
                    castSkill(world, npc, ns != null ? ns : new NpcState(), ev.para1(),
                            isBigSkill(ev.para1()) ? BIG_SKILL_DAMAGE : BOSS_ATTACK_DAMAGE);
                }
            }
            case 27, 29 -> log.info("ai talk npc={} line={} difficulty={}",
                    npc.configId, ev.para1(), world.dungeonDifficulty);
            default -> log.debug("ai op {} not implemented (para1={}) npc={}",
                    ev.opEvent(), ev.para1(), npc.configId);
        }
    }

    /** OpEvent=25：在源 NPC 位置动态生成（宝箱/召唤物），经广播器下发全场景会话。 */    private void spawnDynamic(SceneWorld world, NpcInstance src, String configId) {
        if (configId == null || configId.isEmpty()) {
            return;
        }
        NpcInstance n = new NpcInstance();
        n.configId = configId;
        n.x = src.x;
        n.y = src.y;
        n.z = src.z;
        n.orient = src.orient;
        n.homeX = src.x;
        n.homeY = src.y;
        n.homeZ = src.z;
        n.tag = "ai_spawn";
        NpcTemplate tmpl = templates.lookup(configId);
        n.maxHp = tmpl.maxHp() > 0 ? tmpl.maxHp() : 1000;
        n.hp = n.maxHp;
        String script = tmpl.scriptClass().toLowerCase(Locale.ROOT);
        n.monster = script.contains("attack") || script.contains("boss");
        world.addDynamic(n);
        broadcaster.onSpawned(world, n);
        log.info("ai spawn {} (oid={}) at ({},{},{}) difficulty={} rating={}",
                configId, n.objectId, n.x, n.y, n.z, world.dungeonDifficulty, world.dungeonRating);
    }

    // ---------- 出招循环（近似：原服务器 Lua 已丢失） ----------

    /** 回血类 buff（HPMP）战斗中每秒 2% 上限内回复（近似值）。 */
    private void regenTick(NpcInstance n, NpcState ns) {
        if (n.hp >= n.maxHp || n.buffs.stream().noneMatch(b -> b.contains("HPMP"))) {
            return;
        }
        n.hp = Math.min(n.maxHp, n.hp + Math.max(1, n.maxHp / 50));
        if (ns.lastAttacker != null) {
            sendBossHp(ns.lastAttacker, n);
        }
    }

    /** 出招：按 table@SkillRec 轮转；拼音后缀大招（sxks/byft 等）伤害加倍。 */
    private void bossAttack(SceneWorld world, NpcInstance boss, NpcState ns, long now) {
        if (now - ns.lastAttackMs < ATTACK_INTERVAL_MS) {
            return;
        }
        List<NpcTemplate.SkillRef> skills = templates.lookup(boss.configId).skills();
        if (skills.isEmpty()) {
            return;
        }
        NpcTemplate.SkillRef ref = skills.get(ns.rotationIdx++ % skills.size());
        castSkill(world, boss, ns, ref.id(), isBigSkill(ref.id()) ? BIG_SKILL_DAMAGE : BOSS_ATTACK_DAMAGE);
        ns.lastAttackMs = now;
    }

    /** 对仇恨目标释放技能：动作帧广播 + 玩家伤害 + 嗜血回血 + 死亡缓复活。 */
    private void castSkill(SceneWorld world, NpcInstance boss, NpcState ns, String skillId, int damage) {
        FxSession target = ns.lastAttacker;
        if (target == null) {
            return;
        }
        LoginSession login = target.channel().attr(LoginFlow.SESSION).get();
        if (login == null || login.actor == null || login.actor.hp <= 0) {
            return;
        }
        if (world != null) {
            broadcaster.onBossAction(world, SceneFlow.nativeSkillAction(boss.objectId, boss.ownerId,
                    skillId, S2CPackets.PLAYER_OBJECT_ID, S2CPackets.PLAYER_OWNER_ID));
        }
        boolean justDied = login.actor.applyDamage(damage);
        target.send(login.actor.vitalUpdate());
        log.info("boss {} cast {} -> {} damage={} hp={}", boss.configId, skillId,
                login.roleName, damage, login.actor.hp);
        if (isBigSkill(skillId) && boss.buffs.stream().anyMatch(b -> b.contains("xixue"))) {
            boss.hp = Math.min(boss.maxHp, boss.hp + XIXUE_HEAL);
            sendBossHp(target, boss);
        }
        if (justDied) {
            log.info("player {} defeated by {} (skill {}), revive in {}ms",
                    login.roleName, boss.configId, skillId, PLAYER_REVIVE_MS);
            GameScheduler.schedule(PLAYER_REVIVE_MS, () -> {
                login.actor.revive();
                target.send(login.actor.vitalUpdate());
            });
        }
    }

    /** BOSS 血量同步给攻击者（0x10 批量，npc oid）。 */
    private void sendBossHp(FxSession fx, NpcInstance n) {
        fx.send(S2CPackets.batch(n.objectId, n.ownerId)
                .i32(PropertyId.HP, n.hp)
                .i32(PropertyId.HP_RATIO, n.maxHp > 0 ? (int) ((long) n.hp * 100 / n.maxHp) : 0)
                .build());
    }

    /** 拼音后缀 = 命名大招（clone003_zhoulifeng_sxks）；数字后缀 = 常规轮转技（..._01）。 */
    static boolean isBigSkill(String skillId) {
        String low = skillId.toLowerCase(Locale.ROOT);
        int us = low.lastIndexOf('_');
        if (us < 0) {
            return false;
        }
        for (int i = us + 1; i < low.length(); i++) {
            if (!Character.isDigit(low.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ---------- 条件求值 ----------

    /** 条件/公式求值；公式从左到右无优先级（与原表 & | 混排一致）。 */
    boolean evaluate(int condId, SceneWorld world, NpcInstance npc, int playerLevel) {
        AiRuleData.AiFormula f = rules.formula(condId);
        if (f != null) {
            boolean acc = true;
            boolean have = false;
            String op = "&";
            for (String tok : f.tokens()) {
                if (tok.equals("&") || tok.equals("|")) {
                    op = tok;
                    continue;
                }
                boolean v = evaluate(Integer.parseInt(tok), world, npc, playerLevel);
                acc = !have ? v : ("&".equals(op) ? acc && v : acc || v);
                have = true;
            }
            return acc;
        }
        AiRuleData.AiCondition c = rules.condition(condId);
        if (c == null) {
            return false;   // 未定义条件：保守不触发
        }
        boolean r = switch (c.name()) {
            case "HPRatio" -> inRange(hpRatio(npc), c.min(), c.max());
            case "MPRatio" -> inRange(100, c.min(), c.max());   // NPC 未建模内力，按满蓝
            case "Level" -> inRange(playerLevel, c.min(), c.max());
            case "BufferContainer" -> npc.buffs.contains(c.para1());
            case "Func" -> evalFunc(c, world);
            default -> false;
        };
        return c.not() ? !r : r;
    }

    /** Func(8,N)=副本难度档（1普通/2困难/3地狱/4修罗），Func(22,N)=通关评价档。 */
    private boolean evalFunc(AiRuleData.AiCondition c, SceneWorld world) {
        int p1 = parseIntOr(c.para1());
        int p2 = parseIntOr(c.para2());
        return switch (p1) {
            case 8 -> world.dungeonDifficulty == p2;
            case 22 -> world.dungeonRating == p2;
            default -> false;
        };
    }

    private static int hpRatio(NpcInstance npc) {
        return npc.maxHp > 0 ? (int) ((long) npc.hp * 100 / npc.maxHp) : 100;
    }

    private static boolean inRange(int value, Integer min, Integer max) {
        if (min != null && value < min) {
            return false;
        }
        return max == null || value <= max;
    }

    private static int parseIntOr(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 测试辅助：某包某类事件列表。 */
    List<AiEvent> eventsOf(AiPackage pkg, int eventType) {
        return pkg.eventIds().stream()
                .map(rules::event)
                .filter(ev -> ev != null && ev.eventType() == eventType)
                .toList();
    }
}
