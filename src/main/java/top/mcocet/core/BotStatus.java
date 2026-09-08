package top.mcocet.core;

import com.fasterxml.jackson.databind.JsonNode;
import top.mcocet.i18n.I18n;

/**
 * 一个 BOT 的实时状态。key 为 bot 名 + 服务器地址,
 * 同一账号跑在不同服务器上会分别记录。
 */
public class BotStatus {

    /** server 字段缺失时展示的占位文案,随界面语言 */
    public static final String UNKNOWN_SERVER = I18n.get("bot.unknown_server");

    /** bot 名缺失(客户端按隐私开关裁剪)时展示的占位文案,随界面语言 */
    public static final String UNKNOWN_NAME = I18n.get("bot.unknown_name");

    private final String name;
    private final String server;
    private final long firstSeenMs;
    private volatile String sourceIp;

    private volatile String version;
    private volatile String state;      // Login / Game
    private volatile int players;
    private volatile long lastSeenMs;
    private volatile long uptimeMs;
    private volatile String osName;
    private volatile String osArch;
    private volatile String javaVersion;
    private volatile boolean crashed;
    private volatile long lastCrashMs;
    private volatile String crashException;

    public BotStatus(String name, String server, String sourceIp, long now) {
        this.name = name;
        this.server = server;
        this.sourceIp = sourceIp;
        this.firstSeenMs = now;
        this.lastSeenMs = now;
        this.state = server == null ? null : "Login";
    }

    /** 心跳到达:刷新各字段并解除崩溃标记 */
    public void onHeartbeat(JsonNode hb, String sourceIp, long now) {
        this.lastSeenMs = now;
        this.crashed = false;
        this.sourceIp = sourceIp;
        this.version = text(hb, "version");
        this.state = text(hb, "state");
        this.players = hb.path("players").asInt(0);
        this.uptimeMs = hb.path("uptime_ms").asLong(0);
        this.osName = text(hb, "os_name");
        this.osArch = text(hb, "os_arch");
        this.javaVersion = text(hb, "java_version");
    }

    /** 崩溃报告到达:BOT 立即视为离线,待下次心跳恢复 */
    public void onCrash(JsonNode crash, String sourceIp, long now) {
        this.lastSeenMs = now;
        this.crashed = true;
        this.sourceIp = sourceIp;
        this.lastCrashMs = now;
        this.version = text(crash, "version");
        this.state = text(crash, "state");
        this.players = crash.path("players").asInt(0);
        this.crashException = text(crash, "exception");
    }

    public boolean isOnline(long now, long onlineTimeoutMs) {
        return !crashed && now - lastSeenMs <= onlineTimeoutMs;
    }

    public String getName() {
        return name;
    }

    public String getServer() {
        return server;
    }

    public long getFirstSeenMs() {
        return firstSeenMs;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getVersion() {
        return version;
    }

    public String getState() {
        return state;
    }

    public int getPlayers() {
        return players;
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    public long getUptimeMs() {
        return uptimeMs;
    }

    public String getOsName() {
        return osName;
    }

    public String getOsArch() {
        return osArch;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public boolean isCrashed() {
        return crashed;
    }

    public long getLastCrashMs() {
        return lastCrashMs;
    }

    public String getCrashException() {
        return crashException;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s.isBlank() ? null : s;
    }
}
