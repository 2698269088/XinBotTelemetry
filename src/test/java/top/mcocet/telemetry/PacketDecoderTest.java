package top.mcocet.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketDecoderTest {

    private static final String HEARTBEAT_JSON = """
            {"type":"heartbeat","timestamp_ms":1700000000000,"bot":"test-bot",
             "online":true,"state":"Game","server":"mc.example.com:25565","players":3,
             "version":"2.4.2-RELEASE"}
            """;

    @Test
    void decodesHeartbeatEnvelope() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8));
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope);
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
                PacketDecoder.TYPE_CRASH, crashJson.getBytes(StandardCharsets.UTF_8));
        PacketDecoder.Packet packet = PacketDecoder.decode(envelope);
        assertEquals(PacketDecoder.TYPE_CRASH, packet.type());
        assertEquals("main", packet.body().path("thread_name").asText());
        assertTrue(packet.body().path("stack_trace").asText().contains("Bot.java"));
    }

    @Test
    void rejectsShortPacket() {
        assertThrows(IOException.class, () -> PacketDecoder.decode(new byte[10]));
    }

    @Test
    void rejectsBadMagic() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, "{}".getBytes(StandardCharsets.UTF_8));
        envelope[0] = 'X';
        envelope[1] = 'B';
        envelope[2] = 'T';
        envelope[3] = 'X'; // 魔数最后一位错误
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope));
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, HEARTBEAT_JSON.getBytes(StandardCharsets.UTF_8));
        envelope[envelope.length - 1] ^= 0x01; // 破坏认证标签
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope));
    }

    @Test
    void rejectsInvalidJson() throws Exception {
        byte[] envelope = PacketDecoder.buildEnvelope(
                PacketDecoder.TYPE_HEARTBEAT, "not-json".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PacketDecoder.decode(envelope));
    }
}
