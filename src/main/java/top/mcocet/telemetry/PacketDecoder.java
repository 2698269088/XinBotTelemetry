package top.mcocet.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.mcocet.i18n.I18n;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 遥测信封解码器,协议与 Xinbot 客户端 TelemetryManager 保持一致:
 *
 * <pre>
 *   [0..3]   magic "XBTL"
 *   [4]      协议版本 (1)
 *   [5]      消息类型 (1=心跳 heartbeat, 2=崩溃报告 crash)
 *   [6..17]  AES-GCM 12 字节随机 IV
 *   [18..]   AES-128-GCM 密文(明文 JSON + 16 字节认证标签)
 * </pre>
 *
 * 固定密钥与客户端一致: "xinbot-telemetry"(16 字节, AES-128)
 */
public final class PacketDecoder {

    public static final byte[] MAGIC = {'X', 'B', 'T', 'L'};
    public static final byte PROTOCOL_VERSION = 1;
    public static final byte TYPE_HEARTBEAT = 1;
    public static final byte TYPE_CRASH = 2;

    /** 与客户端固定一致的 AES-128 密钥 */
    static final byte[] ENCRYPTION_KEY = "xinbot-telemetry".getBytes(StandardCharsets.US_ASCII);

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEADER_LENGTH = 18; // 4 magic + 1 version + 1 type + 12 iv

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PacketDecoder() {
    }

    /** 解码结果:消息类型与明文 JSON 负载 */
    public record Packet(byte type, JsonNode body) {
    }

    /**
     * 校验信封头并解密,返回类型与 JSON 负载。
     *
     * @throws IOException 包格式非法、魔数/版本不符或解密失败(AEAD 认证失败)
     */
    public static Packet decode(byte[] envelope) throws IOException {
        if (envelope.length < HEADER_LENGTH) {
            throw new IOException(I18n.get("dec.packet_short", envelope.length));
        }
        if (envelope[0] != MAGIC[0] || envelope[1] != MAGIC[1]
                || envelope[2] != MAGIC[2] || envelope[3] != MAGIC[3]) {
            throw new IOException(I18n.get("dec.bad_magic"));
        }
        if (envelope[4] != PROTOCOL_VERSION) {
            throw new IOException(I18n.get("dec.bad_version", envelope[4]));
        }
        byte type = envelope[5];
        if (type != TYPE_HEARTBEAT && type != TYPE_CRASH) {
            throw new IOException(I18n.get("dec.unknown_type", type));
        }

        byte[] iv = Arrays.copyOfRange(envelope, 6, HEADER_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(envelope, HEADER_LENGTH, envelope.length);
        byte[] plaintext = decrypt(ciphertext, iv);

        try {
            return new Packet(type, MAPPER.readTree(plaintext));
        } catch (IOException e) {
            throw new IOException(I18n.get("dec.bad_json"), e);
        }
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IOException(I18n.get("dec.decrypt_failed"), e);
        }
    }

    /** 仅供单元测试使用:按同一协议加密一段明文 */
    static byte[] buildEnvelope(byte type, byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(ENCRYPTION_KEY, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] envelope = new byte[HEADER_LENGTH + ciphertext.length];
        System.arraycopy(MAGIC, 0, envelope, 0, 4);
        envelope[4] = PROTOCOL_VERSION;
        envelope[5] = type;
        System.arraycopy(iv, 0, envelope, 6, IV_LENGTH);
        System.arraycopy(ciphertext, 0, envelope, HEADER_LENGTH, ciphertext.length);
        return envelope;
    }
}
