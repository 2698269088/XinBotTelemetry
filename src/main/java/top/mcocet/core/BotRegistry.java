package top.mcocet.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BOT 在线状态注册表:以 "bot名@服务器" 为 key 维护每个 BOT 的最新状态。
 * 在线判定:收到过心跳且距最后心跳未超过在线超时,且未收到过崩溃报告。
 */
public class BotRegistry {

    /** 完全离线超过该时长(默认 7 天)的记录会被清理,防止内存膨胀 */
    private static final long PURGE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;

    private final ConcurrentHashMap<String, BotStatus> bots = new ConcurrentHashMap<>();
    private final long onlineTimeoutMs;

    public BotRegistry(long onlineTimeoutMs) {
        this.onlineTimeoutMs = onlineTimeoutMs;
    }

    public long getOnlineTimeoutMs() {
        return onlineTimeoutMs;
    }

    /** 心跳到达:登记或刷新 BOT */
    public BotStatus onHeartbeat(JsonNode hb, String sourceIp, long now) {
        String name = text(hb, "bot");
        if (name == null || name.isBlank()) {
            return null;
        }
        String server = text(hb, "server");
        BotStatus status = bots.computeIfAbsent(
                key(name, server),
                k -> new BotStatus(name, server == null ? BotStatus.UNKNOWN_SERVER : server, sourceIp, now)
        );
        status.onHeartbeat(hb, sourceIp, now);
        return status;
    }

    /** 崩溃报告到达:登记或标记 BOT 崩溃 */
    public BotStatus onCrash(JsonNode crash, String sourceIp, long now) {
        String name = text(crash, "bot");
        if (name == null || name.isBlank()) {
            return null;
        }
        String server = text(crash, "server");
        BotStatus status = bots.computeIfAbsent(
                key(name, server),
                k -> new BotStatus(name, server == null ? BotStatus.UNKNOWN_SERVER : server, sourceIp, now)
        );
        status.onCrash(crash, sourceIp, now);
        return status;
    }

    /** 当前全部 BOT 快照(按最后心跳倒序,便于页面展示) */
    public List<BotStatus> snapshot() {
        long now = System.currentTimeMillis();
        List<BotStatus> list = new ArrayList<>(bots.values());
        list.removeIf(s -> now - s.getLastSeenMs() > PURGE_AFTER_MS);
        list.sort((a, b) -> Long.compare(b.getLastSeenMs(), a.getLastSeenMs()));
        return list;
    }

    /** 在线/离线/已崩溃计数 */
    public Stats stats() {
        long now = System.currentTimeMillis();
        int online = 0;
        int offline = 0;
        int crashed = 0;
        for (BotStatus status : bots.values()) {
            if (status.isCrashed()) {
                crashed++;
            } else if (status.isOnline(now, onlineTimeoutMs)) {
                online++;
            } else {
                offline++;
            }
        }
        return new Stats(online, offline, crashed);
    }

    /** 清理超过 7 天未上报的陈旧记录(由定时任务调用) */
    public void purgeOld() {
        long cutoff = System.currentTimeMillis() - PURGE_AFTER_MS;
        bots.entrySet().removeIf(e -> e.getValue().getLastSeenMs() < cutoff);
    }

    private static String key(String name, String server) {
        return name + "@" + (server == null ? BotStatus.UNKNOWN_SERVER : server);
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s.isBlank() ? null : s;
    }

    /** 在线状态统计 */
    public record Stats(int online, int offline, int crashed) {
    }
}
