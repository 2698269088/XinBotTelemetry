package top.mcocet;

import top.mcocet.core.BotRegistry;
import top.mcocet.http.WebServer;
import top.mcocet.i18n.I18n;
import top.mcocet.net.UdpServer;
import top.mcocet.store.CrashStore;
import top.mcocet.telemetry.TelemetryHandler;

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
        TelemetryHandler handler = new TelemetryHandler(registry, store);

        UdpServer udpServer = config.udpPort > 0 ? new UdpServer(config.udpPort, handler) : null;
        if (udpServer != null) {
            udpServer.start();
        }

        WebServer webServer = config.httpPort > 0
                ? new WebServer(config.httpPort, registry, store, handler) : null;
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
