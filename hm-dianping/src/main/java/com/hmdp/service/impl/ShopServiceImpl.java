package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //缓存击穿
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    //逻辑过期解决缓存击穿
    //创建一个线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    public Shop queryWithLogicalExpire(Long id){
//        //1、从Redis查询商品缓存
//        String key = CACHE_SHOP_KEY + id;
//        String json = stringRedisTemplate.opsForValue().get(key);
//        //2、判断是否命中，未命中，直接返回空
//        if (StrUtil.isBlank(json)) {
//            return null;
//        }
//        //3、命中，判断缓存是否过期
//        //反序列化json为对象
//        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject)redisData.getData(),Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //未过期，直接返回
//            return shop;
//        }
//        //过期，尝试获取锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        if (isLock) {
//            //获取锁成功，开启一个独立的线程进行缓存重建
//            CACHE_REBUILD_EXECUTOR.submit( ()->{
//                try {
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        //4、未过期或者未获取锁成功，返回商铺信息
//        return shop;
//    }

    //互斥锁解决缓存击穿
//    public Shop queryWithMutex(Long id){
//        //1、查询redis中是否命中
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)) {
//            //2、命中，返回shop
//            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
//            return shop;
//        }
//        if (shopJson != null) {
//            return null;
//        }
//        //3、未命中，尝试获取互斥锁，实现缓存重构
//        String lockKey = "lock:shop:" + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            shop = null;
//            //4、未获取互斥锁，休眠，递归
//            if (!isLock) {
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //5、获取互斥锁成功，根据id查询数据库
//            shop = getById(id);
//            //6、将数据库数据写入redis中
//            //如果数据库中也没有，存入空白值
//            if (shop == null) {
//                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//                return null;
//            }
//            //数据库中存在，存入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //7、释放互斥锁
//            unlock(lockKey);
//        }
//        //8、返回shop数据
//        return shop;
//    }

    /*public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、存在，直接放返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        //3、不存在，根据id查询数据库
        Shop shop=getById(id);
        //4、数据库不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //5、存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6、返回
        return shop;
    }
 */

    //获取锁和释放锁的方法(核心思路，利用redis的setnx方法来表示获取锁)
//    private boolean tryLock(String key){
//        boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
//        //自动拆箱可能会获取空指针
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }
//
//    //预先在redis缓存中存储店铺数据
//   public void saveShop2Redis(Long id,Long expireSeconds){
//        String key = CACHE_SHOP_KEY +id;
//       Shop shop = getById(id);
//       RedisData redisData = new RedisData();
//       redisData.setData(shop);
//       redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//       stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
//   }

    @Override
    public Result update(Shop shop) {
        //1、获取店铺id
        Long id = shop.getId();
        //2、id为空，返回错误
        if (id ==null) {
            return Result.fail("店铺id不能为空!");
        }
        //3、id不为空，更新数据库
        updateById(shop);
        //4、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否要根据坐标查询
        if (x == null || y == null) {
            //不需要坐标查询，按照数据库查询
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                        );
        //解析出shop_id(在content当中，包含id和经纬坐标)
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            //没有下一页，结束显示
            return Result.ok(Collections.emptyList());
        }
        //分页显示操作，已经查出该类型的所有shopId，截取from~end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //封装并返回
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
