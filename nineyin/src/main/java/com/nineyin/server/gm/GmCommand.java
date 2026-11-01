package com.nineyin.server.gm;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.alibaba.fastjson2.JSONObject;

/**
 * GM 命令（控制台 POST 进队列，游戏侧消费）。
 * 参数整体透传：面板动作已达数十种（guanYin/gold/pkMode/stage/cardId/…），
 * 由消费端按 action 自行取值。
 *
 * <p>回执（对齐 Go gmCommand.reply chan + HTTP 层 2s 确认窗）：游戏侧消费后
 * {@link #succeed}/{@link #fail} 完成回执；GmRemote 入队后
 * {@link #awaitReply} 有界等待，把真实结果（而非「已入队」）回给面板。
 */
public record GmCommand(long sessionId, String action, JSONObject params,
                        CompletableFuture<Reply> reply) {

    public static final String ACTION_KICK = "kick";
    public static final String ACTION_MINIMAL_LOGIN = "minimal_login";

    /** 游戏侧消费结果：ok=false 时 message 为失败原因。 */
    public record Reply(boolean ok, String message) {
    }

    public GmCommand {
        params = params != null ? params : new JSONObject();
        reply = reply != null ? reply : new CompletableFuture<>();
    }

    public GmCommand(long sessionId, String action, JSONObject params) {
        this(sessionId, action, params, new CompletableFuture<>());
    }

    /** 消费成功；message 为面板 lastAction 同款文案（如「移动速度设为 25.00」）。 */
    public void succeed(String message) {
        reply.complete(new Reply(true, message));
    }

    /** 消费失败（未进场景/数值非法/动作未实现/会话断开）。 */
    public void fail(String message) {
        reply.complete(new Reply(false, message));
    }

    /**
     * 有界等待游戏侧回执（Go HTTP 层 2s 确认窗；超时不把「已入队」描述成成功）。
     * 超时/中断不抛异常，统一折算为失败 Reply。
     */
    public Reply awaitReply(Duration timeout) {
        try {
            return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Reply(false, "游戏会话未在 " + timeout.toSeconds() + " 秒内确认发送");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(false, "等待游戏会话回执被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Reply(false, String.valueOf(cause.getMessage()));
        }
    }

    public int intParam(String key, int def) {
        Integer v = params.getInteger(key);
        return v != null ? v : def;
    }

    public String strParam(String key) {
        String v = params.getString(key);
        return v != null ? v : "";
    }

    /** float 参数（移动参数 value 等）；缺失/非数返回 null。 */
    public Float floatParam(String key) {
        return params.getFloat(key);
    }
}
