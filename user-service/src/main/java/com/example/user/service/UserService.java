package com.example.user.service;

import com.example.common.CodeMsg;
import com.example.common.MiaoshaException;
import com.example.user.common.MD5Util;
import com.example.user.dao.UserMapper;
import com.example.user.domain.User;
import com.example.user.vo.LoginVo;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public User getById(Long id) {
        return userMapper.getById(id);
    }


    public User login(LoginVo loginVo) {
        User user = userMapper.getById(Long.valueOf(loginVo.getMobile()));
        if (user == null) {
          throw new MiaoshaException(CodeMsg.MOBILE_NOT_EXIST);
        }
        String dbPass = MD5Util.inputPassToDbPass(loginVo.getPassword(), user.getSalt());
        if (!dbPass.equals(user.getPassword())) {
          throw new MiaoshaException(CodeMsg.PASSWORD_ERROR);
        }
        return user;
    }

    /**
     * 注册新用户：手机号即用户 id（沿用登录约定）。
     * 密码与登录校验同源：inputPassToDbPass(明文, 随机salt)。
     */
    public User register(LoginVo registerVo) {
        Long id = Long.valueOf(registerVo.getMobile());
        if (userMapper.getById(id) != null) {
          throw new MiaoshaException(CodeMsg.MOBILE_ALREADY_EXIST);
        }

        String salt = randomSalt();
        User user = new User();
        user.setId(id);
        user.setNickname("user" + registerVo.getMobile());
        user.setPassword(MD5Util.inputPassToDbPass(registerVo.getPassword(), salt));
        user.setSalt(salt);
        user.setRegisterDate(LocalDateTime.now());
        user.setLoginCount(0);
        userMapper.insert(user);
        return user;
    }

    /** 6 位随机盐（formPassToDbPass 依赖 salt.charAt(5)，长度必须 ≥ 6）。 */
    private String randomSalt() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }
}
