package com.example.seckill.config;

import com.example.seckill.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtInterceptor())
            .addPathPatterns("/**")           // 拦截所有接口
            .excludePathPatterns("/user/login", "/user/register"); // 放行登录/注册
    }
}