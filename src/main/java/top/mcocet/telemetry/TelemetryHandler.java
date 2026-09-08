package top.mcocet.telemetry;

import top.mcocet.core.BotRegistry;
import top.mcocet.i18n.I18n;
import top.mcocet.model.CrashRecord;
import top.mcocet.store.CrashStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 遥测处理管线:UDP 与 HTTP 两个入口共用。
 * 心跳 -> 更新在线注册表;崩溃报告 -> 标记 BOT 崩溃并异步入库(串行写,兼容 SQLite)。
 */
public class TelemetryHandler {

    private static final Logger log = Logger.getLogger(TelemetryHandler.class.getName());

    private final BotRegistry registry;
    private final CrashStore store;
    /** 部署特定的 AES-256 密钥(与客户端 telemetry.key 一致) */
    private final byte[] key;
    private final ExecutorService crashWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crash-writer");
        thread.setDaemon(true);
        return thread;
    });

    public TelemetryHandler(BotRegistry registry, CrashStore store, byte[] key) {
        this.registry = registry;
        this.store = store;
        this.key = key;
    }

    /** 处理一个收到的原始信封(来源地址用于展示,如 udp 客户端 IP) */
    public void handleEnvelope(byte[] envelope, String sourceIp) {
        PacketDecoder.Packet packet;
        try {
            packet = PacketDecoder.decode(envelope, key);
        } catch (IOException e) {
            log.warning(I18n.get("handler.drop", sourceIp, e.getMessage()));
            return;
        }

        long now = System.currentTimeMillis();
        switch (packet.type()) {
            case PacketDecoder.TYPE_HEARTBEAT ->
                    registry.onHeartbeat(packet.body(), sourceIp, now);
            case PacketDecoder.TYPE_CRASH -> {
                registry.onCrash(packet.body(), sourceIp, now);
                CrashRecord record = CrashRecord.from(packet.body(), now);
                crashWriter.execute(() -> {
                    try {
                        store.save(record);
                        log.info(I18n.get("handler.crash_saved",
                                record.botName(), record.server(), record.exception()));
                    } catch (Exception e) {
                        log.log(Level.SEVERE, I18n.get("handler.crash_save_failed", record), e);
                    }
                });
            }
            default -> log.warning(I18n.get("dec.unknown_type", packet.type()));
        }
    }

    /**
     * UDP 入口:明文密钥请求直接应答,其余数据交给 {@link #handleEnvelope}。
     *
     * @return 需要回送给来源的数据报;无需应答时返回 null
     */
    public byte[] handleDatagram(byte[] data, String sourceIp) {
        if (PacketDecoder.isKeyRequest(data)) {
            log.info(I18n.get("handler.key_requested", sourceIp));
            return PacketDecoder.buildKeyResponse(key);
        }
        handleEnvelope(data, sourceIp);
        return null;
    }

    /** 优雅停机:等队列中尚未落库的崩溃日志写完(守护线程,不等待会随 JVM 丢失) */
    public void shutdown() {
        crashWriter.shutdown();
        try {
            crashWriter.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
