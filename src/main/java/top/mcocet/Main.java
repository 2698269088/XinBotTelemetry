package top.mcocet;

import top.mcocet.core.BotRegistry;
import top.mcocet.http.WebServer;
import top.mcocet.i18n.I18n;
import top.mcocet.net.UdpServer;
import top.mcocet.store.CrashStore;
import top.mcocet.telemetry.PacketDecoder;
import top.mcocet.telemetry.TelemetryHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * XinBot 遥测服务端入口。
 *
 * <pre>
 * java -jar XinBotTelemetry.jar                          # 自动生成并读取 config.properties
 * java -jar XinBotTelemetry.jar --config=my.conf         # 指定其它配置文件
 * </pre>
 * 数据库类型、监听端口等全部配置项见 config.properties(首次启动自动生成)。
 */
public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    /** 部署密钥文件:未在 config.yml 配置 key 时的读取/生成位置 */
    private static final String KEY_FILE = "telemetry.key";

    public static void main(String[] args) throws Exception {
        Config config;
        try {
            config = Config.load(args);
        } catch (Exception e) {
            System.err.println(I18n.get("config.load_failed", e.getMessage()));
            System.exit(1);
            return;
        }
        if (config.help) {
            System.out.println(Config.usage());
            System.exit(1);
        }
        configureLogging();

        // 存储:SQLite / MySQL,自动建表
        CrashStore store = CrashStore.create(config);
        try {
            store.init();
        } catch (Exception e) {
            log.log(Level.SEVERE, I18n.get("main.store_init_failed", config.dbType), e);
            System.exit(1);
        }

        BotRegistry registry = new BotRegistry(config.onlineTimeoutMs);
        byte[] key = resolveTelemetryKey(config);
        TelemetryHandler handler = new TelemetryHandler(registry, store, key);

        UdpServer udpServer = config.udpPort > 0 ? new UdpServer(config.udpPort, handler) : null;
        if (udpServer != null) {
            udpServer.start();
        }

        WebServer webServer = config.httpPort > 0
                ? new WebServer(config.httpPort, registry, store, handler,
                Base64.getEncoder().encodeToString(key)) : null;
        if (webServer != null) {
            webServer.start();
        }

        if (udpServer == null && webServer == null) {
            log.severe(I18n.get("main.no_listener"));
            System.exit(1);
        }

        // 定期清理超过 7 天未上报的 BOT 记录
        ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "registry-janitor");
            thread.setDaemon(true);
            return thread;
        });
        janitor.scheduleWithFixedDelay(registry::purgeOld, 60, 60, TimeUnit.SECONDS);

        log.info(I18n.get("main.store_ready", config.dbType,
                config.onlineTimeoutMs / 1000, config.asMap()));
        log.info(I18n.get("main.ready"));

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info(I18n.get("main.stopping"));
            if (udpServer != null) {
                udpServer.close();
            }
            if (webServer != null) {
                webServer.close();
            }
            handler.shutdown();
            janitor.shutdownNow();
            try {
                store.close();
            } catch (Exception e) {
                log.log(Level.WARNING, "关闭存储失败", e);
            }
        }, "shutdown"));
        // 主线程挂起,由 Ctrl+C / shutdown hook 结束进程
        latch.await();
    }

    /**
     * 解析或生成部署密钥。config.yml 的 key 优先;留空时读取已有的 telemetry.key
     * 文件,文件不存在则生成新的 32 字节随机密钥(Base64 编码)并写入该文件以便
     * 重启复用——与 LS Chat 服务端首启自动生成证书并持久化的模式一致。新生成的
     * 密钥只打印一次,需复制到各客户端 config.conf 的 telemetry.key,之后妥善
     * 保存该文件(轮换时直接更新两端即可)。密钥错误会直接退出(fail-closed)。
     */
    private static byte[] resolveTelemetryKey(Config config) {
        if (config.telemetryKey != null && !config.telemetryKey.isBlank()) {
            try {
                byte[] key = PacketDecoder.parseKey(config.telemetryKey);
                log.info(I18n.get("main.key.from_config"));
                return key;
            } catch (IllegalArgumentException e) {
                log.log(Level.SEVERE, I18n.get("main.key.config_invalid", e.getMessage()));
                System.exit(1);
            }
        }

        Path keyFile = Paths.get(KEY_FILE);
        try {
            if (Files.isRegularFile(keyFile)) {
                byte[] key = PacketDecoder.parseKey(
                        Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
                log.info(I18n.get("main.key.file_loaded", keyFile));
                return key;
            }
            byte[] raw = new byte[32]; // 必须与 PacketDecoder.parseKey 的 32 字节约束一致
            new SecureRandom().nextBytes(raw);
            String encoded = Base64.getEncoder().encodeToString(raw);
            Files.writeString(keyFile, encoded + System.lineSeparator(),
                    StandardCharsets.US_ASCII);
            log.warning(I18n.get("main.key.file_generated", keyFile, encoded));
            return raw;
        } catch (IllegalArgumentException e) {
            log.log(Level.SEVERE, I18n.get("main.key.file_invalid", keyFile, e.getMessage()));
        } catch (IOException e) {
            log.log(Level.SEVERE, I18n.get("main.key.file_failed", keyFile, e.getMessage()));
        }
        System.exit(1);
        return null; // unreachable, System.exit never returns
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (java.util.logging.Handler handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                handler.setLevel(Level.INFO);
            }
        }
    }
}
