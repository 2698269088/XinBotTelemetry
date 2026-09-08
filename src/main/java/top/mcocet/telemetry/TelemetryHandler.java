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
    private final ExecutorService crashWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "crash-writer");
        thread.setDaemon(true);
        return thread;
    });

    public TelemetryHandler(BotRegistry registry, CrashStore store) {
        this.registry = registry;
        this.store = store;
    }

    /** 处理一个收到的原始信封(来源地址用于展示,如 udp 客户端 IP) */
    public void handleEnvelope(byte[] envelope, String sourceIp) {
        PacketDecoder.Packet packet;
        try {
            packet = PacketDecoder.decode(envelope);
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
