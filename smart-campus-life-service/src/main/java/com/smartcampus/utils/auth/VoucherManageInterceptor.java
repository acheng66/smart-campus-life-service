package com.smartcampus.utils.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.smartcampus.dto.UserDTO;

/**
 * 券管理入口权限：只允许平台管理员或商家进入。
 * 商家对具体店铺的归属校验仍在业务层完成，防止绕过 URL 规则。
 */
public class VoucherManageInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        Integer role = user.getRole();
        if (!Integer.valueOf(UserRole.ADMIN).equals(role)
                && !Integer.valueOf(UserRole.MERCHANT).equals(role)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
