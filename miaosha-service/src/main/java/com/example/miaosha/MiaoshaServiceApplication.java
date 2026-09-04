package com.example.miaosha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;


@SpringBootApplication
@EnableFeignClients
public class MiaoshaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiaoshaServiceApplication.class, args);
    }
}
