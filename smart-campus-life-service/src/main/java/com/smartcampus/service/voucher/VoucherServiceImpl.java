package com.smartcampus.service.voucher;

import java.util.List;

import jakarta.annotation.Resource;

import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.Result;
import com.smartcampus.dto.UserDTO;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.entity.Shop;
import com.smartcampus.entity.Voucher;
import com.smartcampus.mapper.shop.ShopMapper;
import com.smartcampus.mapper.voucher.VoucherMapper;
import com.smartcampus.mapper.voucher.VoucherOrderMapper;
import com.smartcampus.service.voucher.ISeckillVoucherService;
import com.smartcampus.service.voucher.IVoucherService;
import com.smartcampus.utils.redis.RedisConstants;
import com.smartcampus.utils.auth.UserHolder;
import com.smartcampus.utils.auth.UserRole;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private ShopMapper shopMapper;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    public Result addVoucher(Voucher voucher) {
        String permissionError = validateManageShop(voucher.getShopId());
        if (permissionError != null) {
            return Result.fail(permissionError);
        }
        // 普通券不允许由调用方伪造为秒杀券。
        voucher.setType(0);
        if (!save(voucher)) {
            return Result.fail("新增普通券失败");
        }
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(Voucher voucher) {
        String permissionError = validateManageShop(voucher.getShopId());
        if (permissionError != null) {
            return Result.fail(permissionError);
        }
        if (voucher.getStock() == null || voucher.getStock() < 0) {
            return Result.fail("秒杀券库存不能为空且不能小于 0");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            return Result.fail("秒杀券开始时间必须早于结束时间");
        }
        // 此接口创建的一定是秒杀券，不能依赖前端是否正确传入 type。
        voucher.setType(1);
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 数据库提交成功后才对外开放 Redis 库存，避免事务回滚留下可抢的脏库存。
        afterCommit(() -> writeSeckillStock(voucher.getId(), voucher.getStock()));
        return Result.ok(voucher.getId());
    }

    /**
     * 更新券的展示信息；券类型与上下架状态分别由专用接口管理，避免误把普通券、秒杀券相互转换。
     */
    @Override
    @Transactional
    public Result updateVoucher(Long voucherId, Voucher voucher) {
        Voucher oldVoucher = getById(voucherId);
        if (oldVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        String permissionError = validateManageShop(oldVoucher.getShopId());
        if (permissionError != null) {
            return Result.fail(permissionError);
        }
        if (voucher.getType() != null && !voucher.getType().equals(oldVoucher.getType())) {
            return Result.fail("不支持直接变更优惠券类型");
        }
        SeckillVoucher oldSeckillVoucher = null;
        if (Integer.valueOf(1).equals(oldVoucher.getType())) {
            oldSeckillVoucher = seckillVoucherService.getById(voucherId);
            if (oldSeckillVoucher == null) {
                return Result.fail("秒杀券库存记录不存在");
            }
        }

        voucher.setId(voucherId);
        // 店铺归属不可通过更新券信息接口变更，避免商家将券迁移到其他店铺。
        voucher.setShopId(oldVoucher.getShopId());
        voucher.setType(oldVoucher.getType());
        // 状态仅能通过上下架接口变更，防止修改信息时意外重新上架。
        voucher.setStatus(null);
        if (!updateById(voucher)) {
            return Result.fail("更新优惠券失败");
        }

        if (!Integer.valueOf(1).equals(oldVoucher.getType())) {
            return Result.ok();
        }
        if (voucher.getStock() != null || voucher.getBeginTime() != null || voucher.getEndTime() != null) {
            SeckillVoucher seckillVoucher = new SeckillVoucher();
            seckillVoucher.setVoucherId(voucherId);
            seckillVoucher.setStock(voucher.getStock());
            seckillVoucher.setBeginTime(voucher.getBeginTime());
            seckillVoucher.setEndTime(voucher.getEndTime());
            if (!seckillVoucherService.updateById(seckillVoucher)) {
                return Result.fail("更新秒杀券信息失败");
            }
        }
        // 仅当管理员明确修改库存且活动仍上架时，才用新库存覆盖 Redis。
        if (voucher.getStock() != null && Integer.valueOf(1).equals(oldVoucher.getStatus())) {
            afterCommit(() -> writeSeckillStock(voucherId, voucher.getStock()));
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result changeVoucherStatus(Long voucherId, Integer status) {
        if (status == null || (status != 1 && status != 2 && status != 3)) {
            return Result.fail("状态值仅支持 1（上架）、2（下架）、3（过期）");
        }
        Voucher voucher = getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        String permissionError = validateManageShop(voucher.getShopId());
        if (permissionError != null) {
            return Result.fail(permissionError);
        }
        if (!update().eq("id", voucherId).set("status", status).update()) {
            return Result.fail("更新优惠券状态失败");
        }
        if (!Integer.valueOf(1).equals(voucher.getType())) {
            return Result.ok();
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀券库存记录不存在");
        }
        afterCommit(() -> {
            if (status == 1) {
                writeSeckillStock(voucherId, seckillVoucher.getStock());
            } else {
                // Lua 读不到库存 key 时会拒绝请求，防止下架券被直接调用秒杀接口领取。
                stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            }
        });
        return Result.ok();
    }

    @Override
    @Transactional
    public Result deleteVoucher(Long voucherId) {
        Voucher voucher = getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        String permissionError = validateManageShop(voucher.getShopId());
        if (permissionError != null) {
            return Result.fail(permissionError);
        }
        Long orderCount = voucherOrderMapper.selectCount(
                new QueryWrapper<com.smartcampus.entity.VoucherOrder>().eq("voucher_id", voucherId));
        if (orderCount != null && orderCount > 0) {
            return Result.fail("该优惠券已有领取记录，请下架而不要删除");
        }
        if (Integer.valueOf(1).equals(voucher.getType())
                && !seckillVoucherService.removeById(voucherId)) {
            return Result.fail("删除秒杀券库存记录失败");
        }
        if (!removeById(voucherId)) {
            return Result.fail("删除优惠券失败");
        }
        if (Integer.valueOf(1).equals(voucher.getType())) {
            afterCommit(() -> {
                stringRedisTemplate.delete(RedisConstants.SECKILL_STOCK_KEY + voucherId);
                stringRedisTemplate.delete(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            });
        }
        return Result.ok();
    }

    private void writeSeckillStock(Long voucherId, Integer stock) {
        int safeStock = stock == null ? 0 : Math.max(stock, 0);
        stringRedisTemplate.opsForValue().set(
                RedisConstants.SECKILL_STOCK_KEY + voucherId, String.valueOf(safeStock));
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 管理权限的最终校验。管理员可管理全站；商家只能管理 owner_id 等于自己的店铺。
     * 返回 null 表示通过，否则返回可直接展示给前端的失败消息。
     */
    private String validateManageShop(Long shopId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return "请先登录";
        }
        if (shopId == null) {
            return "优惠券必须关联店铺";
        }
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return "店铺不存在";
        }
        if (Integer.valueOf(UserRole.ADMIN).equals(user.getRole())) {
            return null;
        }
        if (!Integer.valueOf(UserRole.MERCHANT).equals(user.getRole())) {
            return "没有管理优惠券的权限";
        }
        if (!user.getId().equals(shop.getOwnerId())) {
            return "无权管理该店铺的优惠券";
        }
        return null;
    }
}
