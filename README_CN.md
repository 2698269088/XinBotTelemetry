# XinBotTelemetry

[Xinbot](https://github.com/xinbote/xinbot) Minecraft 机器人客户端的**遥测服务端**。
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
- **与客户端 `TelemetryManager` 协议完全一致**:部署特定 AES-256-GCM 密钥、头部字节作为
  GCM AAD 绑定的加密信封,可防篡改、可防重放伪造

## 环境要求

- Java 17 或更高版本

## 下载

每个 [GitHub Release](https://github.com/2698269088/XinBotTelemetry/releases) 都附带预构建 jar。
`XinBotTelemetry-1.0.1.jar` 为自包含可执行包(全部依赖已打入):放入任意目录,可在旁边放一份
`config.yml`,然后运行:

```bash
java -jar XinBotTelemetry-1.0.1.jar
java -jar XinBotTelemetry-1.0.1.jar --config=/path/to/config.yml
```

## 从源码构建与运行

```bash
mvn package                      # 产出 target/XinBotTelemetry-1.0.1.jar
java -jar target/XinBotTelemetry-1.0.1.jar
java -jar target/XinBotTelemetry-1.0.1.jar --config=/path/to/config.yml
```

首次启动时若 jar 同目录下没有 **`config.yml`**,会自动生成一份默认配置;修改后重启生效。

`mvn test` 可运行信封解码单元测试。

## 配置项(`config.yml`)

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `lang` | `zh_cn` | 界面语言:`zh_cn` / `zh_tw` / `en_us` |
| `key` | 空(自动生成) | 遥测加密密钥:Base64 编码的 32 字节(AES-256)。客户端可复制,也可启动时自动获取(见下) |
| `udp.port` | `9000` | UDP 遥测监听端口(`0` 禁用) |
| `http.port` | `8080` | HTTP 端口:监控页面、`POST /telemetry` 与 `GET /telemetry/key`(`0` 禁用) |
| `online.timeout` | `600000` | 心跳超时毫秒数,超过即判定 BOT 离线 |
| `db.type` | `sqlite` | 崩溃日志存储:`sqlite` 或 `mysql` |
| `db.file` | `telemetry.db` | SQLite 数据库文件 |
| `db.url` | `jdbc:mysql://…` | MySQL JDBC 连接串(仅 `db.type=mysql` 时使用) |
| `db.user` / `db.password` | `root` / 空 | MySQL 账号 |

两个监听入口均绑定 `0.0.0.0`(所有网卡);端口设为 `0` 表示禁用该入口。

### 加密密钥(`key`)

信封使用 **部署特定的密钥** 做 AES-256-GCM 加密,由服务端与所有客户端共享,**不存在内置
默认密钥**(公开的固定密钥既不提供机密性,也无法认证发送方)。

- 用 `openssl rand -base64 32` 生成后填入 `config.yml` 的 `key:`;或
- `key:` 留空:服务端首次启动自动生成随机密钥,保存到 jar 同目录的 `telemetry.key` 文件
  (重启复用)并把密钥值打印一次。

客户端既可以把该值复制到 `config.conf` 的 `telemetry.key`,也可以**留空**让机器人在启动时
按所选传输方式自动向服务端获取(UDP 密钥请求/应答控制报文,或 HTTP `GET /telemetry/key`)。
自动获取是**明文交换**:能监听到它的人即可获得密钥,因此仅建议在**可信网络**使用(例如
机器人与服务端位于同一局域网)。

请妥善保管密钥文件:任何持有密钥的人都能解密报告并伪造心跳/崩溃报告。轮换方式:在服务端
(`config.yml` 的 `key` 或 `telemetry.key` 文件)与所有客户端**同步**更新为新值;怀疑密钥
泄露时应立即轮换。

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
    "port" : 9000,
    "key" : "",            // 留空 = 启动时自动向服务端获取;
                            // 或粘贴服务端的 Base64-32B 密钥值
}
```

- 客户端在无法取得密钥时**静默失败关闭**(fail-closed):显式配置的 `telemetry.key` 无效,
  或自动获取失败(服务端不可达)都不会发送任何数据,更不会明文发送。
- 默认配置中遥测是**关闭**的(`enable: false`),属于 opt-in 功能。

- `mode="udp"`:客户端把加密信封发送到 **`udp.port`**(默认 9000)。
- `mode="http"`:客户端把同样的信封 POST 到 **`http.port`** 的 `/telemetry` 路径
  (默认 `http://<服务端IP>:8080/telemetry`)。

### 可选上报字段开关

每个客户端可通过 `config.conf` 的 `telemetry.send*` 开关独立控制上报内容(默认全部
`true`,即全量上报),把某项设为 `false` 就不再发送对应数据:

| 开关 | 被隐藏的字段 | 常见用途 |
|---|---|---|
| `sendBot` | `bot`(BOT 名称) | 不想暴露账号名 |
| `sendServer` | `server`(服务器地址) | 不想暴露登录的游戏服务器 |
| `sendState` | `online`、`state`(登录状态/主服阶段) | 不想暴露连接状态 |
| `sendPlayers` | `players`(玩家数量) | |
| `sendUptime` | `uptime_ms` | |
| `sendSystem` | JVM 堆 / OS / Java 版本(仅心跳) | |

协议必需字段(`type`、`timestamp_ms`、`version`)与崩溃详情(`thread_name`、`exception`、
`stack_trace`)始终上报。服务端不会因字段缺失而丢弃数据包:缺少 `bot` 名的心跳会按来源 IP
匿名登记,并以占位名 `(未知)` 展示;缺少 `bot` 名的崩溃报告也会以 `(未知)` 作为 bot 名入库。
因此这些隐私开关不会影响在线/离线判定与崩溃日志功能。

## 网页监控与 HTTP API

浏览器打开 `http://<服务端IP>:8080/` 即可查看在线数量与最近崩溃日志。

| 路由 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 监控页面 HTML(按配置的 `lang` 渲染) |
| `/api/stats` | GET | `{online, offline, crashed, total_crashes, online_timeout_ms, now}` |
| `/api/bots` | GET | 全部已知 BOT 状态:`name`、`server`、`online`、`crashed`、`state`、`players`、`version`、`source_ip`、`uptime_ms`、`last_seen_ms` 等 |
| `/api/crashes?limit=N` | GET | 最近崩溃日志(默认 15 条,上限 200) |
| `/telemetry/key` | GET | 明文下发部署密钥,供自动获取模式的客户端使用(弱化模式,仅限可信网络) |
| `/telemetry` | POST | 接收 HTTP 模式遥测信封(body 与 UDP 载荷相同的二进制信封) |

## 传输协议(加密信封)

与客户端 `TelemetryManager` 完全一致,每个数据包即一个信封:

| 偏移 | 字节数 | 字段 |
|---|---|---|
| `0..3` | 4 | 魔数 `XBTL` |
| `4` | 1 | 协议版本(`1`) |
| `5` | 1 | 消息类型:`1` = 心跳,`2` = 崩溃报告(`3`/`4` = 明文密钥交换,见下) |
| `6..17` | 12 | 随机 AES-GCM IV |
| `18..` | 其余 | AES-256-GCM 密文(JSON 负载 + 16 字节认证标签) |

前 6 个头部字节(`magic` + `version` + `type`)作为 GCM AAD 绑定到密文,单独篡改类型字节
会使认证失败;密钥为 `config.yml` / `telemetry.key` 配置的部署密钥(Base64 编码的 32 字节),
认证失败(密钥不符或被篡改)的数据包会被服务端拒绝,负载 JSON 的 `type` 字段与信封类型
不一致的数据包同样会被拒绝。明文 JSON 负载包含 `type`、`timestamp_ms`、`version`、`bot`、
`online`、`state`、`server`、`players`、`uptime_ms`;心跳额外携带 JVM 堆与系统信息
(`heap_used_bytes`、`os_name` 等),崩溃报告额外携带 `thread_name`、`exception`、`stack_trace`。

**明文密钥交换(弱化模式)** —— 客户端 `telemetry.key` 留空时,向 UDP 端口发送 6 字节数据报
`magic + version + type=3`(无 IV、无密文);服务端回送 `magic + version + type=4` + Base64
编码的部署密钥。HTTP 等价端点为 `GET /telemetry/key`。这些控制报文不会进入
`PacketDecoder.decode`:服务端直接按头部识别并应答,因此明文控制包与加密信封永远不会混淆。

如需自行实现接收端,可参考 `PacketDecoder`(`PacketDecoderTest` 覆盖了往返、类型字节篡改、
错误密钥与 JSON 类型不一致检测;`ClientInteropTest` 会解码由真实客户端实现生成的信封)。

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
src/test/java/.../PacketDecoderTest.java      # 信封解码单元测试
src/test/java/.../ClientInteropTest.java      # 消费客户端真实实现生成的信封(向量缺失时跳过)
```

## 说明

- 本项目属于 Xinbot 生态;传输协议与 `.lang` 约定与
  [Xinbot](https://github.com/xinbote/xinbot) 核心项目保持一致。
- 超过 7 天未上报的 BOT 记录会从内存注册表中自动清理,防止内存膨胀。
