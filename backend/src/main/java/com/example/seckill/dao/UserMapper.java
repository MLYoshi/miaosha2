package com.example.seckill.dao;

import org.apache.ibatis.annotations.Param;

import com.example.seckill.domain.User;

public interface UserMapper {
    User getById(@Param("id") Long id);

    int insert(User user);
}
