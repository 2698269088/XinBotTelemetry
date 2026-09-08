# XinBotTelemetry

[Xinbot](https://github.com/huangdihd/xinbot) Minecraft 机器人客户端的**遥测服务端**。
它通过 UDP 或 HTTP 接收来自 Xinbot 客户端的**加密心跳与崩溃报告**,在自带的网页监控页面上
展示**哪些 BOT 在线**,并将**崩溃日志持久化**到 SQLite 或 MySQL。

English version: [README.md](README.md)

## 功能特性

- **监听所有网卡**(`0.0.0.0`),同时支持 UDP 与 HTTP 两个入口
- **按 BOT 维护在线 / 离线 / 已崩溃状态** —— 以 `bot@服务器` 为键;超过 `online.timeout`
  (默认 10 分钟,即客户端 5 分钟心跳的两倍)未收到心跳即判定离线;收到崩溃报告立即视为
  离线(下次心跳自动恢复在线)
- **网页监控面板**:在线数量卡片 + BOT 列表 + 最近崩溃日志,每 10 秒自动刷新
- **崩溃日志入库**:默认 SQLite,也可切换 MySQL;首次启动自动建库建表
- **多语言支持**:控制台日志、帮助文本与监控页面均支持 `zh_cn`(默认简体中文)、`zh_tw`(繁体中文)、
  `en_us`(英文);语言文件与 Xinbot 核心同款 `.lang` 格式(`en_us` 作为兜底基座)
- **全部配置走 `config.yml`**(UTF-8 YAML,带注释),首次启动自动生成;命令行仅保留
  `--help` 与 `--config=路径`
- **与客户端 `TelemetryManager` 协议完全一致**:固定 AES-128-GCM 密钥、带认证的加密信封,可防篡改

## 环境要求

- Java 17 或更高版本

## 构建与运行

```bash
mvn package                      # 产出 target/XinBotTelemetry.jar
java -jar target/XinBotTelemetry.jar
java -jar target/XinBotTelemetry.jar --config=/path/to/config.yml
```

首次启动时若 jar 同目录下没有 **`config.yml`**,会自动生成一份默认配置;修改后重启生效。

`mvn test` 可运行信封解码单元测试。

## 配置项(`config.yml`)

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `lang` | `zh_cn` | 界面语言:`zh_cn` / `zh_tw` / `en_us` |
| `udp.port` | `9000` | UDP 遥测监听端口(`0` 禁用) |
| `http.port` | `8080` | HTTP 端口:监控页面与 `POST /telemetry`(`0` 禁用) |
| `online.timeout` | `600000` | 心跳超时毫秒数,超过即判定 BOT 离线 |
| `db.type` | `sqlite` | 崩溃日志存储:`sqlite` 或 `mysql` |
| `db.file` | `telemetry.db` | SQLite 数据库文件 |
| `db.url` | `jdbc:mysql://…` | MySQL JDBC 连接串(仅 `db.type=mysql` 时使用) |
| `db.user` / `db.password` | `root` / 空 | MySQL 账号 |

两个监听入口均绑定 `0.0.0.0`(所有网卡);端口设为 `0` 表示禁用该入口。

使用 MySQL 时建议在连接串中保留 `createDatabaseIfNotExist=true`,首次启动自动建库建表:

```yaml
db:
  type: mysql
  url: "jdbc:mysql://127.0.0.1:3306/xinbot_telemetry?createDatabaseIfNotExist=true"
  user: root
  password: ""
```

## 对接 Xinbot 客户端

在客户端 `config.conf` 中开启遥测:

```hocon
"telemetry" : {
    "enable" : true,
    "mode" : "udp",        // "udp"(默认)或 "http"
    "ip" : "<服务端IP>",    // 本遥测服务端 IP
    "port" : 9000
}
```

- `mode="udp"`:客户端把加密信封发送到 **`udp.port`**(默认 9000)。
- `mode="http"`:客户端把同样的信封 POST 到 **`http.port`** 的 `/telemetry` 路径
  (默认 `http://<服务端IP>:8080/telemetry`)。

## 网页监控与 HTTP API

浏览器打开 `http://<服务端IP>:8080/` 即可查看在线数量与最近崩溃日志。

| 路由 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 监控页面 HTML(按配置的 `lang` 渲染) |
| `/api/stats` | GET | `{online, offline, crashed, total_crashes, online_timeout_ms, now}` |
| `/api/bots` | GET | 全部已知 BOT 状态:`name`、`server`、`online`、`crashed`、`state`、`players`、`version`、`source_ip`、`uptime_ms`、`last_seen_ms` 等 |
| `/api/crashes?limit=N` | GET | 最近崩溃日志(默认 15 条,上限 200) |
| `/telemetry` | POST | 接收 HTTP 模式遥测信封(body 与 UDP 载荷相同的二进制信封) |

## 传输协议(加密信封)

与客户端 `TelemetryManager` 完全一致,每个数据包即一个信封:

| 偏移 | 字节数 | 字段 |
|---|---|---|
| `0..3` | 4 | 魔数 `XBTL` |
| `4` | 1 | 协议版本(`1`) |
| `5` | 1 | 消息类型:`1` = 心跳,`2` = 崩溃报告 |
| `6..17` | 12 | 随机 AES-GCM IV |
| `18..` | 其余 | AES-128-GCM 密文(JSON 负载 + 16 字节认证标签) |

固定 16 字节密钥为字符串 `xinbot-telemetry`(ASCII);认证失败(密钥不符或被篡改)的数据包会被
服务端拒绝。明文 JSON 负载包含 `type`、`timestamp_ms`、`version`、`bot`、`online`、`state`、
`server`、`players`、`uptime_ms`;心跳额外携带 JVM 堆与系统信息(`heap_used_bytes`、`os_name` 等),
崩溃报告额外携带 `thread_name`、`exception`、`stack_trace`。

如需自行实现接收端,可参考 `PacketDecoder`(`PacketDecoderTest` 覆盖了往返、坏魔数与篡改检测)。

## 多语言

翻译文本位于 `src/main/resources/lang/`,是与 Xinbot 核心同款的 `.lang` 文件:

```
lang/
├── en_us.lang   # 兜底基座语言(完整)
├── zh_cn.lang   # 简体中文(默认)
└── zh_tw.lang   # 繁体中文
```

加载方式与 Xinbot 核心 `LangManager` 一致:先加载 `en_us` 作为基座,再用所选语言覆盖同名 key;
缺失的 key 最终直接显示 key 本身。格式为每行 `key=value`、`#` 开头为注释、占位符用 `%s`/`%d`
(`String.format` 风格)。如需新增语言:放入一份 `<代码>.lang` 文件,并在 `I18n.supports(...)`
与 `Config` 的语言校验中注册该代码即可。

## 项目结构

```
src/main/java/top/mcocet/
├── Main.java                  # 装配启动与优雅停机
├── Config.java                # config.yml 加载与校验
├── i18n/I18n.java             # .lang 加载与查询
├── telemetry/
│   ├── PacketDecoder.java     # 信封校验 + AES-GCM 解密
│   └── TelemetryHandler.java  # 共用处理管线:心跳 -> 注册表,崩溃 -> 入库
├── core/
│   ├── BotRegistry.java       # 在线状态注册表与超时判定
│   └── BotStatus.java         # 单个 BOT 的实时状态
├── model/CrashRecord.java     # 崩溃日志模型(JSON <-> 数据库行)
├── store/                     # CrashStore:SQLite 与 MySQL 两套实现
├── net/UdpServer.java         # UDP 监听(0.0.0.0)
└── http/WebServer.java        # 监控页面、JSON API、POST /telemetry
src/main/resources/
├── config.yml                 # 内置默认配置(首次运行自动复制)
└── lang/*.lang                # 多语言文案
src/test/java/.../PacketDecoderTest.java
```

## 说明

- 本项目属于 Xinbot 生态;传输协议与 `.lang` 约定与
  [Xinbot](https://github.com/huangdihd/xinbot) 核心项目保持一致。
- 超过 7 天未上报的 BOT 记录会从内存注册表中自动清理,防止内存膨胀。
