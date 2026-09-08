package top.mcocet.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.mcocet.i18n.I18n;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 遥测信封解码器,协议与 Xinbot 客户端 TelemetryManager 保持一致:
 *
 * <pre>
 *   [0..3]   magic "XBTL"
 *   [4]      协议版本 (1)
 *   [5]      消息类型 (1=心跳 heartbeat, 2=崩溃报告 crash)
 *   [6..17]  AES-GCM 12 字节随机 IV
 *   [18..]   AES-GCM 密文(明文 JSON + 16 字节认证标签)
 * </pre>
 *
 * 前 6 个头部字节(magic + version + type)作为 GCM AAD 绑定到密文,单独篡改
 * 类型字节会使认证失败;解密后还会校验负载 JSON 的 type 字段与信封类型一致。
 * 加密密钥是部署特定的:由 config.yml 的 key 提供(Base64 编码的 32 字节随机值,
 * AES-256),或首次启动时自动生成到 telemetry.key 文件。本类不再内置任何默认密钥。
 *
 * 弱化模式(明文密钥交换):客户端把 telemetry.key 留空时,发送 6 字节明文请求
 * (magic + version + type=KEY_REQUEST)索取部署密钥;服务端回送 magic + version +
 * type=KEY_RESPONSE + Base64 编码密钥。HTTP 通道的等价端点为 GET /telemetry/key。
 * 该交换不加密,仅建议在可信网络启用;显式配置密钥的客户端不会走此通道。
 */
public final class PacketDecoder {

    public static final byte[] MAGIC = {'X', 'B', 'T', 'L'};
    public static final byte PROTOCOL_VERSION = 1;
    public static final byte TYPE_HEARTBEAT = 1;
    public static final byte TYPE_CRASH = 2;
    /** 明文密钥请求(仅 6 字节头部,无 body),见类注释的弱化模式 */
    public static final byte TYPE_KEY_REQUEST = 3;
    /** 明文密钥应答,body 为 Base64 编码的部署密钥 */
    public static final byte TYPE_KEY_RESPONSE = 4;

    /** AES-256 密钥长度:配置值是 Base64 编码的恰好 32 字节随机数 */
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEADER_LENGTH = 18; // 4 magic + 1 version + 1 type + 12 iv
    /** 明文控制包头部长度(magic + version + type),无 IV、无密文 */
    private static final int CONTROL_HEADER_LENGTH = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PacketDecoder() {
    }

    /** 解码结果:消息类型与明文 JSON 负载 */
    public record Packet(byte type, JsonNode body) {
    }

    /**
     * 校验信封头并解密,返回类型与 JSON 负载。
     *
     * @param envelope 收到的原始信封
     * @param key      部署特定的 32 字节 AES-256 密钥(与客户端 telemetry.key 一致)
     * @throws IOException 包格式非法、魔数/版本不符、负载与信封类型不一致或解密失败(AEAD 认证失败)
     */
    public static Packet decode(byte[] envelope, byte[] key) throws IOException {
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
        byte[] plaintext = decrypt(ciphertext, iv, headerAad(type), key);

        final JsonNode body;
        try {
            body = MAPPER.readTree(plaintext);
        } catch (IOException e) {
            throw new IOException(I18n.get("dec.bad_json"), e);
        }
        // 信封头与负载由同一份 AAD 认证,两者必须互相印证
        String jsonType = body.path("type").isValueNode() ? body.path("type").asText() : "";
        if (!typeLabel(type).equals(jsonType)) {
            throw new IOException(I18n.get("dec.type_mismatch", typeLabel(type), jsonType));
        }
        return new Packet(type, body);
    }

    /** 判断数据报是否为明文密钥请求(magic + version + type = KEY_REQUEST)。 */
    public static boolean isKeyRequest(byte[] data) {
        return data != null && data.length >= CONTROL_HEADER_LENGTH
                && data[0] == MAGIC[0] && data[1] == MAGIC[1]
                && data[2] == MAGIC[2] && data[3] == MAGIC[3]
                && data[4] == PROTOCOL_VERSION
                && data[5] == TYPE_KEY_REQUEST;
    }

    /** 构建明文密钥应答:头部 + Base64 编码的部署密钥(无换行)。 */
    public static byte[] buildKeyResponse(byte[] key) {
        byte[] encoded = Base64.getEncoder().encodeToString(key)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] reply = new byte[CONTROL_HEADER_LENGTH + encoded.length];
        System.arraycopy(MAGIC, 0, reply, 0, 4);
        reply[4] = PROTOCOL_VERSION;
        reply[5] = TYPE_KEY_RESPONSE;
        System.arraycopy(encoded, 0, reply, CONTROL_HEADER_LENGTH, encoded.length);
        return reply;
    }

    /**
     * 解析部署密钥:Base64 编码的恰好 32 字节(AES-256)。
     *
     * @throws IllegalArgumentException 为空、不是合法 Base64 或解码后长度不为 32 字节
     */
    public static byte[] parseKey(String configured) {
        String text = configured == null ? "" : configured.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not valid Base64: " + e.getMessage());
        }
        if (decoded.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "expected 32 bytes after Base64 decoding, got " + decoded.length);
        }
        return decoded;
    }

    private static String typeLabel(byte type) {
        return switch (type) {
            case TYPE_HEARTBEAT -> "heartbeat";
            case TYPE_CRASH -> "crash";
            default -> "unknown";
        };
    }

    /** GCM AAD:IV 之前的头部字节,即 magic + version + type */
    private static byte[] headerAad(byte type) {
        return new byte[]{MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], PROTOCOL_VERSION, type};
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] iv, byte[] aad, byte[] key) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IOException(I18n.get("dec.decrypt_failed"), e);
        }
    }

    /** 仅供单元测试与参考实现使用:按同一协议加密一段明文(头部字节同样作为 AAD) */
    static byte[] buildEnvelope(byte type, byte[] plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(headerAad(type));
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
