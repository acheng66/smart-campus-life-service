package com.smartcampus.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    // v2 使用带 TTL 的列表缓存，避免与旧版无 TTL 的 key 混用。
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type:v2";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final String LOCK_SHOP_TYPE_KEY = "lock:shop:type";
    public static final String SHOP_BLOOM_FILTER_KEY = "bf:shop:id";
    public static final String SHOP_BLOOM_READY_KEY = "bf:shop:id:ready";
    public static final String LOCK_SHOP_BLOOM_REBUILD_KEY = "lock:shop:bloom:rebuild";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String BLOG_LIKED = "blog:liked:";
}
