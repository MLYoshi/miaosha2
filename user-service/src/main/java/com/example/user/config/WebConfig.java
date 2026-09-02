package com.example.user.config;

import com.example.user.interceptor.UserContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
            .addPathPatterns("/**")           // 拦截所有接口
            .excludePathPatterns("/user/login", "/user/register"); // 放行登录/注册
    }
}
