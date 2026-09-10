# Trino 网关（Java）——功能实现分析与详细模块设计

> 版本：v1.0 ｜ 日期：2026-09-10 ｜ 范围：功能可行性、通信协议分析、总体架构、模块设计、实施路线

---

## 1. 项目概述

### 1.1 定位

Trino 网关是位于**客户端与 Trino 集群之间的一层治理代理**：对外表现为一个"Trino 服务器"（与 Trino 协议兼容），对内负责 SQL 安全校验、流量治理（限流/排队）、认证收敛、路由与元数据透传。

核心目标：

| 目标 | 说明 |
|---|---|
| 接入收敛 | 所有用户/BI 工具（DBeaver、Superset、JDBC/ODBC、CLI）统一连网关，不直连集群 |
| SQL 治理 | 校验规则可动态加载，如"SELECT 必须带 WHERE 或 LIMIT"、敏感表禁止访问 |
| 流量治理 | 按用户限制每分钟 SQL 数、排队数、并发数，防压垮后端 |
| 认证统一 | 网关统一认证（LDAP/OAuth2/Kerberos/mTLS），并向后端传播身份 |
| 透明兼容 | 客户端无感知：DBeaver/JDBC 通过标准 Trino REST 协议工作 |

### 1.2 关键结论（先行摘要）

1. **实现路线**：网关需要"协议感知"而非纯 HTTP 反代。推荐采用 **A2 路线——网关实现 Trino 服务端协议端点（`/v1/statement` 等），内部用 `io.trino.client`（Trino 官方 Java 客户端）转发到后端**。纯反代（A1）无法细粒度做 SQL 校验与按用户限流。
2. **SQL 校验**：直接用 Trino 官方 `trino-parser` 把 SQL 解析成 AST 再走规则引擎，比正则匹配准确得多；规则引擎支持文件热加载、数据库版本化管理、脚本（Groovy）与 SPI 插件四种动态加载方式。
3. **限流排队**：限流用"固定/滑动窗口 + 令牌桶"（单机 Caffeine，多实例 Redis Lua）；排队在 **POST 提交阶段**完成，客户端无感（异步挂起首包），超限返回协议兼容的 `429 + Retry-After`。
4. **认证**：网关侧终止认证（Basic/LDAP、OAuth2/JWT、Kerberos、mTLS、可信头），后端侧通过"服务账号 + `X-Trino-User` 身份头"或"凭据透传"两种模式传播身份。
5. **元数据通信**：`/v1/info`、`/v1/status`、`/v1/node`、`/metrics` 全部代理透传，同时用作后端健康检查与路由依据；DBeaver 的元数据浏览走的是 SQL（`SHOW CATALOGS` 等），同样经过校验层但需要放行清单。

---

## 2. Trino 客户端—服务器通信协议分析（网关实现基础）

网关要"伪装"成 Trino 服务器，必须先吃透协议。以下均以官方文档为准（Trino client REST API）。

### 2.1 协议形态

Trino 客户端协议是**基于 HTTP/HTTPS 的 REST + JSON、异步拉取（pull）模型**：

1. 客户端 `POST /v1/statement`，请求体为 SQL 文本；首包响应即 `QueryResults` JSON。
2. 若结果未取完，响应含 `nextUri`；客户端循环 `GET nextUri` 拉取后续结果，直到响应中不再出现 `nextUri`。
3. `DELETE nextUri` 取消正在运行的查询。

### 2.2 核心端点

| 方法/路径 | 作用 |
|---|---|
| `POST /v1/statement` | 提交 SQL，返回 `QueryResults`（含首包数据或 `nextUri`） |
| `GET /v1/statement/{id}/{token}`（即 nextUri） | 拉取下一批结果 |
| `DELETE /v1/statement/{id}/{token}` | 取消查询 |
| `GET /v1/info` | 集群信息（nodeVersion、environment、starting、uptime、clusterUptime） |
| `GET /v1/status` | 协调节点状态（nodeId、version、memoryInfo） |
| `GET /v1/node` | 集群节点列表 |
| `GET /metrics` | OpenMetrics 格式监控指标（RunningQueries/QueuedQueries 等） |

### 2.3 `QueryResults` 关键字段

| 字段 | 说明 |
|---|---|
| `id` | 查询 ID（协调节点分配） |
| `nextUri` | 后续取数地址；**缺失 = 查询结束（成功或失败）** |
| `columns` | 列名与类型 |
| `data` | 行数据（每行按 columns 顺序） |
| `updateType` | 操作类型提示（CREATE TABLE / SET SESSION 等） |
| `error` / `queryError` / `failureInfo` | 查询失败时携带 `QueryError`（message、errorCode 等） |
| `warnings`、`statementStats` | 告警与执行统计（含 rootStage 各阶段统计） |
| `infoUri` | Web UI 查询详情地址 |

### 2.4 客户端请求头（网关必须理解并转发）

| 头 | 说明 |
|---|---|
| `X-Trino-User` / `X-Trino-Original-User` | 会话用户 / 原始用户 |
| `X-Trino-Catalog` / `X-Trino-Schema` | 目录与模式上下文 |
| `X-Trino-Source` | 提交方标识（DBeaver、Superset 等） |
| `X-Trino-Time-Zone` / `X-Trino-Language` | 时区 / 语言 |
| `X-Trino-Session` | 会话属性（`name=value` 逗号列表，客户端会累积） |
| `X-Trino-Prepared-Statement` | 预编译语句句柄列表 |
| `X-Trino-Transaction-Id` | 事务 ID（多语句事务用） |
| `X-Trino-Client-Tags` / `X-Trino-Client-Info` | 资源组标记 / 客户端信息 |
| `X-Trino-Client-Capabilities` | 客户端能力声明（PATH、PARAMETRIC_DATETIME、NUMBER、VARIANT、SESSION_AUTHORIZATION 等） |
| `X-Trino-Resource-Estimate` | 资源预估（EXECUTION_TIME、PEAK_MEMORY 等） |
| `X-Trino-Extra-Credential` | 传给 connector 的额外凭据 |

**协议细节（对网关设计至关重要）**：
- 这些头**只在首次 `POST /v1/statement` 时发送**，轮询 `nextUri` 时不再携带；网关必须记住首请求的上下文（用户、会话、catalog/schema）。
- 响应头承担"会话状态回写"职责：`X-Trino-Set-Session`、`X-Trino-Clear-Session`、`X-Trino-Set-Catalog`、`X-Trino-Set-Schema`、`X-Trino-Started-Transaction-Id`、`X-Trino-Clear-Transaction-Id`、`X-Trino-Added-Prepare`、`X-Trino-Deallocated-Prepare` 等，客户端会像浏览器 cookie 一样记下并在后续请求中带回。**网关必须原样透传这些响应头，否则会话/事务/预编译全部失效。**

### 2.5 错误与重试语义（网关生成错误时必须遵守）

| 场景 | 协议语义 |
|---|---|
| 200 + `QueryResults.error` | 查询逻辑失败（SQL 错误），客户端抛 SQL 异常并展示 message —— **网关做校验拒绝时应采用此形态，DBeaver 能显示清晰报错** |
| 502 / 503 / 504 | 瞬时问题，客户端 50–100ms 后重试 |
| 429 | 限流，客户端按 `Retry-After` 头重试 —— **网关限流/排队应返回此形态** |
| 其他非 200 | 查询处理失败，客户端直接报错 |

### 2.6 DBeaver 等 JDBC 客户端的典型行为

DBeaver 通过 Trino JDBC 驱动（`jdbc:trino://host:port/catalog/schema`）接入，关键行为：

1. **连接/版本探测**：访问 `/v1/info` 获取版本；网关需返回后端真实版本（或合成）。
2. **元数据浏览**：发出 `SHOW CATALOGS`、`SHOW SCHEMAS FROM x`、`SHOW TABLES FROM x`、`DESCRIBE x`、查询 `information_schema.*` 等 SQL——这些都走 `POST /v1/statement`，**网关校验规则必须放行元数据类语句（或按 allowlist 处理），否则 DBeaver 树形目录打不开**。
3. **会话管理**：`USE catalog.schema`、`SET SESSION` 通过 SQL + 响应头回写完成，网关要透传。
4. **失败呈现**：依赖 `QueryResults.error.message` 展示给用户，所以网关的校验拒绝信息要写进 error 字段。

### 2.7 对网关架构的三个决定性推论

1. **不能只做 HTTP 透传**：要校验 SQL 必须解析 `POST /v1/statement` 请求体；要按用户限流必须从认证上下文或 `X-Trino-User` 提取身份；要排队必须在提交阶段拦截。这些都需要应用层逻辑。
2. **`nextUri` 必须指向网关**：后端返回的 `nextUri` 指向后端地址，网关必须重写为自身地址（推荐显式重写），或依赖后端开启 `http-server.process-forwarded=true` + 网关透传 `X-Forwarded-*`（官方 trino-gateway 的做法）。显式重写更可控、不依赖后端配置。
3. **首请求与轮询请求要能关联**：网关需要维护 `网关查询ID → 后端查询ID + 后端集群 + 首请求上下文` 的映射，直到 `nextUri` 消失。

---

## 3. 总体架构设计

### 3.1 两条实现路线对比

| 维度 | A1：纯 HTTP 反向代理（官方 trino-gateway 思路） | A2：协议端点 + 内部 TrinoClient（**推荐**） |
|---|---|---|
| 实现 | 请求转发 + URL 重写 | 自己实现 `/v1/statement` 等端点，内部用 `io.trino.client.StatementClient` 连后端 |
| SQL 校验 | 可拦截 POST body 做，但排队/限流粒度粗 | 天然在应用层完成，控制粒度细 |
| 按用户限流 | 依赖身份提取，耦合重 | 认证上下文直接可用 |
| 首包延迟控制 | 难 | 可做（异步挂起 + 排队） |
| 复杂度 | 低 | 中高 |
| 版本兼容 | 好（透传） | 需跟进协议（协议本身稳定） |

**结论**：采用 A2。网关用 Jetty/AirLift 提供 HTTP 服务并实现协议端点，内部统一通过 `io.trino.client`（或 JDBC）转发到后端；`nextUri` 显式重写。

### 3.2 组件拓扑（文字版）

```
DBeaver / JDBC / ODBC / CLI / Superset / 自研应用
                    │  Trino REST 协议 (HTTPS)
                    ▼
┌─────────────────────────────────────────────────────────┐
│                    Trino 网关（Java）                      │
│  ┌───────────┐ ┌─────────┐ ┌──────────┐ ┌────────────┐  │
│  │ 协议端点层 │→│ 认证层   │→│ SQL校验层  │→│ 流量治理层   │  │
│  │ Statement │ │ (LDAP/  │ │ (规则引擎  │ │ (限流/排队/  │  │
│  │ Info/Proxy│ │ OAuth2/ │ │  动态加载) │ │  并发控制)   │  │
│  └───────────┘ └─────────┘ └──────────┘ └────────────┘  │
│         │               路由层（集群注册/健康检查/策略）      │
│         ▼                                                │
│  ┌────────────────────┐   ┌───────────────────────────┐  │
│  │ 转发层 (TrinoClient) │   │ 管理/观测 (Admin API/指标/审计)│  │
│  └────────────────────┘   └───────────────────────────┘  │
└──────────────┬──────────────────────────────┬────────────┘
               │  Trino REST 协议              │  管理面
               ▼                               ▼
     Trino Coordinator ──→ Workers        MySQL/PostgreSQL (规则/查询历史/HA状态)
                                            Redis (分布式限流/排队计数)
                                            Prometheus + Grafana
```

### 3.3 关键技术选型（建议）

| 领域 | 选型 | 理由 |
|---|---|---|
| HTTP 服务 | Jetty（或 AirLift，Trino 同款） | 异步 Servlet 支持长挂起排队；吞吐高 |
| SQL 解析 | `io.trino:trino-parser`（版本对齐后端） | 与 Trino 语法 100% 一致，返回可遍历 AST |
| 后端客户端 | `io.trino:trino-client`（`io.trino.client.StatementClient`） | 官方实现协议、类型、错误处理 |
| 限流 | Caffeine（单机）＋ Redis Lua（分布式） | 高性能窗口计数 |
| 持久化 | MySQL/PostgreSQL + Flyway + JDBI/MyBatis | 规则、查询历史、网关 HA 状态 |
| 脚本规则 | Groovy（沙箱）或 GraalJS | 动态规则无需重启 |
| 指标 | Micrometer + Prometheus | Running/Queued/Rejected/校验命中率等 |
| 配置 | YAML + 环境变量注入 | 与 trino-gateway 习惯一致 |

### 3.4 核心设计决策

| 决策 | 方案 |
|---|---|
| 身份传播 | 网关认证后：① 透传模式（后端同认证源，原样转发 Authorization/令牌）；② 伪装模式（网关用服务账号连后端 + `X-Trino-User=终端用户` + `X-Trino-Original-User`） |
| nextUri 重写 | 网关生成 `gatewayQueryId`，映射 `{gatewayId → backendId, cluster, 首包上下文}`，重写所有 nextUri 为网关地址；映射 TTL + 客户端停止轮询时自动取消后端查询 |
| 排队位置 | POST 阶段：查询入队，异步 Servlet 挂起等首包；超时/满 → `429/503 + Retry-After` |
| 校验错误形态 | `HTTP 200 + QueryResults{error:{message}}`，DBeaver 显示清晰 SQL 错误 |
| 事务/会话 | 透传 `X-Trino-Transaction-Id` 等响应头；事务起始后记录 `事务→集群` 粘性映射，保证同一事务后续语句去同一集群 |
| 大结果集 | 拉模式天然逐页：网关每收到一次客户端 GET 才向后端拉一页，内存有界；后端响应体上限可配（默认 32MB，参考官方网关） |

---

## 4. 功能模块详细设计

### M1 协议端点层（gateway-protocol）

**职责**：实现 Trino 服务端协议，作为所有客户端请求的唯一入口。

| 组件 | 说明 |
|---|---|
| `StatementResource` | 处理 `POST /v1/statement`、`GET/DELETE /v1/statement/{id}/{token}`；从请求体提取 SQL 与 `X-Trino-*` 头；组装 `QueryResults` JSON（用自建 POJO 或 `io.trino.client.QueryResults`）；透传响应头 |
| `InfoResource` | `/v1/info`、`/v1/status`、`/v1/node`、`/metrics`：转发到健康后端或合成网关视图 |
| `ProxyResource` | 白名单路径透传（`/ui`、`/oauth`、`/v1/query` 及 `extraWhitelistPaths`），供 Web UI/工具使用 |
| `HeaderCodec` | 解析/序列化 `X-Trino-*` 请求头与 `Set-*` 响应头（对照 `io.trino.client.ProtocolHeaders`） |
| `QueryRegistry` | 网关查询 ID 与后端查询映射表（Caffeine，带 TTL）：`{gatewayId → (backendId, cluster, user, context)}` |

**关键流程**：见第 5 节时序。

### M2 认证与身份层（gateway-auth）

**职责**：客户端认证、身份上下文构建、向后端传播身份。

支持的认证方式（网关侧）：

| 方式 | 实现 | 后端传播 |
|---|---|---|
| Basic（LDAP / 密码文件） | 拦截 `Authorization: Basic`，调 LDAP 校验 | ①后端同源：透传 Basic；②否则：服务账号 + 身份头 |
| OAuth2 / OIDC（JWT） | 校验 Bearer Token（JWKS 拉公钥、验 audience/iss/exp） | ①同信任域透传原 token；②换取短期 token（token exchange） |
| Kerberos（SPNEGO） | 网关 keytab 建立服务身份，SPNEGO 握手 | 透传协商头（后端同 KDC），或网关代表用户转发 |
| 客户端证书 mTLS | 校验客户端证书链 | 透传或替换为网关证书 |
| 可信头 | 仅允许来自可信代理 CIDR，信任 `X-Auth-User` 等头 | 原样 |

**身份模型**：

```java
record GatewayIdentity(
    String user,            // 认证后最终用户名（用于限流、审计、规则）
    String originalUser,    // 若有
    AuthType authType,
    Map<String,String> attributes,  // groups、source、clientTags 等
    PropagationMode mode    // PASSTHROUGH | IMPERSONATE
) {}
```

**要点**：
- 网关启用 TLS 终止（HTTPS 对外），后端通信可复用网关侧 TLS 或独立配置。
- 限流与规则引擎只认 `GatewayIdentity.user`，与后端认证解耦。
- 支持 `X-Trino-User` 覆盖（若允许），需校验与认证身份一致性，防越权。

### M3 SQL 校验与规则引擎（gateway-sql + gateway-rules）

**核心思路**：用 `trino-parser` 将 SQL 解析为 AST，规则引擎基于 AST 特征判定；拒绝时返回 `QueryResults.error`。

#### M3.1 SQL 提取与解析

- 从 `POST /v1/statement` 请求体取 SQL 文本。
- 处理 `PREPARE name AS <sql>`：校验内层 SQL。
- 处理多语句？Trino 协议单请求单语句；`;` 结尾做裁剪。
- 解析失败（语法错误）→ 直接放行交给后端报错，或返回解析错误（可配）。

#### M3.2 校验点（AST 特征提取器）

| 校验特征 | 实现方式（AST Visitor） |
|---|---|
| 语句类型分类 | 顶层 `Statement` 类型：SELECT/INSERT/UPDATE/DELETE/CREATE/DROP/DDL/SHOW/SET/DESCRIBE 等 |
| 是否带 WHERE | `QuerySpecification.getWhere()`；需注意：`SELECT 1`（无 FROM）应豁免 |
| 是否带 LIMIT | `Query.getLimit()` / `QuerySpecification.getLimit()`（字段位置随 trino-parser 版本微调，以依赖版本为准） |
| 查询涉及表 | 遍历 `QueryBody`/`Relation` 中的 `Table` 节点提取 qualifiedName（含 CTE、子查询、JOIN） |
| `SELECT *` | `SelectItem` 中 `AllColumns` 节点 |
| 嵌套子查询 | 递归遍历 `TableSubquery` |
| 正则兜底 | 对解析器无法覆盖的场景（如注释混淆），提供可选的 `pattern` 规则 |

#### M3.3 规则模型

```java
interface SqlValidationRule {
    RuleId id();
    RuleAction evaluate(SqlContext ctx);  // SqlContext: 语句类型 + AST + 用户 + source + catalog/schema
}
enum RuleAction { ALLOW, REJECT, WARN, MASK }
record RuleResult(RuleId rule, RuleAction action, String message) {}
```

**规则编排**：静态硬规则（内置，不可删）→ 动态规则（文件/DB/脚本/插件）→ 汇总；任一 REJECT 即拒绝（可配为 ALL 或 ANY 语义）。

#### M3.4 动态加载的四种通道（核心需求）

| 通道 | 机制 | 更新方式 | 适用 |
|---|---|---|---|
| ① 文件热加载 | 规则 YAML/JSON 放规则目录，WatchService 或 md5 轮询（间隔可配），原子替换规则集 | 运维改文件即生效 | 简单、可审计（git） |
| ② 数据库版本化 | 规则存 MySQL/PostgreSQL，带 `version`、`enabled`、`effectiveTime`；Admin API 读写；网关轮询/订阅变更 | Admin API 或直改库 | 多实例统一、带灰度 |
| ③ 脚本规则 | Groovy 脚本实现 `boolean evaluate(SqlContext)`，脚本沙箱执行（限制类/CPU/内存），文件或 DB 存脚本 | 上传/更新脚本即生效 | 复杂逻辑不重启 |
| ④ SPI 插件 | 规则 JAR 放入插件目录，`ServiceLoader` 加载实现 `SqlValidationRule` 的类 | 替换 JAR + 热卸载（自定义类加载器） | 定制团队开发 |

**规则管理面**：
- Admin API：`GET/POST/PUT/DELETE /admin/rules`，支持 dry-run 试跑、版本回滚、规则命中统计。
- 每条规则带 `id/name/version/enabled/priority/action/message/appliesTo/exceptUsers/conditions`。
- 灰度：按用户组/source 分桶启用（如先对非核心组生效）。

#### M3.5 内置规则示例（YAML DSL）

```yaml
rules:
  - id: select-require-where-or-limit
    name: "SELECT 必须包含 WHERE 或 LIMIT"
    version: 3
    enabled: true
    action: REJECT
    appliesTo: [SELECT]                 # 仅 SELECT；SHOW/DESCRIBE/SET 等元数据语句默认放行
    exceptUsers: ["etl_sa", "admin"]
    condition:
      type: ast
      requireWhereOrLimit:
        exemptNoFrom: true              # SELECT 1 无表扫描豁免
        includeSubqueries: false        # 是否递归校验子查询
    message: "查询必须包含 WHERE 或 LIMIT 条件，防止全表扫描"
  - id: forbid-sensitive-tables
    name: "禁止访问敏感表"
    version: 1
    enabled: true
    action: REJECT
    condition:
      type: ast
      forbiddenTables: ["ods.pii.user_profile", "ods.pii.phone"]
    message: "表 %s 受保护，禁止访问"
  - id: forbid-star-no-limit
    name: "禁止 SELECT * 且无 LIMIT"
    condition: { type: ast, forbidSelectStarWithoutLimit: true }
    action: WARN        # 先 WARN 灰度，确认后再改 REJECT
    message: "建议避免无 LIMIT 的 SELECT *"
```

### M4 流量治理层（gateway-flow）

**职责**：限流（每分钟 SQL 数）、排队（排队数上限）、并发控制。

#### M4.1 限流（Rate Limiter）

| 维度 | 设计 |
|---|---|
| 粒度 | 默认按 `user`；可扩展 `user+source`、`user+clientTags`、IP |
| 指标 | 每分钟 SQL 数（核心需求）：固定窗口（简单）/ 滑动窗口（平滑）；另支持令牌桶做突发控制 |
| 单机实现 | Caffeine 缓存 `key → AtomicLong+窗口起点`，窗口过期自动清 |
| 分布式实现 | Redis Lua：`INCR + EXPIRE`（固定窗口，双窗口平滑），保证原子性；避免 ZSET 滑动窗口的高内存 |
| 超限响应 | `HTTP 429 + Retry-After: <剩余秒数>`（协议兼容，客户端自动重试） |
| 动态配置 | 每个用户/用户组限额可在 Admin API 调整，热生效（如 analyst=120/min，etl_sa=600/min） |

```yaml
flow:
  rateLimit:
    mode: fixedWindow            # fixedWindow | slidingWindow | tokenBucket
    defaultPerMinute: 60
    perUser: { "analyst": 120, "etl_sa": 600, "admin": 0 }  # 0=不限
    distributed:
      enabled: true
      redis: "redis://rate-cache:6379"
```

#### M4.2 排队（Query Queue）

| 项 | 设计 |
|---|---|
| 队列模型 | 全局队列 + 每用户队列（容量均可配：`maxGlobalPending`、`maxPerUserPending`） |
| 队列满 | 立即拒绝：`429/503 + Retry-After` |
| 排队超时 | 队列 TTL（默认 30s），超时返回 `429`（客户端可重试） |
| 优先级 | `PriorityBlockingQueue`，优先级来源：source、clientTags、用户组；同优先级 FIFO |
| 并发上限 | `maxRunningPerUser`、`maxRunningGlobal`：运行时计数，超限排队 |
| 实现方式 | POST 请求进入异步 Servlet 挂起 → 调度线程池出队 → 转发后端 → 拿到首包即返回响应；挂起需有超时与监控 |
| 观测 | 队列深度、等待时长 P50/P99、拒绝数、排队超时数 → Prometheus |

> 说明：网关排队与后端 Trino 资源组（resource groups）互补——网关队列管"进门"（容量与用户隔离），资源组管"运行"（CPU/内存配额），可同时启用。

#### M4.3 保护性限制

- 单条 SQL 长度上限（如 1MB）。
- 后端响应单页体积上限（默认 32MB，可配，参考官方网关 `proxyResponseConfiguration.responseSize`）。
- 客户端轮询空闲超时（默认 60s 无 `GET nextUri` 即自动 `DELETE` 后端查询并清理映射），防查询悬挂。

### M5 路由与后端管理（gateway-routing）

| 组件 | 设计 |
|---|---|
| 集群注册 | 配置或 DB 中登记多个后端（`http://coordinator-a:8080` 等），支持启停 |
| 健康检查 | `INFO_API`（`/v1/info` 返回 200 且 `starting=false`）与 `METRICS`（`/metrics` 拉 Running/Queued 指标、活跃节点数），间隔/超时/重试可配；异常标记 UNHEALTHY 并停止路由 |
| 路由策略 | 轮询（默认）、按集群查询数（QueryCountBased，需 METRICS）、规则路由（按用户/source/header 决策，可复用 trino-gateway 的 routing rules 思路） |
| 查询亲和 | 同一 `gatewayQueryId` 的轮询请求必须去同一集群；同一事务后续语句去同一集群（事务→集群粘性） |
| 容错 | 提交失败（后端不可达）→ 换集群重试（幂等性受限，仅首包前可安全重试） |

### M6 元数据与协议代理（M1 中 Info/Proxy 的深化）

| 端点 | 网关行为 |
|---|---|
| `/v1/info` | 转发到任一健康后端（或按路由选择）；客户端据此探测版本 —— **必须透传真实后端版本**，避免 JDBC 版本兼容误判 |
| `/v1/status`、`/v1/node` | 透传健康后端响应，或聚合成多集群视图 |
| `/metrics` | 网关自身指标（对外）+ 可选透传后端指标 |
| 其他白名单路径 | `/ui`、`/oauth`、`/v1/query` 等透传（`extraWhitelistPaths` 可扩展） |

**DBeaver 元数据场景专项**：`SHOW CATALOGS/SCHEMAS/TABLES`、`DESCRIBE`、`information_schema` 查询均走 `POST /v1/statement`。校验规则默认放行 `SHOW/DESCRIBE/SET/USE/EXPLAIN` 类语句；若需拦截，按表名 allowlist 精确控制（如只允许 `information_schema`）。

### M7 存储与配置（gateway-store）

| 数据 | 表（示例） | 说明 |
|---|---|---|
| 规则 | `rule`（id, name, version, payload, enabled, effective_time） | 版本化、可回滚 |
| 限流配置 | `rate_limit_policy`（user, per_minute, mode） | Admin API 热更新 |
| 查询历史 | `query_history`（query_id, user, source, sql_masked, cluster, status, rows, duration_ms, rejected_by） | 审计与排障；保留时长可配；SQL 需脱敏 |
| 网关状态 | `gateway_instance`（id, heartbeat, load） | HA 多实例可见性 |
| 后端状态 | `backend_cluster`（url, active, status） | 运维启停 |

- 迁移用 Flyway（与官方 trino-gateway 一致）；`queryHistoryEnabled` 可关以降低 DB 压力。

### M8 管理 API 与观测（gateway-admin + gateway-observability）

- **Admin API**：规则 CRUD / dry-run / 回滚；限流与队列参数热更新；集群与队列状态查询；查询列表与 kill（`DELETE` 后端查询）。
- **指标（Prometheus）**：`gateway_queries_total{user,source,status}`、`gateway_queries_rejected_total{reason}`、`gateway_queue_depth`、`gateway_queue_wait_seconds`、`gateway_rate_limited_total`、`gateway_backend_health`、`gateway_rule_hits{rule_id}`。
- **日志/审计**：结构化日志带 `traceToken`（透传 `X-Trino-Trace-Token` 或自生成）；审计记录认证、规则命中、限流、取消。
- **查询历史查询**：Admin API 按 user/source/时间检索。

### M9 高可用与部署（HA）

| 项 | 方案 |
|---|---|
| 多实例 | N 个网关实例置于 LB 后（轮询或按来源粘性）；管理面状态存 DB |
| 分布式限流 | Redis Lua 计数，跨实例一致 |
| 排队 | 默认每实例本地队列（容量按实例均摊）；如需全局队列需引入 Redis Stream/消息队列（复杂，建议二期） |
| nextUri 亲和 | LB 会话粘性（按 gatewayQueryId）或 nextUri 中编码实例路由；或映射表存 Redis 供任意实例服务轮询 |
| 部署 | 可执行 JAR（参考官方 `java -jar gateway-ha.jar config.yaml`）、Docker、Helm |

---

## 5. 核心流程时序

### 5.1 查询提交（含校验/限流/排队）

```
DBeaver                    Trino 网关                          Trino
  │  POST /v1/statement      │                                  │
  │  (SQL + X-Trino-* 头)     │                                  │
  ├─────────────────────────►│ 1. TLS 终止 + 认证 → 身份上下文      │
  │                          │ 2. 路由决策 → 选定后端集群           │
  │                          │ 3. 解析 SQL → 规则引擎              │
  │                          │    ├─ 拒绝 → 200+QueryResults{error}│
  │                          │    └─ 通过 ↓                      │
  │                          │ 4. 限流检查（user/分钟）            │
  │                          │    └─ 超限 → 429+Retry-After       │
  │                          │ 5. 排队（容量/超时）                │
  │                          │    └─ 满/超时 → 429/503            │
  │                          │ 6. 并发检查（running 计数）          │
  │                          │ 7. 用 TrinoClient POST 提交         │
  │                          ├──────────────────────────────────►│
  │                          │ 8. QueryResults(id,nextUri)        │
  │                          │ 9. 生成 gatewayId，建立映射          │
  │                          │    重写 nextUri → 网关地址           │
  │  ◄──────── 200 QueryResults(重写后 nextUri) + Set-* 响应头     │
```

### 5.2 结果轮询与结束

```
DBeaver                        Trino 网关                       Trino
  │ GET /v1/statement/{gwId}/..  │                                │
  ├────────────────────────────►│ 查映射 → 后端 GET nextUri       │
  │                             ├───────────────────────────────►│
  │                             │ ◄── 下一批 QueryResults        │
  │ ◄── QueryResults(重写 nextUri)│                                │
  │        (循环直到 nextUri 消失) │ 映射清理 + 查询历史落库         │
```

### 5.3 取消 / 异常

```
DBeaver                        Trino 网关                       Trino
  │ DELETE /v1/statement/{gwId} │                                │
  ├────────────────────────────►│ 映射 → 后端 DELETE             │
  │                             ├───────────────────────────────►│
  │                             │ 清理映射/计数，记录审计          │
  │ ◄── 200                    │                                │
```

### 5.4 动态规则热更新（DB 通道）

```
运维/Admin API                       网关实例                     MySQL
  │ POST /admin/rules (v4)          │                            │
  ├────────────────────────────────►│ 写入 rule 表 (version=4)    │
  │                                 ├───────────────────────────►│
  │                                 │ ◄── commit                 │
  │                                 │ 轮询/订阅变更 → 原子替换规则集 │
  │                                 │ 新查询立即用 v4 规则         │
```

---

## 6. 模块清单与包结构（Maven 多模块）

| 模块 | 职责 | 关键类 |
|---|---|---|
| `gateway-config` | 配置加载/校验/热更新 | `GatewayConfig`, `FlowConfig`, `ValidationConfig` |
| `gateway-server` | HTTP 服务、TLS、过滤器链 | `GatewayServer`, `AuthFilter`, `TraceFilter` |
| `gateway-protocol` | 协议端点、QueryResults 编解码、URL 重写 | `StatementResource`, `InfoResource`, `ProxyResource`, `QueryRegistry`, `HeaderCodec` |
| `gateway-auth` | 认证与身份传播 | `Authenticator`（LDAP/JWT/Kerberos/mTLS/Header 实现）, `IdentityContext` |
| `gateway-sql` | SQL 提取、解析、AST 特征提取 | `SqlParserService`, `StatementClassifier`, `AstFeaturesExtractor` |
| `gateway-rules` | 规则模型、引擎、动态加载、Admin | `SqlValidationRule`, `RuleEngine`, `FileRuleLoader`, `DbRuleLoader`, `ScriptRuleLoader`, `SpiRuleLoader` |
| `gateway-flow` | 限流、排队、并发控制 | `RateLimiter`, `QueryQueue`, `ConcurrencyController`, `SubmissionService` |
| `gateway-routing` | 集群注册、健康检查、路由 | `ClusterRegistry`, `HealthMonitor`, `Router`, `QueryAffinity` |
| `gateway-proxy` | 后端 TrinoClient 转发、重写、取消 | `BackendClient`, `NextUriRewriter`, `QueryCancelHandler` |
| `gateway-store` | DB 访问、Flyway、查询历史 | `RuleDao`, `QueryHistoryDao`, `ClusterDao` |
| `gateway-admin` | 管理 REST API | `AdminResource`, `RuleAdminResource`, `FlowAdminResource` |
| `gateway-observability` | 指标、审计、trace | `MetricsRegistry`, `AuditLogger` |
| `gateway-app` | 启动装配 | `GatewayMain` |

---

## 7. 与官方 trino-gateway 的对比与取舍

| 能力 | 官方 trino-gateway | 本设计（增强网关） |
|---|---|---|
| 多集群路由/健康检查 | ✅ 成熟（INFO_API/METRICS、轮询/规则路由） | 直接借鉴 |
| SQL 校验规则 | ❌ 无 | ✅ trino-parser AST + 动态规则引擎（本设计核心增量） |
| 按用户限流（每分钟 SQL 数） | ❌ 无 | ✅ 本设计核心增量 |
| 网关级排队 | ⚠️ 仅 issue #801 提案，未落地；依赖后端资源组 | ✅ 网关层排队 + 限额，可与资源组互补 |
| 认证 | ✅ OAuth/OIDC、表单 | ✅ 更全（LDAP/JWT/Kerberos/mTLS/可信头）+ 身份传播 |
| 查询历史 | ✅ 有 | ✅ 有（增强：规则命中/拒绝原因落库） |
| 部署 | JAR/Docker/Helm、Java 25、MySQL/Postgres/Oracle + Flyway | 对齐 |

**建议**：若工期紧，可在官方 trino-gateway 之上扩展（它已有协议代理、路由、健康检查、存储、UI）；但其架构以"透传路由"为主，做 SQL 校验与限流需要新增拦截与状态管理，改动不小。**本设计按自研 A2 路线给出**，模块化后也可逐步替换官方网关的对应能力。

---

## 8. 风险与注意事项

| 风险 | 应对 |
|---|---|
| `trino-parser` 版本与后端版本差异导致解析失败 | 固定支持后端版本清单；解析失败默认放行交后端报错（可配为拒绝）；CI 用多版本 parser 回归 |
| 大结果集撑爆内存 | 拉模式逐页转发 + 单页体积上限 + 网关响应缓冲上限 |
| 客户端停止轮询导致查询悬挂 | 轮询空闲超时自动取消后端查询（60s） |
| 多语句事务跨集群 | 事务→集群粘性映射；无法支持时在文档/界面明示"跨集群事务不支持" |
| 预编译语句（PREPARE/EXECUTE） | 校验 PREPARE 内层 SQL；透传 `X-Trino-Added-Prepare` 响应头 |
| 会话属性/时区/语言漂移 | 首包上下文快照，`Set-*` 响应头全透传；轮询请求按映射恢复上下文 |
| 规则误伤元数据查询 | 默认放行 SHOW/DESCRIBE/SET/USE/EXPLAIN；敏感场景用表 allowlist |
| 429 被客户端误判 | 严格按协议：429 必带 `Retry-After`；校验拒绝用 200+error 而非 4xx |
| 认证凭据泄露 | 网关 TLS 终止、凭据只存于密钥系统（环境变量/Secret）、审计认证事件 |
| 多实例限流不一致 | 用 Redis Lua 固定窗口；单机部署可只用 Caffeine |

---

## 9. 实施路线（建议 3~4 人）

| 阶段 | 内容 | 工期 |
|---|---|---|
| M0 协议打底 | Jetty 服务 + `/v1/statement` 转发 + nextUri 重写 + DBeaver 连通 | 2 周 |
| M1 SQL 校验 | trino-parser 接入 + 内置规则（WHERE/LIMIT/敏感表）+ 文件热加载 | 2 周 |
| M2 流量治理 | 限流（单机→Redis）+ 排队 + 并发控制 + 429 语义 | 1.5 周 |
| M3 认证与身份 | LDAP/JWT 认证 + 身份传播（透传/伪装） | 2 周 |
| M4 管理面 | Admin API + 规则 DB 版本化 + 指标/审计/查询历史 | 2 周 |
| M5 加固与 HA | 健康检查/路由完善、多实例、压测、文档 | 2 周 |

合计约 **11~12 周**；每个阶段均以"DBeaver 端到端可用"为验收标准。

---

## 10. 参考资料

- Trino Client REST API（协议端点、头、错误语义）：https://trino.io/docs/current/develop/client-protocol.html
- Trino 客户端协议概览：https://trino.io/docs/current/client/client-protocol.html
- 官方 trino-gateway 安装/配置（代理路径、健康检查、存储）：https://github.com/trinodb/trino-gateway/blob/main/docs/installation.md
- trino-gateway 路由规则：https://github.com/trinodb/trino-gateway/blob/main/docs/routing-rules.md
- trino-gateway 网关级排队提案（issue #801）：https://github.com/trinodb/trino-gateway/issues/801
- Trino 认证类型：https://trino.io/docs/current/security/authentication-types.html
- Trino 资源组（排队策略）：https://trino.io/docs/current/admin/resource-groups.html
