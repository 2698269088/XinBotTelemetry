package top.mcocet.store;

import top.mcocet.Config;
import top.mcocet.model.CrashRecord;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 崩溃日志存储抽象,支持 SQLite / MySQL 两种实现。
 * 写操作应串行调用(SQLite 并发写会锁库)。
 */
public interface CrashStore extends AutoCloseable {

    /** 建库建表(幂等) */
    void init() throws Exception;

    /** 写入一条崩溃日志 */
    void save(CrashRecord record) throws Exception;

    /** 累计崩溃条数 */
    long countCrashes() throws Exception;

    /** 最近 limit 条崩溃日志(按入库顺序倒序) */
    List<CrashRecord> recentCrashes(int limit) throws Exception;

    @Override
    void close() throws Exception;

    /** 按配置创建对应实现 */
    static CrashStore create(Config config) {
        if ("mysql".equalsIgnoreCase(config.dbType)) {
            return new MySqlCrashStore(config.mysqlUrl, config.mysqlUser, config.mysqlPassword);
        }
        return new SqliteCrashStore(config.sqliteFile);
    }

    /** 把记录绑定到两存储实现共用的 INSERT 语句 */
    static void bind(PreparedStatement ps, CrashRecord r) throws Exception {
        ps.setString(1, r.botName());
        ps.setString(2, r.server());
        ps.setString(3, r.version());
        ps.setString(4, r.state());
        ps.setInt(5, r.online() ? 1 : 0);
        ps.setInt(6, r.playerCount());
        ps.setString(7, r.threadName());
        ps.setString(8, r.exception());
        ps.setString(9, r.stackTrace());
        ps.setLong(10, r.crashedAt());
        ps.setLong(11, r.receivedAt());
    }
}
