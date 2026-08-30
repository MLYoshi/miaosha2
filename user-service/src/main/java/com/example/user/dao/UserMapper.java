package com.example.user.dao;

import org.apache.ibatis.annotations.Param;

import com.example.user.domain.User;

public interface UserMapper {
    User getById(@Param("id") Long id);

    int insert(User user);
}
