package com.nineyin.server.game.avatar.jingmai;

import java.util.ArrayList;
import java.util.List;

import com.nineyin.server.protocol.PropertyValues;
import com.nineyin.server.protocol.Props;
import com.nineyin.server.protocol.RecordMessages;
import com.nineyin.server.protocol.SceneMessages;

/**
 * 经脉系统 S2C 帧族（F 盘 52795 抓包逐字节对齐）：
 * View45（VIEWPORT_JINGMAI）容器 + 6 属性行（ConfigID/ItemType/StaticData/
 * Level(byte)/MaxLevel/TotalFillValue，抓包 14 行实证）；
 * 玩家属性 CurJingMai/LastJingMai/JingMaiTotalLevel/jmActCount/ZQ 族
 * （真机 0x09 表 849/850/819/851/852-857 按名解析）；
 * active_jingmai_rec（官方下标 162）已激活经脉行（抓包 'jm_dantian' 实证）。
 */
public final class JingMaiMessages {

    /** share/view_define.lua VIEWPORT_JINGMAI=45（已学经脉对象容器）。 */
    public static final int VIEWPORT_JINGMAI = 45;
    /** 每日真气上限（玩家属性 ZhenQiDayValue；F 盘抓包实测 500）。 */
    public static final int ZHENQI_DAY_VALUE = 500;
    /** 可激活经脉条数（玩家属性 jmActCount；F 盘抓包实测 1）。 */
    public static final int JM_ACT_COUNT = 1;

    /** 进图授予：0x15 容器（容量=已学数）+ 每条一帧 0x18 行。 */
    public static List<byte[]> viewFrames(PlayerMeridian state, MeridianCatalog catalog) {
        List<PlayerMeridian.Meridian> learned = state.learned();
        List<byte[]> frames = new ArrayList<>(learned.size() + 1);
        frames.add(SceneMessages.createView(new SceneMessages.ViewSpec(VIEWPORT_JINGMAI,
                Math.max(learned.size(), 1))));
        int row = 1;
        for (PlayerMeridian.Meridian meridian : learned) {
            frames.add(meridianRowFrame(catalog, row, meridian));
            row++;
        }
        return frames;
    }

    /** View45 单行（抓包同构 6 属性；CurFillValue 非零时一并携带，减少一次 0x10）。 */
    public static byte[] meridianRowFrame(MeridianCatalog catalog, int row,
                                          PlayerMeridian.Meridian meridian) {
        MeridianCatalog.Definition definition = catalog.definition(meridian.configId());
        int maxLevel = definition == null ? 0 : definition.maxLevel();
        int staticData = definition == null ? 0 : definition.staticData();
        int itemType = definition == null ? 1003 : definition.itemType();
        List<SceneMessages.ViewProperty> properties = new ArrayList<>(7);
        Props.addView(properties, "ConfigID", PropertyValues.Value.ofString(meridian.configId()));
        Props.addView(properties, "ItemType", PropertyValues.Value.ofInt32(itemType));
        Props.addView(properties, "StaticData", PropertyValues.Value.ofInt32(staticData));
        Props.addView(properties, "Level", PropertyValues.Value.ofByte(meridian.level()));
        Props.addView(properties, "MaxLevel", PropertyValues.Value.ofInt32(maxLevel));
        Props.addView(properties, "TotalFillValue", PropertyValues.Value.ofInt32(
                catalog.totalFillPerLevel(meridian.configId())));
        if (meridian.fill() > 0) {
            Props.addView(properties, "CurFillValue", PropertyValues.Value.ofInt32(meridian.fill()));
        }
        return SceneMessages.viewAdd(VIEWPORT_JINGMAI, row, properties);
    }

    /** 修炼进度单帧（0x10 isView=1 | obj=45 | owner=行号）：CurFillValue。 */
    public static byte[] progressFrame(int row, int fill) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(1);
        Props.add(properties, "CurFillValue", PropertyValues.Value.ofInt32(fill));
        return viewRowProperties(VIEWPORT_JINGMAI, row, properties);
    }

    /** 升级单帧：Level(byte) + CurFillValue + TotalFillValue（同一 View45 行）。 */
    public static byte[] levelUpFrame(int row, int level, int fill, int totalFill) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(3);
        Props.add(properties, "Level", PropertyValues.Value.ofByte(level));
        Props.add(properties, "CurFillValue", PropertyValues.Value.ofInt32(fill));
        Props.add(properties, "TotalFillValue", PropertyValues.Value.ofInt32(totalFill));
        return viewRowProperties(VIEWPORT_JINGMAI, row, properties);
    }

    /** 玩家经脉属性单帧（0x10）：修炼对象/进度展示族，变化即推。 */
    public static byte[] playerPropsFrame(PlayerMeridian state) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(8);
        Props.add(properties, "CurJingMai", PropertyValues.Value.ofString(state.curJingMai()));
        Props.add(properties, "LastJingMai", PropertyValues.Value.ofString(state.lastJingMai()));
        Props.add(properties, "JingMaiTotalLevel", PropertyValues.Value.ofInt32(state.totalLevel()));
        Props.add(properties, "jmActCount", PropertyValues.Value.ofInt32(JM_ACT_COUNT));
        Props.add(properties, "ZhenQiDayValue", PropertyValues.Value.ofInt32(state.zhenQiDayValue()));
        Props.add(properties, "ZQPLValue", PropertyValues.Value.ofInt32(state.plValue()));
        Props.add(properties, "ZQActlValue", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "ZQUnUsedValue", PropertyValues.Value.ofInt32(0));
        return playerProperties(properties);
    }

    /** 已激活经脉行（active_jingmai_rec，官方下标 162，单字符串列）。 */
    public static byte[] activeRecordRow(int objectId, int ownerId, String configId) {
        return RecordMessages.recordAddString(objectId, ownerId,
                RecordMessages.RECORD_ACTIVE_JINGMAI, configId);
    }

    /** 摘除已激活经脉行（0x12 按行号）。 */
    public static byte[] activeRecordRowRemove(int objectId, int ownerId, int row) {
        return RecordMessages.recordDelRow(objectId, ownerId,
                RecordMessages.RECORD_ACTIVE_JINGMAI, row);
    }

    /** 玩家经脉属性列表（addObject/stateProperties 复用；空列表安全）。 */
    public static List<PropertyValues.Indexed> playerProps(PlayerMeridian state) {
        List<PropertyValues.Indexed> properties = new ArrayList<>(8);
        Props.add(properties, "CurJingMai", PropertyValues.Value.ofString(
                state == null ? "" : state.curJingMai()));
        Props.add(properties, "LastJingMai", PropertyValues.Value.ofString(
                state == null ? "" : state.lastJingMai()));
        Props.add(properties, "JingMaiTotalLevel",
                PropertyValues.Value.ofInt32(state == null ? 0 : state.totalLevel()));
        Props.add(properties, "jmActCount", PropertyValues.Value.ofInt32(JM_ACT_COUNT));
        Props.add(properties, "ZhenQiDayValue",
                PropertyValues.Value.ofInt32(state == null ? ZHENQI_DAY_VALUE : state.zhenQiDayValue()));
        Props.add(properties, "ZQPLValue",
                PropertyValues.Value.ofInt32(state == null ? 0 : state.plValue()));
        Props.add(properties, "ZQActlValue", PropertyValues.Value.ofInt32(0));
        Props.add(properties, "ZQUnUsedValue", PropertyValues.Value.ofInt32(0));
        return properties;
    }

    private static byte[] viewRowProperties(int viewId, int row,
                                            List<PropertyValues.Indexed> properties) {
        return SceneMessages.objectProperties(viewId, row, 1, properties);
    }

    private static byte[] playerProperties(List<PropertyValues.Indexed> properties) {
        return SceneMessages.objectProperties(SceneMessages.PLAYER_OBJECT_ID,
                SceneMessages.PLAYER_OWNER_ID, 0, properties);
    }

    private JingMaiMessages() {
    }
}
