# trino-gateway

Trino 网关（Java 21 / Maven 多模块）。用户接入网关执行 SQL，网关在转发到 Trino
之前完成 **SQL 动态校验、按用户限流/排队、并发控制、认证、协议转发与元数据代理**，
并对 Trino 客户端的 `nextUri` 轮询协议做显式重写，使网关成为客户端唯一入口。

> 依赖锁定 `io.trino:trino-parser:446` / `io.trino:trino-client:446` —— **446 是
> 最后一个兼容 Java 21 的 Trino 版本**（447 起要求 Java 22，最新 Trino 要求 Java 25）。

## 功能总览

| 能力 | 说明 |
| --- | --- |
| SQL 校验（核心） | 内置规则：SELECT 必须包含 WHERE 或 LIMIT（可按 `policy: REJECT/WARN` 切换）；`SHOW/DESCRIBE/USE` 等元数据语句默认放行 |
| 动态规则 | 规则文件（`rules/*.yaml`）轮询热加载 + 管理 API 实时增删，规则集原子替换；条件支持 `forbiddenTables` / `statementKinds` / `sqlContains` |
| 用户限流 | 固定窗口限流（每分钟 SQL 数，支持按用户覆盖），或 Redis 分布式限流（Lua INCR+EXPIRE） |
| SQL 排队 | 全局/每用户排队上限 + 排队等待超时，超限返回 429 + `Retry-After` |
| 并发控制 | 全局/每用户信号量，排队等待并发槽位 |
| 认证 | `header`（如 `X-Auth-User`）/ `basic`（文件用户库）/ `jwt`（HS256，issuer/audience 校验）；Kerberos/mTLS 留扩展点 |
| 协议转发 | 完整兼容 Trino Statement 协议：`POST /v1/statement`、`GET nextUri` 轮询、`DELETE` 取消、`X-Trino-Set-*` 会话变更回传、身份伪装传播（`ClientSession.user()`） |
| nextUri 重写 | 后端 `nextUri` 重写为 `{gatewayBaseUri}/v1/statement/{gatewayId}/{token}`，客户端所有轮询都回到网关 |
| 空闲回收 | 客户端轮询空闲超时（默认 60s）自动取消后端查询并清理映射 |
| 路由与健康检查 | 多后端集群轮询路由；`GET /v1/info` 周期健康探活，故障集群自动摘除 |
| 元数据代理 | `/v1/info`、`/v1/status`、`/v1/node` 转发健康后端，无可用后端时返回合成视图 |
| 可观测 | Prometheus 指标（`/metrics`）；管理 API（规则/流量/集群/活跃查询） |
| 安全 | 校验拒绝 → HTTP 200 + `QueryResults.error`（客户端按 SQL 错误处理）；限流 → 429；越权头 `X-Trino-User` 被认证身份覆盖 |

## 模块结构

```
trinogate-parent             父 POM（依赖版本管理，Java 21 release）
├── trinogate-config         YAML 配置加载（${ENV} 插值，三层路径优先级）
├── trinogate-protocol       协议 POJO（QueryResults/QueryError/Column）、nextUri 重写、
│                            网关内查询注册表（空闲扫描/到期回调）
├── trinogate-auth           认证：header / basic / jwt + 用户文件
├── trinogate-validation     解析(SqlParser 446)→分类→AST 特征→规则引擎(内置+文件热加载+admin)
├── trinogate-flow           限流(fixedWindow/redis)、排队、并发信号量 → Admission
├── trinogate-proxy          后端集群注册/轮询/健康检查、StatementClient 封装（拉取协议映射）
├── trinogate-server         Jetty 11 HTTP 层：StatementServlet/InfoServlet/AdminServlet、
│                            AuthFilter、QueryPipeline 编排、Micrometer 指标
└── trinogate-app            启动入口 GatewayMain + 可执行 fat jar + 端到端集成测试
```

## 快速开始

要求：JDK 21+、Maven 3.9+。

```bash
# 1. 构建（编译 + 集成测试）
mvn clean install

# 2. 运行（默认读 ./config.yaml，也可 java -jar ... config.yaml 指定）
java -jar trinogate-app/target/trinogate.jar config.yaml
```

默认配置示例：`config.yaml`（后端集群、限流等）、`rules/example-rules.yaml`（动态规则）、
`users.yaml`（basic 认证用户，示例密码 `trino123`）。

客户端接入（Trino CLI / JDBC / DBeaver）把地址指向网关即可，例如：

```bash
trino --server http://127.0.0.1:8080 --user alice --catalog tpch --schema sf1
```

## 校验行为

- `SELECT * FROM t` → 拒绝（HTTP 200 + `QueryResults.error`，message 提示缺少 WHERE/LIMIT）
- `SELECT * FROM t WHERE x = 1` / `... LIMIT 10` / `SELECT 1`（无 FROM，`exemptNoFrom`）→ 放行
- `SHOW CATALOGS` / `DESCRIBE t` / `USE ...` → 放行（`allowMetadataStatements`）
- 规则文件热加载：修改 `rules/*.yaml` 后按 `rulesPollSeconds`（默认 5s）自动生效

## 动态规则（管理 API）

```bash
# 新增规则（立即生效）
curl -u admin:trino123 -X POST http://127.0.0.1:8080/admin/rules -d '{
  "id":"block-pii","name":"block pii","action":"REJECT","version":1,
  "appliesTo":["QUERY"],
  "condition":{"forbiddenTables":["ods.pii.user_profile"]},
  "message":"表 %s 受保护"}'

# 查看 / 删除
curl -u admin:trino123 http://127.0.0.1:8080/admin/rules
curl -u admin:trino123 -X DELETE "http://127.0.0.1:8080/admin/rules?id=block-pii"
```

## 限流与排队

- 超限：HTTP 429 + `Retry-After`（秒），客户端按 Trino 惯例等待后重试
- 排队：并发槽位占满时进入队列，超过 `maxGlobalPending`/`maxPerUserPending` 或
  `timeoutSeconds` 未获槽位则 429
- 按用户覆盖：`flow.rateLimit.perUser` / `concurrency.maxRunningPerUser`

## 主要端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/statement` | 提交 SQL（body），携带 `X-Trino-*` 头 |
| GET | `/v1/statement/{gatewayId}/{token}` | 轮询下一页（nextUri 已重写指向网关） |
| DELETE | `/v1/statement/{gatewayId}/{token}` | 取消查询 |
| GET | `/v1/info` `/v1/status` `/v1/node` | 元数据代理 |
| GET | `/metrics` | Prometheus 指标 |
| GET/POST/DELETE | `/admin/rules` | 动态规则管理 |
| GET | `/admin/flow?user=` `/admin/clusters` `/admin/queries` | 流量/集群/查询观测 |
| DELETE | `/admin/queries?id=` | 取消指定网关查询 |

## 配置优先级

`-Dtrinogate.config=path` > 环境变量 `TRINOGATE_CONFIG` > 当前目录 `config.yaml`。
配置值支持 `${ENV_VAR:default}` 插值。

## 测试

```bash
mvn test        # 各模块单测
mvn install     # 全量 + 端到端集成测试
```

`trinogate-app` 内置 `GatewayIntegrationTest`：用内嵌 `HttpServer` 模拟 Trino 后端，
覆盖透传、两页轮询（nextUri 重写）、校验拒绝、元数据放行、限流 429+Retry-After、
管理 API 动态规则增删、取消查询、元数据/指标端点。

## 扩展点

- 新认证方式：实现 `Authenticator` 并注册到 `AuthenticatorFactory`
- 新规则条件：扩展 `RuleDefinition.Condition` 与 `DynamicRule.evaluate`
- 规则来源：当前已实现文件热加载 + admin 内存管理，可按 `RuleManager` 结构扩展 DB/脚本/SPI 通道
- 分布式限流：`mode: redis`（需 Redis；Redis 不可用自动 fail-open）
