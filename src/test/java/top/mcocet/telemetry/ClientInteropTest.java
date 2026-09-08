package top.mcocet.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 互通测试:解码由 xinbot-master 客户端测试(InteropVectorTest)用真实
 * TelemetryManager 发送实现生成的加密信封,验证接收端与真实客户端实现互通,
 * 而不是两端各自重复的协议辅助代码独立自测。向量缺失时跳过(需先在
 * xinbot-master 运行一次 InteropVectorTest 刷新向量)。
 */
class ClientInteropTest {

    /** 与客户端 InteropVectorTest / TelemetryTest 共享的 32 字节测试密钥 */
    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void decodesRealClientHeartbeat() throws Exception {
        byte[] envelope = vector("client-heartbeat.bin");
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope, TEST_KEY);
        assertEquals(PacketDecoder.TYPE_HEARTBEAT, packet.type());
        JsonNode body = packet.body();
        assertEquals("heartbeat", body.path("type").asText());
        assertEquals("interop-bot", body.path("bot").asText());
        assertEquals("interop.example.com:25565", body.path("server").asText());
        assertEquals(2, body.path("players").asInt());
        assertTrue(body.path("version").asText().startsWith("2.4."));
        assertTrue(body.has("heap_used_bytes") && body.has("os_name"));
    }

    @Test
    void decodesRealClientCrashReport() throws Exception {
        byte[] envelope = vector("client-crash.bin");
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope, TEST_KEY);
        assertEquals(PacketDecoder.TYPE_CRASH, packet.type());
        JsonNode body = packet.body();
        assertEquals("crash", body.path("type").asText());
        assertEquals("main", body.path("thread_name").asText());
        assertTrue(body.path("exception").asText().contains("NullPointerException"));
        assertTrue(body.path("stack_trace").asText().contains("Bot.java"));
    }

    private static byte[] vector(String name) {
        InputStream in = ClientInteropTest.class.getResourceAsStream("/interop/" + name);
        Assumptions.assumeTrue(in != null,
                "缺少互通向量 /interop/" + name
                        + " :请先在 xinbot-master 运行 InteropVectorTest(或 mvn test)刷新向量后重跑本测试");
        try (InputStream is = in) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("读取互通向量失败: " + name, e);
        }
    }
}
