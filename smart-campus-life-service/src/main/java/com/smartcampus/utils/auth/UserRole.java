package com.smartcampus.utils.auth;

/** 系统内置角色。角色值会随登录态一并写入 Redis。 */
public final class UserRole {
    private UserRole() {
    }

    /** 普通学生，只能浏览、领取和查看自己的订单。 */
    public static final int STUDENT = 0;
    /** 平台管理员，可以管理全部优惠券。 */
    public static final int ADMIN = 1;
    /** 商家，只能管理归属给自己的店铺及其优惠券。 */
    public static final int MERCHANT = 2;
}
