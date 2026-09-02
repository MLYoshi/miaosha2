package com.example.user.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部身份上下文拦截器：读 gateway 下发（JWT 校验后翻译）的 X-User-Id Header，
 * 缺失/非法一律 401，合法则 setAttribute("userId") 供 Controller 取值。
 *
 * <p>JWT 校验已上移 gateway，业务服务不再解析 Bearer Token，仅信任内部身份头。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 1. 读 X-User-Id（gateway 校验 JWT 后下发）
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader == null || userIdHeader.isBlank()) {
            response.setStatus(401);
            return false;
        }

        // 2. 解析 userId，非法就拒绝
        Long userId;
        try {
            userId = Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException e) {
            response.setStatus(401);
            return false;
        }

        // 3. 放入 request，后续 Controller 直接用
        request.setAttribute("userId", userId);
        return true;
    }
}
