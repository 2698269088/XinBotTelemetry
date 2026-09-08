package top.mcocet.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketDecoderTest {

    /** 32 ASCII 字节,即合法的 32 字节 AES-256 测试密钥(仅用于测试) */
    private static final byte[] TEST_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static final String HEARTBEAT_JSON = """
            {"type":"heartbeat","timestamp_ms":1700000000000,"bot":"test-bot",
             "online":true,"state":"Game","server":"mc.example.com:25565","players":3,
             "version":"2.4.2-RELEASE"}
            """;

    @Test
    void decodesHeartbeatEnvelope() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope, TEST_KEY);
        assertEquals(PacketDecoder.TYPE_HEARTBEAT, packet.type());
        JsonNode body = packet.body();
        assertEquals("heartbeat", body.path("type").asText());
        assertEquals("test-bot", body.path("bot").asText());
        assertEquals("mc.example.com:25565", body.path("server").asText());
        assertEquals(3, body.path("players").asInt());
    }

    @Test
    void decodesCrashEnvelope() throws Exception {
        String crashJson = """
                {"type":"crash","bot":"bot-a","server":"srv","timestamp_ms":1700000000000,
                 "thread_name":"main","exception":"java.lang.NullPointerException: boom",
                 "stack_trace":"java.lang.NullPointerException: boom\\n\\tat xin.bbtt.mcbot.Bot.start(Bot.java:1)"}
                """;
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_CRASH, crashJson.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope, TEST_KEY);
        assertEquals(PacketDecoder.TYPE_CRASH, packet.type());
        assertEquals("main", packet.body().path("thread_name").asText());
        assertTrue(packet.body().path("stack_trace").asText().contains("Bot.java"));
    }

    @Test
    void rejectsMutatedTypeByte() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        // 头部 type 字节作为 GCM AAD 参与认证:心跳信封改成崩溃必须被拒绝
        envelope[5] = PacketDecoder.TYPE_CRASH;
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
    }

    @Test
    void rejectsJsonTypeMismatchingEnvelope() throws Exception {
        // 合法加密、头部未被篡改,但负载 JSON 的 type 与信封类型矛盾
        String crashJson = """
                {"type":"crash","bot":"bot-a","server":"srv","timestamp_ms":1700000000000}
                """;
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, crashJson.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        IOException e = assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
        assertTrue(e.getMessage().contains("type_mismatch") || e.getMessage().contains("不一致"));
    }

    @Test
    void rejectsEnvelopeWithoutJsonTypeField() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, "{\"bot\":\"x\"}".getBytes(StandardCharsets.UTF_8), TEST_KEY);
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
    }

    @Test
    void rejectsWrongKey() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        byte[] otherKey = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, otherKey));
    }

    @Test
    void rejectsShortPacket() {
        assertThrows(IOException.class, () -> PacketDecoder.decode(new byte[10], TEST_KEY));
    }

    @Test
    void rejectsBadMagic() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, "{}".getBytes(StandardCharsets.UTF_8), TEST_KEY);
        envelope[0] = 'X';
        envelope[1] = 'B';
        envelope[2] = 'T';
        envelope[3] = 'X'; // 魔数最后一位错误
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8), TEST_KEY);
        envelope[envelope.length - 1] ^= 0x01; // 破坏认证标签
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
    }

    @Test
    void rejectsInvalidJson() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, "not-json".getBytes(StandardCharsets.UTF_8), TEST_KEY);
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope, TEST_KEY));
    }

    @Test
    void parseKeyAcceptsBase64OfExactly32Bytes() {
        String encoded = Base64.getEncoder().encodeToString(TEST_KEY);
        assertArrayEquals(TEST_KEY, PacketDecoder.parseKey(encoded));
        // 两侧允许空白
        assertArrayEquals(TEST_KEY, PacketDecoder.parseKey("  " + encoded + "\n"));
    }

    @Test
    void parseKeyRejectsBlankMalformedAndWrongLengthValues() {
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.parseKey(null));
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.parseKey(""));
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.parseKey("   "));
        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.parseKey("!!!not-base64!!!"));
        // 合法 Base64 但解码后不是 32 字节(例如旧的 16 字节固定密钥)
        String shortKey = Base64.getEncoder().encodeToString(
                "xinbot-telemetry".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.parseKey(shortKey));
    }

    @Test
    void recognizesPlaintextKeyRequestHeader() {
        byte[] request = new byte[]{'X', 'B', 'T', 'L',
                PacketDecoder.PROTOCOL_VERSION, PacketDecoder.TYPE_KEY_REQUEST};
        assertTrue(PacketDecoder.isKeyRequest(request));
        // 头部不完整、魔数错误、或密文类型(1/2)都不能误判为密钥请求
        assertFalse(PacketDecoder.isKeyRequest(new byte[]{'X', 'B', 'T', 'L',
                PacketDecoder.PROTOCOL_VERSION}));
        assertFalse(PacketDecoder.isKeyRequest(new byte[]{'X', 'B', 'T', 'X',
                PacketDecoder.PROTOCOL_VERSION, PacketDecoder.TYPE_KEY_REQUEST}));
        assertFalse(PacketDecoder.isKeyRequest(null));
        byte[] heartbeat = new byte[]{'X', 'B', 'T', 'L',
                PacketDecoder.PROTOCOL_VERSION, PacketDecoder.TYPE_HEARTBEAT};
        assertFalse(PacketDecoder.isKeyRequest(heartbeat));
    }

    @Test
    void keyResponseCarriesBase64OfTheDeploymentKey() {
        byte[] reply = PacketDecoder.buildKeyResponse(TEST_KEY);
        assertEquals('X', reply[0]);
        assertEquals('B', reply[1]);
        assertEquals('T', reply[2]);
        assertEquals('L', reply[3]);
        assertEquals(PacketDecoder.PROTOCOL_VERSION, reply[4]);
        assertEquals(PacketDecoder.TYPE_KEY_RESPONSE, reply[5]);
        String body = new String(reply, 6, reply.length - 6, StandardCharsets.US_ASCII);
        assertArrayEquals(TEST_KEY, PacketDecoder.parseKey(body));
        // 应答与请求同属明文控制包,不应被误判为加密信封的密钥请求
        assertFalse(PacketDecoder.isKeyRequest(reply));
    }

    @Test
    void decodeRejectsPlaintextKeyPackets() {
        // 明文控制包走 UdpServer 的分流,直接送入 decode 必须被拒(未知类型)
        byte[] request = new byte[]{'X', 'B', 'T', 'L',
                PacketDecoder.PROTOCOL_VERSION, PacketDecoder.TYPE_KEY_REQUEST};
        assertThrows(IOException.class, () -> PacketDecoder.decode(request, TEST_KEY));
        assertThrows(IOException.class,
                () -> PacketDecoder.decode(PacketDecoder.buildKeyResponse(TEST_KEY), TEST_KEY));
    }
}
