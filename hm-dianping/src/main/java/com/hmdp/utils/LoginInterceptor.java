package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //在并发请求情况下，因为每次请求都有不同的用户信息，我们必须保证每次请求保存的用户信息互不干扰，线程独立。

        //1、判断是否需要拦截(ThreadLocal中是否有用户/需要用户登录的功能，用户是否已经登录再进行操作)
        if (UserHolder.getUser() == null) {
            //没有用户，拦截,设置状态码
            response.setStatus(401);
            return false;
        }
        //有用户，放行，不用刷新时间，这在上一级的拦截器中已进行
        return true;
    }
}
