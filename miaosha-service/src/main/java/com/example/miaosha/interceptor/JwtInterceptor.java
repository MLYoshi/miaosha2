package com.example.miaosha.interceptor;

import com.example.common.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class JwtInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 1. 从 Authorization Header 里取 token，格式是 "Bearer <token>"
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatus(401);
            return false;
        }

        // 2. 截取 token 部分
        String token = auth.substring(7);

        // 3. 验证 token，无效就拒绝
        if (!JwtUtil.isValid(token)) {
            response.setStatus(401);
            return false;
        }

        // 4. 把解析出的 userId 放到 request 里，后续 Controller 直接用
        request.setAttribute("userId", JwtUtil.parseUserId(token));
        return true;
    }
}
