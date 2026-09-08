package top.mcocet.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import top.mcocet.core.BotRegistry;
import top.mcocet.core.BotStatus;
import top.mcocet.i18n.I18n;
import top.mcocet.model.CrashRecord;
import top.mcocet.store.CrashStore;
import top.mcocet.telemetry.TelemetryHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP 服务(绑定 0.0.0.0,即监听所有网卡):
 *   GET  /             监控页面(BOT 在线数量,文案随 config.yml 的 lang 渲染)
 *   GET  /api/stats    在线统计 JSON
 *   GET  /api/bots     全部 BOT 状态 JSON
 *   GET  /api/crashes  最近崩溃日志 JSON(?limit=N)
 *   GET  /telemetry/key 弱化模式:向客户端明文下发部署密钥(见 PacketDecoder 类注释)
 *   POST /telemetry    接收 HTTP 模式的遥测信封(与 UDP 相同二进制格式)
 */
public class WebServer implements AutoCloseable {

    private static final Logger log = Logger.getLogger(WebServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 页面模板中需要替换为当前语言文案的占位 key(I18n key 全集) */
    private static final String[] PAGE_TEXT_KEYS = {
            "page.title", "page.sub_load", "page.refresh",
            "page.card_online", "page.card_offline", "page.card_crashed", "page.card_logs",
            "page.bot_list", "page.th_server", "page.th_status", "page.th_state",
            "page.th_players", "page.th_version", "page.th_source", "page.th_last", "page.th_uptime",
            "page.recent_crashes", "page.th_time", "page.th_exception",
            "page.st_online", "page.st_offline", "page.st_crashed",
            "page.empty_bots", "page.empty_crashes", "page.meta_rule",
            "page.unit_day", "page.unit_hour", "page.unit_minute"
    };

    private final int port;
    private final BotRegistry registry;
    private final CrashStore store;
    private final TelemetryHandler telemetryHandler;
    /** Base64 编码的部署密钥,弱化模式下经 GET /telemetry/key 明文下发给客户端 */
    private final String telemetryKeyText;
    private HttpServer server;
    private String pageHtml;

    public WebServer(int port, BotRegistry registry, CrashStore store,
                     TelemetryHandler telemetryHandler, String telemetryKeyText) {
        this.port = port;
        this.registry = registry;
        this.store = store;
        this.telemetryHandler = telemetryHandler;
        this.telemetryKeyText = telemetryKeyText;
    }

    public void start() throws IOException {
        pageHtml = renderPage();
        server = HttpServer.create(new InetSocketAddress(port), 0); // 0.0.0.0 = 所有网卡
        server.createContext("/", this::handleRoot);
        server.createContext("/api/stats", this::handleStats);
        server.createContext("/api/bots", this::handleBots);
        server.createContext("/api/crashes", this::handleCrashes);
        server.createContext("/telemetry/key", this::handleTelemetryKey);
        server.createContext("/telemetry", this::handleTelemetry);
        server.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "http-worker");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        log.info(I18n.get("http.started", port, port));
    }

    /** 把页面模板中的 {page.xxx} 占位符替换为当前语言文案 */
    private static String renderPage() {
        String html = PAGE_HTML;
        for (String key : PAGE_TEXT_KEYS) {
            html = html.replace("{" + key + "}", I18n.get(key));
        }
        return html;
    }

    // ---------- 请求处理 ----------

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", pageHtml);
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{\"error\":\"method\"}");
            return;
        }
        try {
            BotRegistry.Stats stats = registry.stats();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("online", stats.online());
            body.put("offline", stats.offline());
            body.put("crashed", stats.crashed());
            body.put("total_crashes", store.countCrashes());
            body.put("online_timeout_ms", registry.getOnlineTimeoutMs());
            body.put("now", System.currentTimeMillis());
            respond(exchange, 200, "application/json", MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            respondError(exchange, e);
        }
    }

    private void handleBots(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{\"error\":\"method\"}");
            return;
        }
        try {
            long now = System.currentTimeMillis();
            List<Map<String, Object>> list = new ArrayList<>();
            for (BotStatus bot : registry.snapshot()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", bot.getName());
                item.put("server", bot.getServer());
                item.put("online", bot.isOnline(now, registry.getOnlineTimeoutMs()));
                item.put("crashed", bot.isCrashed());
                item.put("crash_exception", bot.getCrashException());
                item.put("last_crash_ms", bot.getLastCrashMs());
                item.put("state", bot.getState());
                item.put("players", bot.getPlayers());
                item.put("version", bot.getVersion());
                item.put("source_ip", bot.getSourceIp());
                item.put("first_seen_ms", bot.getFirstSeenMs());
                item.put("last_seen_ms", bot.getLastSeenMs());
                item.put("uptime_ms", bot.getUptimeMs());
                item.put("os_name", bot.getOsName());
                item.put("java_version", bot.getJavaVersion());
                list.add(item);
            }
            respond(exchange, 200, "application/json", MAPPER.writeValueAsString(list));
        } catch (Exception e) {
            respondError(exchange, e);
        }
    }

    private void handleCrashes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{\"error\":\"method\"}");
            return;
        }
        try {
            int limit = 15;
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2 && "limit".equals(kv[0])) {
                        try {
                            limit = Math.min(200, Math.max(1, Integer.parseInt(kv[1])));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (CrashRecord record : store.recentCrashes(limit)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", record.id());
                item.put("bot_name", record.botName());
                item.put("server", record.server());
                item.put("version", record.version());
                item.put("exception", record.exception());
                item.put("thread_name", record.threadName());
                item.put("stack_trace", record.stackTrace());
                item.put("received_at", record.receivedAt());
                item.put("crashed_at", record.crashedAt());
                list.add(item);
            }
            respond(exchange, 200, "application/json", MAPPER.writeValueAsString(list));
        } catch (Exception e) {
            respondError(exchange, e);
        }
    }

    /** 客户端 mode="http" 时把遥测信封 POST 到这里,body 与 UDP 载荷相同 */
    private void handleTelemetry(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        byte[] envelope = exchange.getRequestBody().readAllBytes();
        String sourceIp = exchange.getRemoteAddress().getAddress() == null
                ? "?" : exchange.getRemoteAddress().getAddress().getHostAddress();
        telemetryHandler.handleEnvelope(envelope, sourceIp);
        respond(exchange, 200, "text/plain", "ok");
    }

    /** 弱化模式:客户端 mode="http" 且 telemetry.key 留空时,从此端点自动获取部署密钥(明文) */
    private void handleTelemetryKey(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        respond(exchange, 200, "text/plain; charset=utf-8", telemetryKeyText);
    }

    // ---------- 输出工具 ----------

    private static void respond(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondError(HttpExchange exchange, Exception e) throws IOException {
        log.log(Level.SEVERE, I18n.get("api.error"), e);
        respond(exchange, 500, "application/json",
                "{\"error\":\"" + e.getMessage() + "\"}");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------- 监控页面模板 ----------
    // 所有 {page.xxx} 占位符在 start() 时按 config.yml 的 lang 替换为对应语言;
    // JS 运行时还会替换 meta 文案里的 {timeout} {total} {time}。

    private static final String PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>{page.title}</title>
            <style>
              :root { --bg:#0f1115; --card:#181b22; --line:#262b36; --text:#e6e9ef; --dim:#8b93a5;
                      --green:#34d399; --red:#f87171; --amber:#fbbf24; --blue:#60a5fa; }
              * { box-sizing:border-box; }
              body { margin:0; background:var(--bg); color:var(--text); font:14px/1.6 "Microsoft YaHei",system-ui,sans-serif; }
              .wrap { max-width:1100px; margin:0 auto; padding:20px; }
              h1 { font-size:20px; margin:0 0 4px; }
              .sub { color:var(--dim); font-size:12px; margin-bottom:16px; }
              .cards { display:flex; gap:14px; flex-wrap:wrap; margin-bottom:18px; }
              .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px 22px; min-width:140px; }
              .card .num { font-size:30px; font-weight:700; }
              .card .lbl { color:var(--dim); font-size:12px; }
              .online .num { color:var(--green); } .offline .num { color:var(--dim); }
              .crashed .num { color:var(--red); } .logs .num { color:var(--amber); }
              table { width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:10px; overflow:hidden; }
              th,td { padding:8px 10px; text-align:left; border-bottom:1px solid var(--line); font-size:13px; }
              th { background:#1d212b; color:var(--dim); font-weight:600; white-space:nowrap; }
              tr:last-child td { border-bottom:none; }
              .tag { display:inline-block; padding:1px 8px; border-radius:99px; font-size:12px; }
              .tag.on { background:#0d2b21; color:var(--green); }
              .tag.off { background:#23262e; color:var(--dim); }
              .tag.crash { background:#33151a; color:var(--red); }
              .sec { margin:22px 0 10px; font-size:15px; }
              .mono { font-family:Consolas,monospace; font-size:12px; }
              .dim { color:var(--dim); }
              .ellipsis { max-width:340px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
              #refreshBtn { float:right; background:#242937; color:var(--text); border:1px solid var(--line);
                            border-radius:8px; padding:5px 14px; cursor:pointer; font-size:13px; }
              #refreshBtn:hover { background:#2d3342; }
              .empty { color:var(--dim); padding:14px; text-align:center; }
            </style>
            </head>
            <body>
            <div class="wrap">
              <h1>{page.title}</h1>
              <div class="sub" id="meta">{page.sub_load}</div>
              <button id="refreshBtn" onclick="refreshAll()">{page.refresh}</button>
              <div class="cards">
                <div class="card online"><div class="lbl">{page.card_online}</div><div class="num" id="cOnline">-</div></div>
                <div class="card offline"><div class="lbl">{page.card_offline}</div><div class="num" id="cOffline">-</div></div>
                <div class="card crashed"><div class="lbl">{page.card_crashed}</div><div class="num" id="cCrashed">-</div></div>
                <div class="card logs"><div class="lbl">{page.card_logs}</div><div class="num" id="cLogs">-</div></div>
              </div>

              <div class="sec">{page.bot_list}</div>
              <table>
                <thead><tr><th>BOT</th><th>{page.th_server}</th><th>{page.th_status}</th><th>{page.th_state}</th><th>{page.th_players}</th><th>{page.th_version}</th><th>{page.th_source}</th><th>{page.th_last}</th><th>{page.th_uptime}</th></tr></thead>
                <tbody id="botsBody"><tr><td colspan="9" class="empty">{page.empty_bots}</td></tr></tbody>
              </table>

              <div class="sec">{page.recent_crashes}</div>
              <table>
                <thead><tr><th>{page.th_time}</th><th>BOT</th><th>{page.th_server}</th><th>{page.th_exception}</th></tr></thead>
                <tbody id="crashBody"><tr><td colspan="4" class="empty">{page.empty_crashes}</td></tr></tbody>
              </table>
            </div>
            <script>
              const L = {
                online: "{page.st_online}", offline: "{page.st_offline}", crashed: "{page.st_crashed}",
                emptyBots: "{page.empty_bots}", emptyCrashes: "{page.empty_crashes}",
                d: "{page.unit_day}", h: "{page.unit_hour}", m: "{page.unit_minute}"
              };
              function fmtTime(ms) {
                if (!ms) return "-";
                const d = new Date(ms);
                const p = n => String(n).padStart(2, "0");
                return d.getFullYear() + "-" + p(d.getMonth()+1) + "-" + p(d.getDate())
                     + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
              }
              function fmtUptime(ms) {
                if (!ms) return "-";
                const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
                return (d ? d + L.d : "") + h + L.h + m + L.m;
              }
              function td(text, cls) { const cell = document.createElement("td"); if (cls) cell.className = cls;
                                       cell.textContent = text == null ? "-" : text; return cell; }
              function renderBots(list) {
                const body = document.getElementById("botsBody"); body.innerHTML = "";
                if (!list.length) { const tr = document.createElement("tr"); const td = document.createElement("td");
                                    td.colSpan = 9; td.className = "empty"; td.textContent = L.emptyBots; tr.appendChild(td); body.appendChild(tr); return; }
                for (const b of list) {
                  const tr = document.createElement("tr");
                  const st = b.crashed ? L.crashed : (b.online ? L.online : L.offline);
                  const cls = b.crashed ? "crash" : (b.online ? "on" : "off");
                  const stTd = document.createElement("td");
                  const tag = document.createElement("span"); tag.className = "tag " + cls; tag.textContent = st;
                  stTd.appendChild(tag);
                  tr.appendChild(td(b.name));
                  tr.appendChild(td(b.server, "mono"));
                  tr.appendChild(stTd);
                  tr.appendChild(td(b.state));
                  tr.appendChild(td(b.players));
                  tr.appendChild(td(b.version, "mono"));
                  tr.appendChild(td(b.source_ip, "mono"));
                  tr.appendChild(td(fmtTime(b.last_seen_ms)));
                  tr.appendChild(td(fmtUptime(b.uptime_ms)));
                  body.appendChild(tr);
                }
              }
              function renderCrashes(list) {
                const body = document.getElementById("crashBody"); body.innerHTML = "";
                if (!list.length) { const tr = document.createElement("tr"); const td = document.createElement("td");
                                    td.colSpan = 4; td.className = "empty"; td.textContent = L.emptyCrashes; tr.appendChild(td); body.appendChild(tr); return; }
                for (const c of list) {
                  const tr = document.createElement("tr");
                  const exTd = document.createElement("td");
                  exTd.className = "ellipsis mono"; exTd.textContent = c.exception || "-";
                  exTd.title = (c.thread_name || "") + "\\n" + (c.stack_trace || "");
                  tr.appendChild(td(fmtTime(c.received_at)));
                  tr.appendChild(td(c.bot_name));
                  tr.appendChild(td(c.server, "mono"));
                  tr.appendChild(exTd);
                  body.appendChild(tr);
                }
              }
              function refreshAll() {
                fetch("api/stats").then(r => r.json()).then(s => {
                  document.getElementById("cOnline").textContent = s.online;
                  document.getElementById("cOffline").textContent = s.offline;
                  document.getElementById("cCrashed").textContent = s.crashed;
                  document.getElementById("cLogs").textContent = s.total_crashes;
                  document.getElementById("meta").textContent = "{page.meta_rule}"
                    .replace("{timeout}", s.online_timeout_ms / 60000)
                    .replace("{total}", s.total_crashes)
                    .replace("{time}", fmtTime(s.now));
                }).catch(() => {});
                fetch("api/bots").then(r => r.json()).then(renderBots).catch(() => {});
                fetch("api/crashes?limit=15").then(r => r.json()).then(renderCrashes).catch(() => {});
              }
              refreshAll();
              setInterval(refreshAll, 10000);
            </script>
            </body>
            </html>
            """;
}
