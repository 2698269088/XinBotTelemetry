package top.mcocet.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表对客户端隐私开关(裁剪 bot 名/服务器地址)的容错:
 * bot 名缺失的包不再被丢弃,而是按来源 IP 匿名登记。
 */
class BotRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造一个心跳:默认不含 bot/server,模拟客户端关掉 sendBot/sendServer */
    private ObjectNode heartbeat() {
        return mapper.createObjectNode()
                .put("type", "heartbeat")
                .put("online", true)
                .put("state", "Game")
                .put("players", 2);
    }

    @Test
    void heartbeatWithoutBotNameIsTrackedAnonymouslyBySourceIp() {
        BotRegistry registry = new BotRegistry(60_000);
        ObjectNode hb = heartbeat();

        BotStatus first = registry.onHeartbeat(hb, "203.0.113.7", 1000L);
        assertNotNull(first);
        assertEquals(BotStatus.UNKNOWN_NAME, first.getName());
        assertEquals(BotStatus.UNKNOWN_SERVER, first.getServer());

        // 同一来源的后续心跳刷新同一条记录
        BotStatus refresh = registry.onHeartbeat(hb.deepCopy(), "203.0.113.7", 2000L);
        assertSame(first, refresh);

        // 不同来源的匿名客户端各自成条,互不覆盖
        BotStatus other = registry.onHeartbeat(hb.deepCopy(), "203.0.113.8", 3000L);
        assertNotSame(first, other);
        assertEquals(BotStatus.UNKNOWN_NAME, other.getName());
        assertEquals(2, registry.stats().offline());
    }

    @Test
    void heartbeatWithBotNameIsKeyedByNameAndServerAsBefore() {
        BotRegistry registry = new BotRegistry(60_000);
        ObjectNode hb = heartbeat().put("bot", "Steve").put("server", "mc.example:25565");

        BotStatus first = registry.onHeartbeat(hb, "203.0.113.7", 1000L);
        assertEquals("Steve", first.getName());
        assertEquals("mc.example:25565", first.getServer());

        // 带名字的记录仍按名称归并,与匿名记录不冲突
        BotStatus refresh = registry.onHeartbeat(hb.deepCopy(), "203.0.113.7", 2000L);
        assertSame(first, refresh);
        assertEquals(1, registry.stats().offline());
    }

    @Test
    void crashWithoutBotNameStillRegistersAndHeartbeatRecoversIt() {
        BotRegistry registry = new BotRegistry(60_000);
        ObjectNode crash = mapper.createObjectNode()
                .put("type", "crash")
                .put("online", false)
                .put("exception", "java.lang.Error: boom");
        String sourceIp = "203.0.113.9";

        BotStatus status = registry.onCrash(crash, sourceIp, 1000L);
        assertNotNull(status);
        assertTrue(status.isCrashed());
        assertEquals(BotStatus.UNKNOWN_NAME, status.getName());

        // 同来源后续心跳把该匿名客户端标记为恢复在线
        ObjectNode hb = heartbeat().put("online", true);
        BotStatus recovered = registry.onHeartbeat(hb, sourceIp, 2000L);
        assertSame(status, recovered);
        assertFalse(status.isCrashed());
        assertEquals(1, registry.stats().offline());
    }
}
