package com.smartcampus.service.voucher;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.entity.SeckillVoucher;
import com.smartcampus.mapper.voucher.SeckillVoucherMapper;
import com.smartcampus.service.voucher.ISeckillVoucherService;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

}
