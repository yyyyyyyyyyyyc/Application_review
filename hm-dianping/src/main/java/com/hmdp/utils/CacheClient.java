package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.events.Event;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Slf4j
//日志记录的操作
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1、从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、存在，直接放返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if (json != null) {
            return null;
        }
        //3、不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //4、数据库不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //5、存在，写入redis
        this.set(key,r,time,unit);
        //6、返回
        return r;
    }

    //逻辑过期解决缓存击穿
    //创建一个线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        //1、从Redis查询商品缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否命中，未命中，直接返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3、命中，判断缓存是否过期
        //反序列化json为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        R r = JSONUtil.toBean((JSONObject)redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回
            return r;
        }
        //过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获取锁成功，开启一个独立的线程进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                try {
                    //this.saveShop2Redis(id,20L);
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //4、未过期或者未获取锁成功，返回商铺信息
        return r;
    }

    //获取锁和释放锁的方法(核心思路，利用redis的setnx方法来表示获取锁)
    private boolean tryLock(String key){
        boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        //自动拆箱可能会获取空指针
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
