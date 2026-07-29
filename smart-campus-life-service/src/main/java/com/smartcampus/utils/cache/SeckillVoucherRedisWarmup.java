package com.smartcampus.utils.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Resource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.VoucherOrder;
import com.smartcampus.mapper.voucher.VoucherOrderMapper;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.utils.redis.RedisConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * 将数据库中已有的秒杀券恢复到 Redis。
 *
 * <p>新建秒杀券会在 {@code VoucherServiceImpl#addSeckillVoucher} 中立即写 Redis；
 * 此处仅解决历史数据、Redis 重启或 Redis 数据库被清空后的库存缺失问题。</p>
 *
 * <p>已有 Redis 库存绝不覆盖，避免用数据库的旧库存回滚正在进行的秒杀；
 * 已落库订单则始终补入领取集合，保证重启后仍然一人一单。</p>
 */
@Slf4j
@Component
public class SeckillVoucherRedisWarmup {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 应用可用后执行一次。只补缺失 key，因此重复启动是安全的。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();
        if (seckillVouchers.isEmpty()) {
            return;
        }

        Set<Long> voucherIds = new HashSet<>();
        int restoredStocks = 0;
        for (SeckillVoucher seckillVoucher : seckillVouchers) {
            Long voucherId = seckillVoucher.getVoucherId();
            if (voucherId == null) {
                continue;
            }
            voucherIds.add(voucherId);
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(stockKey))) {
                int stock = seckillVoucher.getStock() == null ? 0 : Math.max(seckillVoucher.getStock(), 0);
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
                restoredStocks++;
            }
        }

        // 只查询必要字段，并按券 ID 分组后批量写入 Set。
        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                new QueryWrapper<VoucherOrder>().select("voucher_id", "user_id"));
        Map<Long, List<String>> userIdsByVoucher = new HashMap<>();
        for (VoucherOrder order : orders) {
            if (order.getVoucherId() == null || order.getUserId() == null
                    || !voucherIds.contains(order.getVoucherId())) {
                continue;
            }
            userIdsByVoucher.computeIfAbsent(order.getVoucherId(), ignored -> new ArrayList<>())
                    .add(String.valueOf(order.getUserId()));
        }

        int restoredOrders = 0;
        for (Map.Entry<Long, List<String>> entry : userIdsByVoucher.entrySet()) {
            List<String> userIds = entry.getValue();
            Long added = stringRedisTemplate.opsForSet().add(
                    RedisConstants.SECKILL_ORDER_KEY + entry.getKey(),
                    userIds.toArray(new String[0]));
            if (added != null) {
                restoredOrders += added.intValue();
            }
        }
        log.info("秒杀券 Redis 预热完成：补充库存 key {} 个，补充领取记录 {} 条", restoredStocks, restoredOrders);
    }
}
