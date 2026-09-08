package top.mcocet.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 一条崩溃日志。字段与客户端崩溃报告的 JSON 负载对应,
 * crashedAt 取客户端上报的 timestamp_ms,receivedAt 为服务端收到时间。
 */
public record CrashRecord(
        long id,
        String botName,
        String server,
        String version,
        String state,
        boolean online,
        int playerCount,
        String threadName,
        String exception,
        String stackTrace,
        long crashedAt,
        long receivedAt
) {

    /** 从客户端崩溃报告 JSON 构造(尚未入库,id 置 0) */
    public static CrashRecord from(JsonNode body, long receivedAt) {
        long crashedAt = body.path("timestamp_ms").asLong(receivedAt);
        return new CrashRecord(
                0,
                text(body, "bot", "(未知)"),
                text(body, "server", null),
                text(body, "version", null),
                text(body, "state", null),
                body.path("online").asBoolean(false),
                body.path("players").asInt(0),
                text(body, "thread_name", null),
                text(body, "exception", null),
                text(body, "stack_trace", null),
                crashedAt,
                receivedAt
        );
    }

    /** 从数据库行读取 */
    public static CrashRecord fromRow(ResultSet rs) throws SQLException {
        return new CrashRecord(
                rs.getLong("id"),
                rs.getString("bot_name"),
                rs.getString("server"),
                rs.getString("version"),
                rs.getString("state"),
                rs.getInt("online") != 0,
                rs.getInt("player_count"),
                rs.getString("thread_name"),
                rs.getString("exception"),
                rs.getString("stack_trace"),
                rs.getLong("crashed_at"),
                rs.getLong("received_at")
        );
    }

    private static String text(JsonNode body, String key, String fallback) {
        JsonNode node = body.get(key);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText();
        return value.isBlank() ? fallback : value;
    }
}
