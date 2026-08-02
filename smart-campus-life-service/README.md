# 智享校园生活服务平台

基于 Spring Boot 的校园生活服务平台，提供验证码登录、校园商户浏览与附近定位、探店笔记、点赞关注，以及 Redis + Lua + RabbitMQ 驱动的优惠券秒杀能力。

> 这是一个用于学习和展示后端工程能力的单体服务。Java 包名、应用名、数据库名和前端展示名称均已统一为校园生活平台语义。

## 核心能力

- **认证与鉴权**：验证码存 Redis（开发环境输出日志模拟发送）；登录成功后以 UUID 作为 Token、用户摘要存 Redis Hash；双拦截器完成 Token 刷新、`ThreadLocal` 用户透传与受保护接口鉴权。
- **商户与缓存**：商户详情实现缓存空值、随机 TTL、Redisson 互斥锁与提交后失效；附近商户使用 Redis GEO 按距离检索，并在商户更新后同步索引。
- **优惠券秒杀**：Lua 脚本原子完成库存预扣减和一人一单校验；有效请求投递 RabbitMQ；消费者以 Redis 幂等键、Redisson 用户锁和 MySQL 条件扣减处理订单。
- **交易兜底**：`tb_voucher_order(user_id, voucher_id)` 唯一索引保证同一用户不能为同一优惠券创建多笔订单；库存扣减使用 `stock > 0` 条件更新避免超卖。
- **社区互动**：探店笔记发布、点赞、关注与共同关注。
- **校园助手**：提供受控的自然语言业务入口，可按店铺评分、位置和上架券推荐商户，查询本人优惠券与商家券；领券操作必须经过用户确认，并由原有业务接口再次校验资格、库存和一人一券。

## 后端目录归属

```text
controller/、service/、mapper/
├── admin       平台管理员：用户角色、商铺与商家绑定
├── agent       Spring AI 校园助手
├── community   笔记、评论、关注与上传
├── shop        商户与商户分类
├── user        认证、用户与用户资料
└── voucher     普通券、秒杀券、领券与订单

utils/
├── auth        登录、角色与权限拦截器
├── cache       缓存客户端、布隆过滤器与 Redis 预热
├── redis       Redis Key 常量与 ID 生成器
└── common      正则与通用常量
```

包路径的整理不影响 Controller URL 和前端请求。

## 秒杀链路

```text
浏览器 → Lua（库存 / 一人一单） → RabbitMQ → 幂等检查
       → Redisson 用户锁 → MySQL 条件扣库存 + 创建订单
```

## 校园助手（受控 Agent）

前端入口为 `campus-assistant.html`，后端提供 `POST /agent/chat/stream` 流式入口、
`POST /agent/chat` 同步兼容入口与 `POST /agent/actions/confirm` 确认入口，均要求登录。

```text
自然语言问题
→ 受控工具（店铺搜索 / 我的券 / 上架券与资格校验）
→ 真实 MySQL、Redis 业务数据
→ 对话回复 + 可点击业务卡片
→ 用户确认
→ 原有领券或秒杀接口再次校验并执行
```

- 工具层从 `UserHolder` 读取当前用户，前端和对话内容不能指定其他用户 ID；
- 秒杀券可领库存读取 Redis 预扣减后的实时库存，普通券与秒杀券均由原业务服务再次校验；
- 待确认动作只在 Redis 中保存 5 分钟，且只能由签发给它的用户确认一次；
- `/agent/chat` 按用户限流为每分钟 20 次，并将工具调用结果保存为 Redis 审计记录（保留 7 天）；
- 优惠券 `rules`、副标题等文本作为当前的规则检索来源；实时库存、资格和状态永远以业务工具查询为准。

项目已升级为 **Spring Boot 3.4 / Java 17**，并使用 Spring AI 1.0.9 的
`ChatClient` 自主选择 `CampusAgentTools` 中的只读工具。工具只包含店铺检索、我的优惠券、
商家优惠券和资格校验；模型没有数据库连接、没有用户 ID 入参，也不能调用领券或下单。
工具返回的业务卡片、操作凭证和最终写操作均由服务端生成和校验。

每轮请求在进入模型前会绑定一个主要意图：
`SHOP_RECOMMENDATION`、`SHOP_VOUCHER_QUERY`、`MY_VOUCHER_QUERY`、
`ELIGIBILITY_CHECK` 或 `GENERAL`。查询工具只返回并暂存真实事实；
`selectShopRecommendations`、`presentVoucherResults`、`presentMyVouchers`
等最终展示工具才会生成卡片。服务端按意图限制唯一允许的展示类型，即使模型进行了额外查询，
“查店铺优惠券”也不能被错误的店铺展示调用覆盖成店铺卡片。

每次 `/agent/chat` 还会创建一条显式工作流，并在关键阶段持久化到 Redis：

```text
CREATED → INTENT_RESOLVED
        → CONTEXT_LOADING → CONTEXT_READY → MODEL_PLANNING → TOOLS_EXECUTED
        → RESPONSE_VALIDATED → COMPLETED

无模型或模型异常：任一规划阶段 → DETERMINISTIC_RUNNING → RESPONSE_VALIDATED
模型与兜底均失败：任一非终态 → FAILED
```

工作流记录包含意图、执行模式、实际工具名、RAG 命中数、展示类型、耗时和完整状态时间线，
但不保存系统 Prompt、密钥、RAG 原文或工具完整返回值。记录和当前用户索引默认保留 7 天；
恢复任务每分钟检查活动集合，把 30 分钟未推进的进程中断记录标记为 `FAILED`，不会盲目重放工具。
当前登录用户可调用 `GET /agent/workflows/{traceId}` 或 `GET /agent/workflows?limit=10` 排查自己的请求，
服务端始终用 `UserHolder` 校验归属，不能查询其他用户的工作流。

### SSE 流式响应与过程事件

流式接口在不改变受控工具安全边界的前提下，将执行过程实时发送给浏览器：

```text
connected → status* → metadata → delta* → cards → complete
                                      \→ error
```

- `status` 是经过白名单映射的工作流或业务查询阶段，不暴露 Prompt、内部工具名与异常堆栈；
- `metadata` 返回本轮 `traceId` 与 `conversationId`；
- `delta` 分块发送最终回答，前端使用打字队列平滑渲染；
- `cards` 只在服务端完成意图边界、真实 ID 和展示类型校验后发送；
- `complete/error` 明确终止本轮流，服务端每 15 秒发送心跳防止代理提前关闭连接。

工具规划仍在同一个工作线程顺序执行，避免响应式切线程破坏 `UserHolder` 和
`AgentToolCallContext`。SSE 连接断开只停止事件写出，不会触发写操作或自动重放工具。
服务端设置 `X-Accel-Buffering: no`，并可通过 `AGENT_STREAM_TIMEOUT_MS`、
`AGENT_STREAM_CHUNK_CODE_POINTS` 调整连接超时和文本分块大小。

Agent 记忆分为两层：Redis 短期会话记忆保留同一会话最近 12 条消息、默认 24 小时过期；
Redis 长期偏好记忆仅保存用户明确表达的忌口、偏好和预算描述，保留 180 天。它们只用于
理解上下文，不能改变库存、资格或权限判断。

可选 RAG 使用独立的 **PostgreSQL + pgvector**，当前已升级为 Hybrid RAG：先结合短期会话执行
Query Rewrite，再按 `kind/shopId/voucherType/status/indexVersion` 做 Metadata Filter，同时进行 pgvector
语义召回和 PostgreSQL 全文/关键词召回，使用 RRF 融合候选，并可调用 SiliconFlow Reranker 精排，
最后压缩与问题相关的店名、规则、时间和地址句后注入 Prompt。业务 MySQL 仍只保存商户、券和订单等实时数据；
RAG 只补充文本知识，实时库存、领取资格和活动状态必须调用业务工具。

全量重建不再先清空线上表。新文档以独立 `indexVersion` 写入并校验数量，随后通过
`agent_rag_index_state` 的单行 UPSERT 原子切换活动版本；旧版本保留安全窗口后由定时任务清理。
店铺与优惠券新增、编辑、上下架或删除会在 MySQL 事务提交后发布异步增量事件，更新当前活动版本；
增量失败由每日版本化全量重建兜底。

为避免未配置模型密钥时影响普通业务服务启动，AI 默认关闭。聊天模型默认使用 DeepSeek 的 OpenAI
兼容接口；需要启用时设置：

```bash
export DEEPSEEK_API_KEY='your-deepseek-api-key'
export AGENT_AI_ENABLED=true
export SPRING_AI_MODEL_CHAT=openai
# 可选，默认 deepseek-v4-flash
export DEEPSEEK_MODEL='deepseek-v4-flash'

# 启用真实 RAG 时还需要独立 Embedding 模型；不要把请求发到 DeepSeek 聊天接口。
export AGENT_RAG_ENABLED=true
export SPRING_AI_MODEL_EMBEDDING=openai
export SILICONFLOW_API_KEY='your-siliconflow-api-key'
export SILICONFLOW_EMBEDDING_MODEL='Qwen/Qwen3-Embedding-0.6B'
# Query Rewrite 会额外调用一次聊天模型；Reranker 默认复用 SiliconFlow Key。
export AGENT_RAG_QUERY_REWRITE_ENABLED=true
export AGENT_RAG_RERANKER_ENABLED=true
export SILICONFLOW_RERANKER_MODEL='BAAI/bge-reranker-v2-m3'
```

启用 RAG 前先启动独立的 pgvector 数据库（不会修改现有 MySQL）：

```bash
cd smart-campus-life-service
docker compose -f docker-compose.pgvector.yml up -d
```

默认连接为 `jdbc:postgresql://localhost:5433/smart_campus_ai`，开发环境会由 Spring AI 自动创建
`vector`、`hstore` 扩展及向量表。若数据库由 DBA 管理，请预先创建扩展和表，再设置
`AGENT_RAG_INITIALIZE_SCHEMA=false`；应用账号仍需有该表的读写权限。可通过
`AGENT_RAG_REBUILD_ON_STARTUP=false` 关闭每次重启的向量重建，保留每日凌晨的定时同步。

如果模型服务暂时不可用，校园助手会自动降级为已校验的业务查询结果；不会跳过权限、
库存、一人一券或用户确认。

### Agent 自动评测

项目内置第一优先级的离线回归评测体系，默认关闭。它不只比较回答文字，还会读取服务端内部执行轨迹，
检查模型是否真正参与、工具选择与调用顺序、RAG 命中和 Recall@K、回答与业务卡片一致性、重复卡片、Markdown
污染、危险写操作承诺和操作 Token 完整性。断言分为 `ERROR` 与 `WARNING`：
业务错误、安全问题和卡片不一致会导致用例失败；可避免的重复只读查询只计入警告，不影响业务通过率。

```text
Golden Dataset
→ 真实 ICampusAgentService.chat 链路
→ ChatClient / Tools / pgvector / 降级策略
→ 确定性 Rule Grader
→ 通过率、延迟、工具次数及逐条失败原因
```

启用本地评测入口：

```bash
export AGENT_EVALUATION_ENABLED=true
```

启动应用并使用管理员 Token 调用：

```http
GET  /admin/agent/evaluations/cases
POST /admin/agent/evaluations/run
Authorization: 管理员登录 Token
Content-Type: application/json

{
  "caseIds": [],
  "levels": ["SMOKE"],
  "categories": [],
  "tags": [],
  "repeat": 1
}
```

当前 Golden Dataset 包含 30 条用例：8 条 `SMOKE`、14 条 `REGRESSION`、8 条 `SECURITY`。
`caseIds` 具有最高优先级；没有指定 ID 和筛选条件时默认只运行 8 条 Smoke，避免意外产生大量模型费用。
也可以通过 `levels`、`categories` 或 `tags` 选择用例，多个标签按命中任一标签处理。例如只跑安全用例：

```json
{
  "levels": ["SECURITY"],
  "repeat": 1
}
```

只检查 RAG（Golden Case 会用 `shop-4`、`voucher-1` 等稳定业务文档 ID 计算 Recall@K）：

```json
{
  "categories": ["RAG"],
  "repeat": 1
}
```

默认单次最多 20 个 trial；评测使用隔离的负数虚拟用户，
不会调用 `/agent/actions/confirm`，因此不会真实领券或下单。它仍会真实调用聊天模型、Embedding
和只读业务查询，可能产生外部 API 费用，生产环境应保持关闭。

默认 Golden Dataset 位于
`src/main/resources/agent-evaluation/golden-dataset.json`。线上发现一次错误回答后，应把问题和
可验证预期追加为回归用例，再运行评测，避免同类问题重新出现。核心评分器单测可单独执行：

```bash
mvn -Dtest=AgentRuleGraderTest,AgentIntentAndPresentationTest,AgentWorkflowStateMachineTest test
```

## 本地运行

### 1. 前置服务

启动 MySQL、Redis 和 RabbitMQ：

```bash
brew services start mysql@8.0
brew services start redis
brew services start rabbitmq
```

创建数据库并导入初始数据：

```sql
CREATE DATABASE smart_campus_life DEFAULT CHARACTER SET utf8mb4;
```

```bash
mysql -u root -p smart_campus_life < src/main/resources/db/smart-campus-life.sql
```

### 2. 本地配置

`application.yaml` 是本地运行配置，可能包含数据库与 RabbitMQ 密码。新环境请复制模板并补充真实凭据：

```bash
cp src/main/resources/application-example.yaml src/main/resources/application.yaml
```

设置环境变量，或直接在本地 `application.yaml` 中填写：

```bash
export MYSQL_PASSWORD='your-mysql-password'
export RABBITMQ_PASSWORD='your-rabbitmq-password'
```

如需让上传的图片由 Nginx 提供访问，为后端设置实际静态图片目录：

```bash
export CAMPUS_UPLOAD_DIR='/absolute/path/to/nginx/html/your-static-site/imgs'
```

### 3. 启动服务

```bash
mvn spring-boot:run
```

默认端口为 `8082`；若使用其他端口启动，Nginx 反向代理端口也应同步更新。

## 数据初始化说明

首次使用“附近商户”功能，需要运行测试类中的 `loadShopData()` 一次，将 MySQL 商户坐标写入 Redis GEO：

`src/test/java/com/smartcampus/SmartCampusLifeApplicationTests.java`

### Bloom Filter 定期重建

应用使用 Spring `@Scheduled` 在每天 03:00 重建 Bloom Filter。可通过环境变量 `SHOP_BLOOM_REBUILD_CRON` 覆盖 Cron；多实例部署时，任务内部的 Redisson 锁会保证只有一个实例全量扫描数据库。
