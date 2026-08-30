package com.example.seckill.controller;

import com.example.seckill.common.JwtUtil;
import com.example.seckill.common.Result;
import com.example.seckill.domain.User;
import com.example.seckill.service.UserService;
import com.example.seckill.vo.LoginVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 1. 登录接口
     * 接收登录参数，校验成功后返回生成的 JWT Token
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginVo loginVo) {
        // 校验手机号和密码（验证失败会直接抛出 MiaoshaException，由全局异常处理器接管）
        User user = userService.login(loginVo);

        // 校验成功，使用用户的 id 生成 JWT Token
        String token = JwtUtil.generateToken(user.getId());

        // 返回包含 Token 的成功响应
        return Result.success(token);
    }

    /**
     * 2. 注册接口
     * 手机号+密码注册，成功后直接返回 JWT Token（免登录）
     */
    @PostMapping("/register")
    public Result<String> register(@RequestBody @Valid LoginVo registerVo) {
        // 手机号已注册会抛 MiaoshaException(MOBILE_ALREADY_EXIST)，由全局异常处理器接管
        User user = userService.register(registerVo);

        return Result.success(JwtUtil.generateToken(user.getId()));
    }

    /**
     * 3. 受保护的个人信息接口
     */
    @GetMapping("/profile")
    public Result<User> profile(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        User user = userService.getById(userId);
        if (user == null) {
            return Result.error(500501, "用户不存在");
        }

        // 隐藏敏感信息
        user.setPassword(null);
        user.setSalt(null);

        return Result.success(user);
    }
}