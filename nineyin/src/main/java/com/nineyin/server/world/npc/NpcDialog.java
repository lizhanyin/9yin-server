package com.nineyin.server.world.npc;

import java.util.HashMap;
import java.util.Map;

/**
 * 单连接 NPC 对话路由态（两段式交互的服务端侧）：开对话时分配菜单 mark，
 * 客户端选项以 0x07 FuncID=mark 回发后查表分发。
 *
 * <p>mark 是服务器本地路由号（每次开对话从 1 重新分配；protocol-notes §12：
 * 故意不复用旧 FuncID 数字空间）。同一时间只挂一个对话。
 */
public final class NpcDialog {

    /** 菜单项动作。 */
    public sealed interface MenuAction permits NextPage, ChooseOption, AcceptTask, SubmitTask, Leave {
    }

    /** 剧情链翻页（对话节点仅 NextPage 时菜单单「继续」项）。 */
    public record NextPage(int nodeId) implements MenuAction {
    }

    /** 命中选项（shop/giveitem/task/trans…，走 OptionTalkCatalog.Option 路由）。 */
    public record ChooseOption(OptionTalkCatalog.Option option) implements MenuAction {
    }

    /** 接任务（optiontalk task 选项 / npcconfig TaskCanAccept 任务专线路由）。 */
    public record AcceptTask(int taskId) implements MenuAction {
    }

    /** 交任务（npcconfig TaskCanSubmit 命中玩家已接任务）。 */
    public record SubmitTask(int taskId) implements MenuAction {
    }

    /** 离开（回 0x1D 关菜单）。 */
    public record Leave() implements MenuAction {
    }

    private int objectId;
    private int ownerId;
    private int nextMark = 1;
    private final Map<Integer, MenuAction> marks = new HashMap<>();
    /** 当前打开商店号（msg70 购买校验；null=未开店）。 */
    private String openShopId;

    /** 重置到新对话（清空旧 mark 表）。 */
    public void reset(int objectId, int ownerId) {
        this.objectId = objectId;
        this.ownerId = ownerId;
        this.nextMark = 1;
        this.marks.clear();
    }

    /** 分配下一个 mark（1 起递增）。 */
    public int allocate(MenuAction action) {
        int mark = nextMark++;
        marks.put(mark, action);
        return mark;
    }

    /** 取走 mark 对应动作（消费后删除，防重放）。 */
    public MenuAction take(int mark) {
        return marks.remove(mark);
    }

    public boolean isActive() {
        return !marks.isEmpty() || openShopId != null;
    }

    public int objectId() {
        return objectId;
    }

    public int ownerId() {
        return ownerId;
    }

    public String openShopId() {
        return openShopId;
    }

    public void openShop(String shopId) {
        this.openShopId = shopId;
    }

    public void closeShop() {
        this.openShopId = null;
    }
}
