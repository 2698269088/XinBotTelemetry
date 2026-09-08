package top.mcocet.store;

import top.mcocet.model.CrashRecord;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 崩溃日志存储。连接串建议带上建库参数,首次启动自动建表:
 * jdbc:mysql://host:3306/xinbot_telemetry?createDatabaseIfNotExist=true
 */
public class MySqlCrashStore implements CrashStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS crash_logs (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                bot_name VARCHAR(255) NOT NULL,
                server VARCHAR(255),
                version VARCHAR(64),
                state VARCHAR(16),
                online TINYINT NOT NULL DEFAULT 0,
                player_count INT NOT NULL DEFAULT 0,
                thread_name VARCHAR(255),
                exception TEXT,
                stack_trace MEDIUMTEXT,
                crashed_at BIGINT,
                received_at BIGINT NOT NULL,
                KEY idx_crash_received (received_at)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
            """;

    private static final String INSERT = """
            INSERT INTO crash_logs (bot_name, server, version, state, online, player_count,
                                     thread_name, exception, stack_trace, crashed_at, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COUNT = "SELECT COUNT(*) FROM crash_logs";

    private static final String RECENT =
            "SELECT * FROM crash_logs ORDER BY id DESC LIMIT ?";

    private final String url;
    private final String user;
    private final String password;

    public MySqlCrashStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void init() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection conn = connect();
             PreparedStatement table = conn.prepareStatement(CREATE_TABLE)) {
            table.executeUpdate();
        }
    }

    @Override
    public void save(CrashRecord record) throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(INSERT)) {
            CrashStore.bind(ps, record);
            ps.executeUpdate();
        }
    }

    @Override
    public long countCrashes() throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public List<CrashRecord> recentCrashes(int limit) throws Exception {
        List<CrashRecord> records = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(RECENT)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(CrashRecord.fromRow(rs));
                }
            }
        }
        return records;
    }

    @Override
    public void close() {
        // 连接按操作即开即关,无需持有
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }
}
