package com.example.goods.config;

import com.example.goods.interceptor.UserContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
            .addPathPatterns("/**") // goods-service 无登录接口，全量拦截不放行
            .excludePathPatterns("/internal/**"); // 内部接口供 order-service 服务间调用，不走鉴权
    }
}
