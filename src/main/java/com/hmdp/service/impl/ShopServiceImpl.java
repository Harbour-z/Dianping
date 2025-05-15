package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  @Override
  public Result queryById(Long id) {
    // 缓存穿透
    // Shop shop = queryWithPassThrough(id);

    // 互斥锁解决缓存击穿
//    Shop shop = queryWithMutex(id);
    //逻辑过期
    Shop shop = queryWithLogicalExpire(id);

    if (shop == null) {
      return Result.fail("店铺不存在！");
    }
    //7 返回
    return Result.ok(shop);
  }

  //创建线程池
  private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
  // 逻辑过期方案
  public Shop queryWithLogicalExpire(Long id){
    String key = CACHE_SHOP_KEY + id;
    // 1 从redis查询商铺缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    // 2 判断是否存在
    if (StrUtil.isBlank(shopJson)) {
      // 3 存在直接返回
      return null;
    }
    //命中，json反序列化为对象，判断过期时间
    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    JSONObject data = (JSONObject) redisData.getData();
    Shop shop = JSONUtil.toBean(data, Shop.class);
    LocalDateTime expireTime = redisData.getExpireTime();
    if(expireTime.isAfter(LocalDateTime.now())){
      //未过期直接返回店铺信息
      return shop;
    }
    //已过期需要缓存重建
    //获取互斥锁，判断是否成功；成功开启独立线程；失败则返回过期商铺信息
    String lockKey = LOCK_SHOP_KEY + id;
    boolean isLock = tryLock(lockKey);
    if(isLock){
      // TODO 成功，开启独立线程，实现缓存重建
      CACHE_REBUILD_EXECUTOR.submit(()->{
        try {
          this.saveShop2Redis(id, 20L);
        }catch (Exception e){
          throw new RuntimeException(e);
        }finally {
          //释放锁
          unlock(lockKey);
        }



      });
    }

    return shop;
  }

  public void saveShop2Redis(Long id, Long expireSeconds){
    Shop shop = getById(id);
    //封装逻辑过期时间
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
  }

  //缓存击穿的解决方案
  public Shop queryWithMutex(Long id){
    String key = CACHE_SHOP_KEY + id;
    // 1 从redis查询商铺缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    // 2 判断是否存在
    if (!StrUtil.isBlank(shopJson)) {
      // 3 存在直接返回
      return JSONUtil.toBean(shopJson, Shop.class);
    }
    //判断命中是否为空
    if (shopJson!=null) {
      return null;
    }
    // 4 实现缓存重建
    // 获取互斥锁
    String lockKey = "lock:shop:" + id;
    Shop shop = null;
    try {

      boolean isLock = tryLock(lockKey);
      // 判断是否获取成功
      if(!isLock){
        //失败则休眠并重试
        Thread.sleep(50);
        return queryWithPassThrough(id);
      }

      // 成功，根据id查询数据库
      shop = getById(id);
      // 5 不存在，返回错误
      if(shop == null){
        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
      }
      // 6 存在，写入redis
      stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
      log.info("存在，写入redis");
    }catch (InterruptedException e){
      throw new RuntimeException(e);
    }finally {
      //释放互斥锁
      unlock(lockKey);
    }

    //7 返回
    return shop;
  }

  //缓存穿透的解决方案
  public Shop queryWithPassThrough(Long id){
    String key = CACHE_SHOP_KEY + id;
    // 1 从redis查询商铺缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    // 2 判断是否存在
    if (!StrUtil.isBlank(shopJson)) {
      // 3 存在直接返回
      return JSONUtil.toBean(shopJson, Shop.class);
    }
    //判断命中是否为空
    if (shopJson!=null) {
      return null;
    }

    // 4 不存在根据id查询数据库
    Shop shop = getById(id);
    // 5 不存在，返回错误
    if(shop == null){
      stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
      return null;
    }
    // 6 存在，写入redis
    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    log.info("存在，写入redis");
    //7 返回
    return shop;
  }

  private boolean tryLock(String key){
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
  }

  private void unlock(String key){
    stringRedisTemplate.delete(key);
  }

  @Override
  @Transactional
  public Result update(Shop shop) {
    Long id = shop.getId();
    if (id==null) {
      return Result.fail("店铺id不能为空！");
    }
    // 更新数据库
    updateById(shop);
    //删除缓存
    stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
    return null;
  }
}
