package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //静态常量要在静态代码块中做初始化
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //指定脚本(设置脚本的位置)
        //ClassPathResource就是Resource目录
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //配置返回值
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明没有消息，继续循环
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果有消息，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认   XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handelPendingList();
                }
            }
        }

        private void handelPendingList() {
            while(true){
                try {
                    //获取Pending-List中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //获取失败，说明Pending-List里面没有消息，结束循环
                        break;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果有消息，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认   XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理Pending-List订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }

    /*//定义阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1、创建锁对象
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //2、获取锁对象
        boolean isLock = lock.tryLock();
        //3、判断是否获取成功
        if (!isLock) {
            //获取锁不成功，返回只能一单提示
            log.error("不允许重复下单");
            return;
        }
        //4、成功，获取代理对象（因为有事务），然后创建订单
        try {
            //可能出现异常，不管有没有出现异常，最终都要释放锁
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //5、释放锁
            lock.unlock();
        }
    }

    public VoucherOrderServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private IVoucherOrderService proxy;
    //优惠卷秒杀业务
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //判断结果不为0，没有购买资格
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //判断结果不为0，没有购买资格
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //为0，有购买资格，把下单的信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //创建阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }*/


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠卷信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //秒杀为开始，返回未开始提示
            return Result.fail("秒杀尚未开始!");
        }
        //判断秒杀是否结束
        if (LocalDateTime.now().isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束!");
        }
        //3、秒杀开始，判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足!");
        }

        Long userId = UserHolder.getUser().getId();

        //1、创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //2、获取锁对象
        boolean isLock = lock.tryLock();
        //3、判断是否获取成功
        if (!isLock) {
            //获取锁不成功，返回只能一单提示
            return Result.fail("不允许重复下单!");
        }
        //4、成功，获取代理对象（因为有事务），然后创建订单
        try {
            //可能出现异常，不管有没有出现异常，最终都要释放锁
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //5、释放锁
            lock.unlock();
        }
    }*/


    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //添加一人一单逻辑
        //获取用户id+根据用户id统计数据库表中对应优惠卷的数量、数量大于0说明改用户买过了
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }
        //4、库存充足，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)         //防止超卖问题
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}
