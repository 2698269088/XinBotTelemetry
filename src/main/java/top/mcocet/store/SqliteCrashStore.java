package top.mcocet.store;

import top.mcocet.model.CrashRecord;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 崩溃日志存储。数据库文件默认 ./telemetry.db,首次启动自动建表。
 */
public class SqliteCrashStore implements CrashStore {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS crash_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bot_name TEXT NOT NULL,
                server TEXT,
                version TEXT,
                state TEXT,
                online INTEGER NOT NULL DEFAULT 0,
                player_count INTEGER NOT NULL DEFAULT 0,
                thread_name TEXT,
                exception TEXT,
                stack_trace TEXT,
                crashed_at INTEGER,
                received_at INTEGER NOT NULL
            )
            """;

    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_crash_received ON crash_logs (received_at DESC)";

    private static final String INSERT = """
            INSERT INTO crash_logs (bot_name, server, version, state, online, player_count,
                                     thread_name, exception, stack_trace, crashed_at, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COUNT = "SELECT COUNT(*) FROM crash_logs";

    private static final String RECENT =
            "SELECT * FROM crash_logs ORDER BY id DESC LIMIT ?";

    private final String file;

    public SqliteCrashStore(String file) {
        this.file = file;
    }

    @Override
    public void init() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = connect();
             PreparedStatement table = conn.prepareStatement(CREATE_TABLE);
             PreparedStatement index = conn.prepareStatement(CREATE_INDEX)) {
            table.executeUpdate();
            index.executeUpdate();
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
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }
}
