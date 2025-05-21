package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
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
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
  @Resource
  private ISeckillVoucherService seckillVoucherService;
  @Resource
  private RedisIdWorker redisIdWorker;
  @Resource
  private StringRedisTemplate stringRedisTemplate;
  @Resource
  private RedissonClient redissonClient;
  private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
  static {
    SECKILL_SCRIPT = new DefaultRedisScript<>();
    SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    SECKILL_SCRIPT.setResultType(Long.class);
  }

  private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);
  private static final ExecutorService SECKILL_EXECUTOR = Executors.newSingleThreadExecutor();
  @PostConstruct
  private void init(){
    SECKILL_EXECUTOR.submit(new VoucherOrderHandler());
  }

  private class VoucherOrderHandler implements Runnable {
    String queueName = "stream.orders";
    @Override
    public void run() {
      while (true) {
        try {
          //获取消息队列的订单信息
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
              StreamOffset.create(queueName, ReadOffset.lastConsumed())
          );

          //判断是否成功
          if(list==null || list.isEmpty()){
            //失败，没有消息，下一次循环
            continue;
          }
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> values = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
          //有消息，可以下单
          handleVoucherOrder(voucherOrder);
          //ACK确认
          stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
        } catch (Exception e){
          log.info("'处理订单异常",e);
          handlePendingList();
        }
      }
    }

    private void handlePendingList() {
      while (true) {
        try {
          //获取消息队列的订单信息
          List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
              Consumer.from("g1", "c1"),
              StreamReadOptions.empty().count(1),
              StreamOffset.create(queueName, ReadOffset.from("0"))
          );

          //判断是否成功
          if(list==null || list.isEmpty()){
            //失败，没有消息，跳出循环
            break;
          }
          MapRecord<String, Object, Object> record = list.get(0);
          Map<Object, Object> values = record.getValue();
          VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
          //有消息，可以下单
          handleVoucherOrder(voucherOrder);
          //ACK确认
          stringRedisTemplate.opsForStream().acknowledge(queueName,"g1", record.getId());
        } catch (Exception e){
          log.info("处理pending-list异常",e);
        }
      }
    }
//    @Override
//    public void run() {
//      while (true) {
//        try {
//          // 1.获取队列中订单信息
//          VoucherOrder voucherOrder = orderTask.take();
//          // 2.创建队列
//          handleVoucherOrder(voucherOrder);
//        } catch (Exception e) {
//          log.error("处理订单异常",e);
//        }
//      }
//    }
  }



  private void handleVoucherOrder(VoucherOrder voucherOrder) {
    //获取用户
    Long userId = voucherOrder.getUserId();

     //创建锁对象获取锁
    //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
    RLock lock = redissonClient.getLock("lock:order:" + userId);
    //boolean isLock = lock.tryLock(1200);
    boolean isLock = lock.tryLock();
    if(!isLock){
      // 获取锁失败，返回错误或重试
      log.error("不允许重复下单");
      return;
    }
    //用代理对象调用
    try {
      IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
      proxy.createVoucherOrder(voucherOrder);
    }finally {
      lock.unlock();
    }
  }

  private IVoucherService proxy;
  @Override
  public Result seckillVoucher(Long voucherId) {
    //获取用户
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    //执行lua脚本
    Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        voucherId.toString(), userId.toString(), String.valueOf(orderId)
    );
    //判断结果是否为0
    int r = result.intValue();
    if (r!=0){
      //代表没有资格
      return Result.fail(r==1?"库存不足":"不能重复下单");
    }
    //获取代理对象
    proxy = (IVoucherService)AopContext.currentProxy();
    return Result.ok(orderId);
  }
//  @Override
//  public Result seckillVoucher(Long voucherId) {
//    //获取用户
//    Long userId = UserHolder.getUser().getId();
//    //执行lua脚本
//    Long result = stringRedisTemplate.execute(
//        SECKILL_SCRIPT,
//        Collections.emptyList(),
//        voucherId.toString(), userId.toString()
//    );
//    //判断结果是否为0
//    int r = result.intValue();
//    if (r!=0){
//      //代表没有资格
//      return Result.fail(r==1?"库存不足":"不能重复下单");
//    }
//    //有购买资格，把下单信息保存到阻塞队列
//    VoucherOrder voucherOrder = new VoucherOrder();
//    long orderId = redisIdWorker.nextId("order");
//    voucherOrder.setId(orderId);
//    voucherOrder.setUserId(userId);
//    voucherOrder.setVoucherId(voucherId);
//    //保存阻塞队列
//    orderTask.add(voucherOrder);
//    //获取代理对象
//    proxy = (IVoucherService)AopContext.currentProxy();
//    return Result.ok(orderId);
//  }

//  public Result seckillVoucher(Long voucherId) {
//    // 查询优惠券
//    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//    //判断是否开始和结束
//    if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//      return Result.fail("秒杀尚未开始！");
//    }
//    if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//      return Result.fail("秒杀已经结束！");
//    }
//    //判断库存是否充足
//    if(voucher.getStock()<1){
//      return Result.fail("库存不足！");
//    }
//    // 给方法调用加锁确保事务在锁释放前提交
//    Long userId = UserHolder.getUser().getId();
/// /    synchronized (userId.toString().intern()) {
//
//    // 创建锁对象获取锁
//    //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
//    RLock lock = redissonClient.getLock("lock:order:" + userId);
//    //boolean isLock = lock.tryLock(1200);
//    boolean isLock = lock.tryLock();
//    if(!isLock){
//      // 获取锁失败，返回错误或重试
//      return Result.fail("一个人不允许重复下单");
//    }
//    //用代理对象调用
//    try {
//      IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//      return proxy.createVoucherOrder(voucherId);
//    }finally {
//      lock.unlock();
//    }
//  }

  @Transactional
  public void createVoucherOrder(VoucherOrder voucherOrder) {
    // 创建订单
    Long userId = voucherOrder.getUserId();

    int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
    if (count > 0) {
      log.error("用户已经购买过一次");
      return;
    }

    //扣减库存并创建订单
    boolean success = seckillVoucherService
        .update()
        .setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId())
        .gt("stock", 0)
        //.eq("stock",voucher.getStock())
        .update();
    if (!success) {
      log.info("库存不足!");
      return;
    }

    save(voucherOrder);
  }
}
