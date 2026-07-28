package com.smartcampus.service.impl;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartcampus.dto.Result;
import com.smartcampus.entity.ShopType;
import com.smartcampus.mapper.ShopTypeMapper;
import com.smartcampus.service.IShopTypeService;
import com.smartcampus.utils.RedisConstants;

import cn.hutool.json.JSONUtil;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询商铺类型列表
     *
     * @return
     */
    @Override
    public Result queryShopTypeList() {
        //1.从缓存中查询
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().
                range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //2.判断是否存在
        if (shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            //3.存在，直接返回
            ArrayList<ShopType> shopTypeList = new ArrayList<>();
            for (String str : shopTypeJsonList) {
                shopTypeList.add(JSONUtil.toBean(str, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        //4.不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList == null) {
            return Result.fail("店铺类型不存在");
        }
        //5.数据库中查到，写入缓存
        for (ShopType shopType : shopTypeList) {
            stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(shopTypeList);
    }

}
