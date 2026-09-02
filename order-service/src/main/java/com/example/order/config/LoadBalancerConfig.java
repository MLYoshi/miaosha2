package com.example.order.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 出站 HTTP 负载均衡配置：{@link RestClient.Builder} 标 {@code @LoadBalanced}，
 * 使 client 以服务名（http://goods-service）发起调用时经 Spring Cloud LoadBalancer
 * 解析为真实实例地址。
 */
@Configuration
public class LoadBalancerConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
