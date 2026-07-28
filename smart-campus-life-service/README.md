# 智享校园生活服务平台

基于 Spring Boot 的校园生活服务平台，提供验证码登录、校园商户浏览与附近定位、探店笔记、点赞关注，以及 Redis + Lua + RabbitMQ 驱动的优惠券秒杀能力。

> 这是一个用于学习和展示后端工程能力的单体服务。Java 包名、应用名、数据库名和前端展示名称均已统一为校园生活平台语义。

## 核心能力

- **认证与鉴权**：验证码存 Redis（开发环境输出日志模拟发送）；登录成功后以 UUID 作为 Token、用户摘要存 Redis Hash；双拦截器完成 Token 刷新、`ThreadLocal` 用户透传与受保护接口鉴权。
- **商户与缓存**：商户详情实现缓存空值、随机 TTL、Redisson 互斥锁与提交后失效；附近商户使用 Redis GEO 按距离检索，并在商户更新后同步索引。
- **优惠券秒杀**：Lua 脚本原子完成库存预扣减和一人一单校验；有效请求投递 RabbitMQ；消费者以 Redis 幂等键、Redisson 用户锁和 MySQL 条件扣减处理订单。
- **交易兜底**：`tb_voucher_order(user_id, voucher_id)` 唯一索引保证同一用户不能为同一优惠券创建多笔订单；库存扣减使用 `stock > 0` 条件更新避免超卖。
- **社区互动**：探店笔记发布、点赞、关注与共同关注。

## 秒杀链路

```text
浏览器 → Lua（库存 / 一人一单） → RabbitMQ → 幂等检查
       → Redisson 用户锁 → MySQL 条件扣库存 + 创建订单
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
