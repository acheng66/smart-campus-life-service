package com.smartcampus.config;

import com.smartcampus.utils.auth.LoginInterceptor;
import com.smartcampus.utils.auth.RefreshTokenInterceptor;
import com.smartcampus.utils.auth.VoucherManageInterceptor;
import com.smartcampus.utils.auth.AdminOnlyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        // 用户浏览店铺时需要公开查询券列表；其余券管理操作必须登录。
                        "/voucher/list/**"
                ).order(1);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        registry.addInterceptor(new VoucherManageInterceptor())
                .addPathPatterns("/voucher", "/voucher/**", "/shop", "/shop/manage/**")
                .excludePathPatterns("/voucher/list/**")
                .order(2);
        registry.addInterceptor(new AdminOnlyInterceptor())
                .addPathPatterns("/admin/**")
                .order(3);
    }
}
