package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{


    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //静态常量要在静态代码块中做初始化
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //指定脚本(设置脚本的位置)
        //ClassPathResource就是Resource目录
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //配置返回值
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX="lock:";

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示(当前线程id)
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //拆箱，防止返回空指针
        return Boolean.TRUE.equals(success);
    }
    //释放锁
//    @Override
//    public void unlock() {
//        //获取改线程原本的线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取用户对应锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id)) {
//            //如果唯一标识相同，则可以释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
    @Override
    public void unlock() {
        //调用lua脚本
        //Collections.singletonList--生成单元素的集合
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                                    Collections.singletonList(KEY_PREFIX + name),
                                    ID_PREFIX + Thread.currentThread().getId());
    }
}
