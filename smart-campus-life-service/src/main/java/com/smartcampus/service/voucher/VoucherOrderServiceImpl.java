package com.smartcampus.service.voucher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.config.RabbitMQConfig;
import com.smartcampus.dto.MyVoucherDTO;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.Shop;
import com.smartcampus.entity.Voucher;
import com.smartcampus.entity.VoucherOrder;
import com.smartcampus.mapper.shop.ShopMapper;
import com.smartcampus.mapper.voucher.VoucherOrderMapper;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.service.voucher.IVoucherOrderService;
import com.smartcampus.service.voucher.IVoucherService;
import com.smartcampus.utils.redis.RedisIdWorker;
import com.smartcampus.utils.auth.UserHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    @Lazy
    private IVoucherOrderService proxy;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ShopMapper shopMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * RabbitMQ 消费者 - 监听秒杀订单队列
     *
     * 可靠性说明：
     * - acknowledge-mode=auto：方法正常返回后 Spring 自动 ACK，抛出异常时自动 NACK
     * - Spring Retry 会在异常时自动重试（application.yaml 配置 max-attempts=3，指数退避）
     * - 重试耗尽后消息自动进入死信队列（seckill.order.dlq）
     *
     * @param voucherOrder 订单对象
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void handleSeckillOrder(VoucherOrder voucherOrder) {
        // 处理订单（若抛出异常，Spring Retry 会自动重试，重试耗尽后进入死信队列）
        handleVoucherOrder(voucherOrder);
    }

    /**
     * 处理订单创建逻辑（含幂等检查）
     *
     * 幂等策略：使用 Redis SETNX 对 orderId 加标记，TTL=24h
     * - 首次消费：SETNX 成功 → 继续处理
     * - 重复消费（重试/网络异常）：SETNX 失败 → 直接跳过，保证幂等
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long orderId = voucherOrder.getId();
        Long userId = voucherOrder.getUserId();

        String idempotentKey = "order:idempotent:" + orderId;
        Boolean isFirstConsume = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, java.util.concurrent.TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isFirstConsume)) {
            log.warn("[幂等] 订单已处理过，跳过重复消费，orderId={}", orderId);
            return;
        }
        if (isFirstConsume == null) {
            throw new IllegalStateException("写入订单幂等标记失败，orderId=" + orderId);
        }

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        try {
            if (!lock.tryLock()) {
                log.error("获取分布式锁失败，orderId={}", orderId);
                throw new IllegalStateException("获取订单锁失败，orderId=" + orderId);
            }
            proxy.createVoucherOrder(voucherOrder);
        } catch (RuntimeException e) {
            // 本次消费未成功：撤销幂等标记，允许 RabbitMQ 重试时重新处理。
            Boolean deleted = stringRedisTemplate.delete(idempotentKey);
            if (!Boolean.TRUE.equals(deleted)) {
                log.warn("撤销订单幂等标记失败，orderId={}", orderId);
            }
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 秒杀优惠券
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本（不再需要传递orderId）
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        // 2.判断结果是否为0
        int resultInt = result.intValue();
        if (resultInt != 0) {
            // 2.1不为0，代表没有购买资格
            return Result.fail(resultInt == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2为0，有购买资格，发送消息到 RabbitMQ
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 发送消息到 RabbitMQ
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_EXCHANGE,
                RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,
                voucherOrder);

        // 3.返回订单id
        return Result.ok(orderId);

    }

    /**
     * 领取普通券。
     *
     * <p>普通券没有秒杀库存，因此不使用 Redis/Lua；领取关系直接落到数据库。
     * 数据库唯一索引 {@code uk_user_voucher(user_id, voucher_id)} 是并发下的最终兜底，
     * 即使两个请求同时通过预检，也只能成功创建一条记录。</p>
     */
    @Override
    @Transactional
    public Result receiveVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        if (!Integer.valueOf(0).equals(voucher.getType())) {
            return Result.fail("该优惠券为秒杀券，请使用秒杀领取接口");
        }
        if (!Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("优惠券当前不可领取");
        }
        boolean received = query().eq("user_id", userId).eq("voucher_id", voucherId).count() > 0;
        if (received) {
            return Result.fail("不能重复领取");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        // 普通券不依赖 Redis 生成订单号，使用 MyBatis-Plus 的雪花 ID。
        voucherOrder.setId(IdWorker.getId());
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setPayType(1);
        voucherOrder.setStatus(1);
        try {
            if (!save(voucherOrder)) {
                return Result.fail("领取失败，请稍后重试");
            }
        } catch (DuplicateKeyException e) {
            // 并发请求同时通过预检时，由数据库唯一索引保证只会成功一次。
            return Result.fail("不能重复领取");
        }
        return Result.ok(voucherOrder.getId());
    }

    @Override
    public Result queryMyVouchers() {
        Long userId = UserHolder.getUser().getId();
        List<VoucherOrder> rawOrders = query().eq("user_id", userId).orderByDesc("create_time").list();
        // 业务规则是一人一券。即使旧数据或历史测试曾留下重复订单，
        // 列表也只展示同一 voucherId 最新的一条，不能让用户看到多张重复券。
        List<VoucherOrder> orders = rawOrders.stream()
                .collect(Collectors.toMap(VoucherOrder::getVoucherId, Function.identity(), (newer, ignored) -> newer,
                        java.util.LinkedHashMap::new))
                .values().stream().collect(Collectors.toList());
        if (orders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        Set<Long> voucherIds = orders.stream().map(VoucherOrder::getVoucherId).collect(Collectors.toSet());
        Map<Long, Voucher> voucherMap = voucherService.listByIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, Function.identity()));
        Map<Long, SeckillVoucher> seckillMap = seckillVoucherService.query()
                .in("voucher_id", voucherIds).list().stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, Function.identity()));
        Set<Long> shopIds = voucherMap.values().stream().map(Voucher::getShopId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Shop> shopMap = shopIds.isEmpty() ? Collections.emptyMap()
                : shopMapper.selectBatchIds(shopIds).stream()
                        .collect(Collectors.toMap(Shop::getId, Function.identity()));

        List<MyVoucherDTO> result = orders.stream().map(order -> {
            Voucher voucher = voucherMap.get(order.getVoucherId());
            MyVoucherDTO dto = new MyVoucherDTO();
            dto.setOrderId(order.getId());
            dto.setVoucherId(order.getVoucherId());
            dto.setOrderStatus(order.getStatus());
            dto.setReceivedAt(order.getCreateTime());
            if (voucher == null) {
                dto.setTitle("优惠券已不可用");
                dto.setVoucherStatus(3);
                return dto;
            }
            dto.setShopId(voucher.getShopId());
            dto.setTitle(voucher.getTitle());
            dto.setSubTitle(voucher.getSubTitle());
            dto.setPayValue(voucher.getPayValue());
            dto.setActualValue(voucher.getActualValue());
            dto.setType(voucher.getType());
            dto.setVoucherStatus(voucher.getStatus());
            Shop shop = shopMap.get(voucher.getShopId());
            if (shop != null) {
                dto.setShopName(shop.getName());
            }
            SeckillVoucher seckillVoucher = seckillMap.get(voucher.getId());
            if (seckillVoucher != null) {
                dto.setBeginTime(seckillVoucher.getBeginTime());
                dto.setEndTime(seckillVoucher.getEndTime());
            }
            return dto;
        }).collect(Collectors.toList());
        return Result.ok(result);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.兜底幂等：数据库层一人一单校验（防止 Redis 幂等 key 过期后的极端情况）
        Long userId = voucherOrder.getUserId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // Bug Fix: 已购买过须立即 return，否则后续代码仍会扣库存、插订单记录
            log.error("[兜底幂等] 用户已经购买过，orderId={}", voucherOrder.getId());
            return;
        }
        // 6.扣减库存,乐观锁
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足，orderId={}", voucherOrder.getId());
            return;
        }
        save(voucherOrder);
    }
}
