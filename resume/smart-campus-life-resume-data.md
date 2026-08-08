# 简历结构化草稿（待个人信息确认）

> 目标方向暂按 Java 后端开发 / AI 应用开发整理。所有项目描述均来自当前仓库代码；
> 姓名、教育经历、项目时间、个人链接及真实性能数据尚未提供，因此不做猜测。

## Header

- 姓名：`[缺失]`
- 目标岗位：`Java 后端开发工程师 / AI 应用开发工程师 [待确认]`
- 城市：`[缺失]`
- 邮箱：`[缺失]`
- 电话：`[缺失，可选]`
- GitHub / Gitee：`[缺失]`

## 个人简介

具备 Java 后端与 LLM 应用开发经验，使用 Spring AI 构建 Tool Calling、Hybrid RAG、记忆、
工作流与自动评测体系，并具备 Redis、RabbitMQ、MySQL 高并发业务实践。

## 项目经历

### 智享校园生活服务平台

- 角色：`[待确认：独立开发 / 核心开发 / 课程设计]`
- 时间：`[缺失]`
- 项目链接：`[缺失]`
- 技术栈：Java 17、Spring Boot、Spring AI、MySQL、Redis、Redisson、RabbitMQ、
  PostgreSQL、pgvector、DeepSeek、SiliconFlow、Vue 2、Docker

项目描述：面向校园商户、优惠券与内容社区的一体化平台，支持附近商户检索、普通/秒杀券领取及探店互动；
集成基于 Spring AI 的校园助手，通过 7 个业务工具提供店铺推荐、查券与资格校验等 5 类智能服务。

核心成果：

1. 基于 Redis Lua 原子校验库存与一人一券，使用 RabbitMQ 异步削峰；结合 SETNX 幂等、
   Redisson 用户锁、MySQL 条件扣减与唯一索引防止重复下单和超卖，并配置重试、Confirm/Return 与死信队列。
2. 构建“Redis + Bloom Filter + 空值缓存 + 分布式锁 + MySQL”缓存链路，通过双重检查、随机 TTL
   及事务提交后缓存/GEO 更新，治理缓存穿透、击穿、雪崩与数据一致性问题。
3. 基于 Spring AI `ChatClient` 构建受控 Agent Harness，编排 7 个 Tool 与 5 类业务意图；通过
   查询/展示工具分离、ID 与卡片白名单校验及 5 分钟一次性 Token，实现 Human-in-the-loop 安全领券。
4. 实现 Hybrid RAG，将 pgvector 与 PostgreSQL 各 Top 12 结果经 Metadata Filter、RRF 和
   SiliconFlow Reranker 收敛至 Top 4；采用版本化索引原子切换与事务后增量更新保障知识可用性。
5. 使用 Redis 保存 12 条/24 小时短期记忆与 180 天长期偏好，设计 10 状态持久化工作流，记录
   工具轨迹、RAG 命中和耗时，支持异常恢复、确定性降级及带 15 秒心跳的 SSE 过程事件。
6. 构建 30 条 Golden Dataset（8 Smoke/14 Regression/8 Security），覆盖 6 类能力并评估意图、
   Tool Planning、Recall@K、卡片一致性及越权写操作，为 Prompt、工具与检索迭代提供回归基线。

## 教育经历

- 学校：`[缺失]`
- 学位与专业：`[缺失]`
- 时间：`[缺失]`
- GPA / 荣誉 / 相关课程：`[可选]`

## 技能

- Java：Java 17、Spring Boot、Spring MVC、MyBatis-Plus、Spring AMQP
- 数据与中间件：MySQL、Redis、Redisson、RabbitMQ、PostgreSQL、pgvector
- AI 工程：Spring AI、Tool Calling、Hybrid RAG、RRF、Reranker、Agent Workflow、Eval
- 工程工具：Git、Maven、Docker Compose、Nginx、Postman、Vue 2

## 需要确认的数字与事实

- 项目开发起止时间。
- 你在项目中的角色，是否可以写“独立开发”。
- 是否有压测数据：并发用户数、QPS/TPS、p95 延迟、数据库请求下降比例等。
- Agent 需要补充稳定复测指标：各级评测通过率、意图准确率、工具调用成功率、Recall@K、
  p95 响应时延、平均 Tool Call 次数及单请求 Token 消耗；未复测前不在正式简历中虚构结果。
- 是否有公开仓库或演示地址。
- 30 条 Golden Dataset 是代码中的当前规模，可直接使用；不要把历史单次评测通过率写入简历，除非完成稳定复测。
