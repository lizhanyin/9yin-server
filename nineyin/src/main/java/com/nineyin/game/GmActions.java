package com.nineyin.game;

import com.nineyin.db.RoleStore;
import com.nineyin.db.StoredRole;
import com.nineyin.gm.GmCommand;
import com.nineyin.login.LoginSession;
import com.nineyin.protocol.CustomPackets;
import com.nineyin.protocol.PropertyId;
import com.nineyin.protocol.S2CPackets;
import com.nineyin.scene.NpcInstance;
import com.nineyin.scene.SceneManager;
import com.nineyin.scene.SceneWorld;
import com.nineyin.server.FxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * GM 命令服务端执行器（对齐 Go processGMCommand 的动作集）：
 * 资源（碎银/怒气/速度/重力）、瞬移/切图（含预设）、门派、协议探针
 * （NPC 气泡/章节卡/红霸体/State 动作）。kick/minimal_login 在 NineyinServer。
 */
public final class GmActions {

    private static final Logger log = LoggerFactory.getLogger(GmActions.class);

    /** 门派预设（白名单，对齐 Go gmFactionPresets）。 */
    public record FactionPreset(String label, String faction) {
    }

    public static final Map<String, FactionPreset> FACTIONS = Map.ofEntries(
            Map.entry("shaolin", new FactionPreset("少林", "school_shaolin")),
            Map.entry("wudang", new FactionPreset("武当", "school_wudang")),
            Map.entry("emei", new FactionPreset("峨眉", "school_emei")),
            Map.entry("gaibang", new FactionPreset("丐帮", "school_gaibang")),
            Map.entry("tangmen", new FactionPreset("唐门", "school_tangmen")),
            Map.entry("junzitang", new FactionPreset("君子堂", "school_junzitang")),
            Map.entry("jinyiwei", new FactionPreset("锦衣卫", "school_jinyiwei")),
            Map.entry("jilegu", new FactionPreset("极乐谷", "school_jilegu")),
            Map.entry("mingjiao", new FactionPreset("明教", "school_mingjiao")),
            Map.entry("tianshan", new FactionPreset("天山派", "school_tianshan")),
            Map.entry("kunlun", new FactionPreset("昆仑派", "school_kunlun")),
            Map.entry("xingtianbao", new FactionPreset("刑天城", "school_xingtianbao")),
            Map.entry("yihuagong", new FactionPreset("移花宫", "force_yihua")),
            Map.entry("taohuadao", new FactionPreset("桃花岛", "force_taohua")),
            Map.entry("xujia", new FactionPreset("徐家庄", "force_xujia")),
            Map.entry("wanshou", new FactionPreset("万兽山庄", "force_wanshou")),
            Map.entry("jinzhen", new FactionPreset("金针沈家", "force_jinzhen")),
            Map.entry("wugenmen", new FactionPreset("无根门", "force_wugen")),
            Map.entry("tianlun", new FactionPreset("天轮寺", "force_tianlun")),
            Map.entry("qingyi", new FactionPreset("青衣阁", "force_qingyi")),
            Map.entry("xuedao", new FactionPreset("血刀门", "newschool_xuedao")),
            Map.entry("huashan", new FactionPreset("华山派", "newschool_huashan")),
            Map.entry("gumu", new FactionPreset("古墓派", "newschool_gumu")),
            Map.entry("damo", new FactionPreset("达摩派", "newschool_damo")),
            Map.entry("shenshui", new FactionPreset("神水宫", "newschool_shenshui")),
            Map.entry("changfeng", new FactionPreset("长风镖局", "newschool_changfeng")),
            Map.entry("nianluo", new FactionPreset("念萝坝", "newschool_nianluo")),
            Map.entry("wuxian", new FactionPreset("无仙教", "newschool_wuxian")),
            Map.entry("shenji", new FactionPreset("神机营", "newschool_shenji")),
            Map.entry("xingmiao", new FactionPreset("星渺阁", "newschool_xingmiao")),
            Map.entry("tianya", new FactionPreset("天涯海阁", "newschool_tianya")),
            Map.entry("wanghui", new FactionPreset("王回洲", "newschool_wanghui")),
            Map.entry("shenjihui", new FactionPreset("神机汇", "newschool_shenjihui")));

    /** 场景预设（config,resource,x,y,z,orient；对齐 Go gmScenePresets）。 */
    public static final Map<String, String> SCENE_PRESETS = Map.ofEntries(
            Map.entry("yanjing", "ini\\scene\\city01_YanJing,city01,837.625,-35.939,-750.860,0.680"),
            Map.entry("suzhou", "ini\\scene\\city02_SuZhou,city02,438.140,0.800,1188.282,-2.325"),
            Map.entry("jinling", "ini\\scene\\city03_JinLing,city03,1675.078,10.726,-101.141,0.335"),
            Map.entry("luoyang", "ini\\scene\\city04_LuoYang,city04,243.387,43.277,403.821,5.024"),
            Map.entry("chengdu", "ini\\scene\\city05_ChengDu,city05,911.073,31.192,537.350,1.742"),
            Map.entry("jimingyi", "ini\\scene\\born01_MoHeZhen,born01,998.817,8.364,530.187,-1.498"),
            Map.entry("yanyuzhuang", "ini\\scene\\born03_YanYuZhuang,born03,870.655,58.287,717.367,-1.513"),
            Map.entry("qiandengzhen", "ini\\scene\\born04_QianDengZhen,born04,883.000,-29.000,664.000,4.680"),
            Map.entry("jinyiwei", "ini\\scene\\school01_JinYiWei,school01,480.046,49.653,473.986,0.621"),
            Map.entry("gaibang", "ini\\scene\\school02_GaiBang,school02,522.804,10.774,694.848,2.056"),
            Map.entry("junzitang", "ini\\scene\\school03_JunZiTang,school03,1168.737,9.440,515.075,4.740"),
            Map.entry("jilegu", "ini\\scene\\school04_JiLeGu,school04,851.084,14.440,373.077,2.892"),
            Map.entry("tangmen", "ini\\scene\\school05_TangMen,school05,716.682,6.774,495.249,5.705"),
            Map.entry("emei", "ini\\scene\\school06_EMei,school06,687.422,36.738,726.217,5.836"),
            Map.entry("wudang", "ini\\scene\\school07_WuDang,school07,324.751,-0.405,812.974,2.137"),
            Map.entry("shaolin", "ini\\scene\\school08_ShaoLin,school08,517.669,-52.775,670.125,2.537"),
            Map.entry("yihuagong", "ini\\scene\\school09_YiHuaGong,school09,340.160,47.979,657.284,2.588"),
            Map.entry("taohuadao", "ini\\scene\\school10_taohuadao,school10,-603.500,95.779,-259.100,0.255"),
            Map.entry("wugenmen", "ini\\scene\\school11_wugenmen,school11,34.200,4.600,245.500,1.500"),
            Map.entry("xujia", "ini\\scene\\city05_ChengDu,city05,569.750,39.045,272.750,0.000"),
            Map.entry("jinzhen", "ini\\scene\\city05_ChengDu,city05,354.750,81.061,800.000,0.000"),
            Map.entry("wanshou", "ini\\scene\\school02_GaiBang,school02,338.321,87.146,1016.193,2.927"),
            Map.entry("tianlun", "ini\\scene\\adv156_tianlun,adv156,-45.777,-34.792,-243.900,0.000"),
            Map.entry("qingyi", "ini\\scene\\adv158_qingyi,adv158,1316.718,21.183,1389.666,0.000"),
            Map.entry("changfeng", "ini\\scene\\city03_JinLing,city03,1582.000,9.000,470.000,0.000"),
            Map.entry("wanghui", "ini\\scene\\school28_wanghui,school28,119.267,12.734,3.625,0.000"),
            Map.entry("xuedao", "ini\\scene\\born02_ERenGU,born02,552.000,32.500,922.000,0.000"),
            Map.entry("huashan", "ini\\scene\\school17_huashanpai,school17,1563.800,133.500,694.700,0.000"),
            Map.entry("gumu", "ini\\scene\\school14_gumupai,school14,-277.595,355.876,-325.481,0.000"),
            Map.entry("damo", "ini\\scene\\school18_damo,school18,-373.021,276.227,-573.989,0.000"),
            Map.entry("shenshui", "ini\\scene\\school19_shenshuigong,school19,2269.324,162.736,-1379.467,0.000"),
            Map.entry("nianluo", "ini\\scene\\school15_nianluoba,school15,2133.586,108.031,1007.968,0.000"),
            Map.entry("wuxian", "ini\\scene\\school12_wuxianjiao,school12,1451.899,49.252,799.405,0.000"),
            Map.entry("shenji", "ini\\scene\\school21_shenjiying,school21,-45.400,18.600,-46.900,0.000"),
            Map.entry("xingmiao", "ini\\scene\\school23_xingmiaoge,school23,259.598,246.446,70.540,0.000"),
            Map.entry("tianya", "ini\\scene\\school25_tianya,school25,1745.635,18.729,1066.640,0.000"),
            Map.entry("shenjihui", "ini\\scene\\school31_shenjihui,school31,-48.765,-153.637,-4770.149,0.000"));

    private final SceneManager sceneManager;
    private final RoleStore roleStore;
    private final SceneSwitcher switcher;
    private final SceneFlow sceneFlow;

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher) {
        this(sceneManager, roleStore, switcher, null);
    }

    public GmActions(SceneManager sceneManager, RoleStore roleStore, SceneSwitcher switcher,
                     SceneFlow sceneFlow) {
        this.sceneManager = sceneManager;
        this.roleStore = roleStore;
        this.switcher = switcher;
        this.sceneFlow = sceneFlow;
    }

    /** 执行一条命令；返回错误消息（null = 成功）。未知动作返回提示。 */
    public String execute(FxSession fx, LoginSession session, SceneFlow.SceneState st, GmCommand cmd) {
        PlayerActor actor = session.actor;
        if (actor == null) {
            return "角色尚未进入场景";
        }
        switch (cmd.action()) {
            case "set_silver" -> {
                long silver = cmd.params().getLongValue("silver");
                actor.silver = Math.max(0, silver);
                fx.send(silverUpdate(actor));
                return null;
            }
            case "set_sp" -> {
                actor.sp = clamp(cmd.intParam("sp", 0), 0, PlayerActor.MAX_SP);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "add_sp" -> {
                actor.sp = clamp(actor.sp + cmd.intParam("sp", 0), 0, PlayerActor.MAX_SP);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "fill_sp" -> {
                actor.sp = PlayerActor.MAX_SP;
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "set_move_speed" -> {
                float value = clamp(cmd.params().getFloatValue("value"), 0.1f, 100f);
                actor.moveSpeed = value;
                actor.runSpeed = value;
                fx.send(S2CPackets.batch(1, 1)
                        .f32(PropertyId.MOVE_SPEED, value)
                        .f32(PropertyId.RUN_SPEED, value)
                        .build());
                return null;
            }
            case "set_gravity" -> {
                float value = clamp(cmd.params().getFloatValue("value"), 0.1f, 100f);
                actor.gravity = value;
                fx.send(S2CPackets.batch(1, 1)
                        .f32(PropertyId.GRAVITY, value)
                        .build());
                return null;
            }
            case "teleport" -> {
                float[] pos = parsePosition(cmd.strParam("position"));
                if (pos == null) {
                    return "坐标格式应为 x,y,z,orient";
                }
                switcher.teleportWithinScene(fx, session, st, pos[0], pos[1], pos[2], pos[3]);
                return null;
            }
            case "switch_scene" -> {
                String[] dest = parseDestination(cmd.strParam("destination"));
                if (dest == null) {
                    return "目的地格式应为 config,resource,x,y,z,orient";
                }
                switcher.switchScene(fx, session, st, dest[0], dest[1],
                        Float.parseFloat(dest[2]), Float.parseFloat(dest[3]),
                        Float.parseFloat(dest[4]), Float.parseFloat(dest[5]));
                return null;
            }
            case "switch_scene_preset" -> {
                String destination = SCENE_PRESETS.get(cmd.strParam("preset"));
                if (destination == null) {
                    return "未知场景预设: " + cmd.strParam("preset");
                }
                String[] dest = parseDestination(destination);
                switcher.switchScene(fx, session, st, dest[0], dest[1],
                        Float.parseFloat(dest[2]), Float.parseFloat(dest[3]),
                        Float.parseFloat(dest[4]), Float.parseFloat(dest[5]));
                return null;
            }
            case "set_faction" -> {
                FactionPreset preset = FACTIONS.get(cmd.strParam("faction"));
                if (preset == null) {
                    return "未知门派预设: " + cmd.strParam("faction");
                }
                actor.faction = preset.faction();
                S2CPackets.Batch b = S2CPackets.playerBatch();
                S2CPackets.appendFaction(b, preset.faction());
                fx.send(b.build());
                persistFaction(session, preset.faction());
                return null;
            }
            case "npc_bubble" -> {
                int oid = st.selectedOid != 0 ? st.selectedOid
                        : (st.world != null && !st.world.npcs.isEmpty() ? st.world.npcs.get(0).objectId : 0);
                if (oid == 0) {
                    return "场景内没有 NPC";
                }
                fx.send(CustomPackets.npcBubble(oid, 1, cmd.strParam("value").isEmpty()
                        ? "ui_shop" : cmd.strParam("value")));
                return null;
            }
            case "drama_prompt" -> {
                fx.send(CustomPackets.dramaPrompt(1, "ui_shop", 1, 1, "ui_shop", "", "", 0));
                return null;
            }
            case "red_super_armor" -> {
                actor.activateBuff(PlayerActor.BUFF_SLOT_RED_ARMOR,
                        PlayerActor.RED_ARMOR_STATIC, 20000, "", 1);
                fx.send(actor.vitalUpdate());
                return null;
            }
            case "action_state_fishing" -> {
                sendState(fx, "interact268");
                return null;
            }
            case "action_state_hsqs05" -> {
                sendState(fx, "hsqs_05_h");
                return null;
            }
            case "action_state_sgmd" -> {
                sendState(fx, "hsqs_07");
                return null;
            }
            case "action_state_stop" -> {
                sendState(fx, "stand");
                return null;
            }
            case "action_native_fishing" -> {
                fx.send(CustomPackets.nativeAction(1, 1, "interact268"));
                return null;
            }
            // ---------- 暮色剧情验证（clone003） ----------
            case "twilight_stages_start" -> {
                return twilightStagesStart(fx, session, st);
            }
            case "twilight_stages_remove" -> {
                return twilightStagesRemove(fx, session, st);
            }
            case "twilight_mechanism_start" -> {
                TwilightVerify.ensureScene(fx, session, st, switcher);
                if (st.world == null) {
                    st.world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                }
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
                NpcInstance box = TwilightVerify.spawn(sceneManager, st.world,
                        TwilightVerify.BOX_CONFIG, TwilightVerify.BOX_POS, TwilightVerify.TAG_BOX);
                TwilightVerify.sendNpc(fx, st, sceneManager, box);
                return null;
            }
            case "twilight_mechanism_remove" -> {
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
                return null;
            }
            case "twilight_npc_move_start" -> {
                TwilightVerify.ensureScene(fx, session, st, switcher);
                if (st.world == null) {
                    st.world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                }
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
                NpcInstance villager = TwilightVerify.spawn(sceneManager, st.world,
                        TwilightVerify.VILLAGER_CONFIG, TwilightVerify.VILLAGER_POS,
                        TwilightVerify.TAG_VILLAGER);
                TwilightVerify.sendNpc(fx, st, sceneManager, villager);
                return null;
            }
            case "twilight_npc_move_remove" -> {
                TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
                return null;
            }
            case "twilight_difficulty" -> {
                int level = cmd.intParam("level", 1);
                if (level < 1 || level > 4) {
                    return "level 应为 1~4（普通/困难/地狱/修罗）";
                }
                int rating = Math.max(1, Math.min(3, cmd.intParam("rating", 1)));
                SceneWorld world = st.world != null && st.world.cloneScene
                        ? st.world : sceneManager.world(TwilightVerify.SCENE_RESOURCE);
                world.dungeonDifficulty = level;
                world.dungeonRating = rating;
                fx.send(S2CPackets.worldInfo(0,
                        "副本难度：" + difficultyName(level) + "，评价档：" + rating));
                return null;
            }
            default -> {
                return "动作未实现: " + cmd.action();
            }
        }
    }

    /**
     * 暮色剧情归位验证：不在村则从村口大门进村（重进链完成后全量下发）；
     * 已在村则把波次进度推到最大并全量重发摆位（creator 固定坐标，不跟随玩家）。
     */
    private String twilightStagesStart(FxSession fx, LoginSession session, SceneFlow.SceneState st) {
        SceneWorld world = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
        world.dungeonProgress = TwilightVerify.MAX_PROGRESS;
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
        if (!TwilightVerify.inScene(session)) {
            switcher.switchScene(fx, session, st, TwilightVerify.SCENE_CONFIG,
                    TwilightVerify.SCENE_RESOURCE, TwilightVerify.ENTRANCE[0],
                    TwilightVerify.ENTRANCE[1], TwilightVerify.ENTRANCE[2],
                    TwilightVerify.ENTRANCE[3]);
            return null;
        }
        st.world = world;
        resendAllNpcs(fx, st, session);
        return null;
    }

    /** 重置/清理：丢弃 clone003 世界缓存（进度回到 1），在村则重发初始摆位。 */
    private String twilightStagesRemove(FxSession fx, LoginSession session, SceneFlow.SceneState st) {
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_BOX);
        TwilightVerify.despawnTagged(fx, st, TwilightVerify.TAG_VILLAGER);
        sceneManager.resetWorld(TwilightVerify.SCENE_RESOURCE);
        SceneWorld fresh = sceneManager.world(TwilightVerify.SCENE_RESOURCE);
        if (TwilightVerify.inScene(session)) {
            st.world = fresh;
            resendAllNpcs(fx, st, session);
        }
        return null;
    }

    /** 全量重发：先移除客户端已见对象（含进度回退后应消失的波次），再走标准下发。 */
    private void resendAllNpcs(FxSession fx, SceneFlow.SceneState st, LoginSession session) {
        if (sceneFlow == null) {
            log.warn("SceneFlow 未接入，无法重发摆位");
            return;
        }
        if (st.world != null) {
            synchronized (st.world) {
                for (Integer oid : st.sentIds) {
                    var n = st.world.npcById(oid);
                    fx.send(S2CPackets.removeObject(oid, n != null ? n.ownerId : 1));
                }
            }
        }
        st.sentIds.clear();
        sceneFlow.sendSceneNpcs(session);
    }

    private static String difficultyName(int level) {
        return switch (level) {
            case 2 -> "困难";
            case 3 -> "地狱";
            case 4 -> "修罗";
            default -> "普通";
        };
    }

    private String sendState(FxSession fx, String action) {
        fx.send(S2CPackets.objectPropertyString(PropertyId.STATE, action));
        return null;
    }

    private static byte[] silverUpdate(PlayerActor actor) {
        return S2CPackets.playerBatch()
                .i64(PropertyId.CAPITAL_TYPE1, actor.silver)
                .build();
    }

    private void persistFaction(LoginSession session, String faction) {
        if (session.accountKey.isEmpty()) {
            return;
        }
        java.util.List<String> appearance = new java.util.ArrayList<>(session.appearance);
        while (appearance.size() < 9) {
            appearance.add("");
        }
        appearance.set(3, faction);
        session.appearance = appearance;
        StoredRole role = new StoredRole();
        role.setName(session.roleName);
        role.setAppearance(appearance);
        role.setSceneConfig(session.spawnConfig);
        role.setSceneResource(session.spawnResource);
        role.setX(session.x);
        role.setY(session.y);
        role.setZ(session.z);
        role.setOrient(session.orient);
        roleStore.put(session.accountKey, role);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static float[] parsePosition(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()), Float.parseFloat(parts[3].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String[] parseDestination(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 6 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return null;
        }
        try {
            for (int i = 2; i < 6; i++) {
                Float.parseFloat(parts[i].trim());
            }
            return parts;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
