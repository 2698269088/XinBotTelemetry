package top.mcocet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import top.mcocet.i18n.I18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务端配置:所有配置项均来自 YAML 配置文件(默认 ./config.yml)。
 *
 * <p>首次启动若配置文件不存在,会自动把 jar 内置的默认配置复制一份出来;
 * 读取时文件里缺失的键回落到内置默认值。命令行仅支持 --help 与 --config=路径。</p>
 */
public class Config {

    /** 心跳周期 5 分钟,两个周期未见即判定离线 */
    public static final long DEFAULT_ONLINE_TIMEOUT = 10L * 60 * 1000;
    /** 默认配置文件路径(相对工作目录) */
    public static final String DEFAULT_CONFIG_NAME = "config.yml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 界面语言:zh_cn / zh_tw / en_us */
    public String lang = I18n.LANG_ZH_CN;
    /** 存储类型:sqlite / mysql */
    public String dbType = "sqlite";
    /** SQLite 数据库文件路径 */
    public String sqliteFile = "telemetry.db";
    /** MySQL JDBC 连接串 */
    public String mysqlUrl = "jdbc:mysql://127.0.0.1:3306/xinbot_telemetry";
    public String mysqlUser = "root";
    public String mysqlPassword = "";
    /** UDP 遥测监听端口,0 表示禁用(监听所有网卡) */
    public int udpPort = 9000;
    /** HTTP 服务端口,0 表示禁用(监听所有网卡,提供页面与 POST /telemetry) */
    public int httpPort = 8080;
    /** 心跳超时毫秒数,超过即判定 BOT 离线 */
    public long onlineTimeoutMs = DEFAULT_ONLINE_TIMEOUT;
    /** 遥测加密密钥:Base64 编码的 32 字节随机值(AES-256),留空则使用/生成 telemetry.key 文件 */
    public String telemetryKey = "";
    /** 实际使用的配置文件路径 */
    public String configFile = DEFAULT_CONFIG_NAME;
    public boolean help = false;

    private Config() {
    }

    /**
     * 解析命令行(--help / --config=路径),必要时生成默认配置文件并应用全部配置。
     *
     * @throws IOException 配置文件无法读取、YAML 非法或配置值不合法
     */
    public static Config load(String[] args) throws IOException {
        Config config = new Config();
        String path = DEFAULT_CONFIG_NAME;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                config.help = true;
            } else if (arg.startsWith("--config=")) {
                String custom = arg.substring("--config=".length()).trim();
                if (!custom.isEmpty()) {
                    path = custom;
                }
            } else {
                System.err.println(I18n.get("config.bad_arg", arg));
                config.help = true;
            }
        }
        config.configFile = path;
        if (config.help) {
            // --help 时尽量按配置文件里的语言输出,文件不存在则用默认简体中文
            tryPeekLang(path);
            return config;
        }

        boolean generated = false;
        if (!Files.exists(Paths.get(path))) {
            config.copyDefaultConfig(path);
            generated = true;
        }
        config.apply(path);
        if (generated) {
            System.out.println(I18n.get("config.generated", path));
        }
        return config;
    }

    /** 从 YAML 读取全部配置;文件里缺失的键使用内置默认值 */
    private void apply(String path) throws IOException {
        JsonNode root;
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            root = YAML.readTree(in);
        }
        if (root == null || !root.isObject()) {
            throw new IOException(I18n.get("config.load_failed", path));
        }

        // 语言最先应用,让后续校验信息按用户语言输出
        String cfgLang = text(root, "lang", I18n.LANG_ZH_CN).toLowerCase();
        if (!I18n.supports(cfgLang)) {
            throw new IOException(I18n.get("config.lang_invalid", cfgLang));
        }
        I18n.init(cfgLang);
        lang = cfgLang;

        JsonNode db = root.path("db");
        dbType = text(db, "type", dbType).toLowerCase();
        if (!"sqlite".equals(dbType) && !"mysql".equals(dbType)) {
            throw new IOException(I18n.get("config.db_type_invalid", dbType));
        }
        sqliteFile = text(db, "file", sqliteFile);
        mysqlUrl = text(db, "url", mysqlUrl);
        mysqlUser = text(db, "user", mysqlUser);
        mysqlPassword = text(db, "password", mysqlPassword);

        telemetryKey = text(root, "key", telemetryKey);

        udpPort = intValue(root.path("udp"), "port", udpPort);
        httpPort = intValue(root.path("http"), "port", httpPort);
        onlineTimeoutMs = longValue(root.path("online"), "timeout", onlineTimeoutMs);
        if (udpPort < 0 || httpPort < 0) {
            throw new IOException(I18n.get("config.port_negative"));
        }
    }

    /** 配置文件不存在时,从 classpath 复制内置默认配置 */
    private void copyDefaultConfig(String path) throws IOException {
        Path target = Paths.get(path);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IOException("classpath 中缺少内置默认配置 config.yml");
            }
            Files.copy(in, target);
        }
    }

    /** --help 场景:若配置文件已存在,仅读取其中的 lang 键用于输出语言 */
    private static void tryPeekLang(String path) {
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            JsonNode root = YAML.readTree(in);
            if (root != null) {
                String cfgLang = text(root, "lang", I18n.LANG_ZH_CN).toLowerCase();
                if (I18n.supports(cfgLang)) {
                    I18n.init(cfgLang);
                }
            }
        } catch (Exception ignored) {
            // 读取失败保持默认语言
        }
    }

    private static String text(JsonNode parent, String key, String fallback) {
        JsonNode node = parent.path(key);
        if (node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            return fallback;
        }
        String value = node.asText();
        return value.isBlank() ? fallback : value.trim();
    }

    private static int intValue(JsonNode parent, String key, int fallback) throws IOException {
        JsonNode node = parent.path(key);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isNumber() || !node.canConvertToInt()) {
            throw new IOException(I18n.get("config.bad_number", key, node.asText()));
        }
        return node.asInt();
    }

    private static long longValue(JsonNode parent, String key, long fallback) throws IOException {
        JsonNode node = parent.path(key);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isNumber() || !node.canConvertToLong()) {
            throw new IOException(I18n.get("config.bad_number", key, node.asText()));
        }
        return node.asLong();
    }

    public static String usage() {
        return "\n" + I18n.get("usage.main") + "\n\n"
                + "  " + I18n.get("usage.opt") + "\n\n"
                + I18n.get("usage.note") + "\n"
                + "  " + I18n.get("usage.items_db") + "\n"
                + "  " + I18n.get("usage.items_net") + "\n\n"
                + I18n.get("usage.client") + "\n";
    }

    /** 仅用于启动日志,不含任何密码 */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("config", configFile);
        map.put("lang", lang);
        map.put("db.type", dbType);
        map.put("udp.port", udpPort);
        map.put("http.port", httpPort);
        map.put("online.timeout", onlineTimeoutMs);
        return map;
    }
}
