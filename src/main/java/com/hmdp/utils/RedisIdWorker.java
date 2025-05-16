package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
  // 开始时间戳
  private static  final long BEGIN_TIMESTAMP = 1640995200L;
  private static  final long COUNT_BITS = 32;
  @Resource
  private StringRedisTemplate stringRedisTemplate;

  public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
    this.stringRedisTemplate = stringRedisTemplate;
  }

  public long nextId(String keyPrefix){
    //这个前缀是业务前缀
    //生成时间戳和序列号
    LocalDateTime now = LocalDateTime.now();
    long nowsecond = now.toEpochSecond(ZoneOffset.UTC);
    long timestamp = nowsecond - BEGIN_TIMESTAMP;

    String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

    long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

    return timestamp << COUNT_BITS | count;

  }

  public static void main(String[] args) {
    LocalDateTime localDateTime = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
    System.out.println(localDateTime.toEpochSecond(ZoneOffset.UTC));
  }

}
