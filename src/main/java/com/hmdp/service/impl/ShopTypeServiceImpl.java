package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.catalina.LifecycleState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
  @Autowired
  private StringRedisTemplate stringRedisTemplate;
  @Override
  public Result queryList() {
    String key = RedisConstants.CACHE_TYPE_LIST;
    //从redis查询缓存类型
    String typeJson = stringRedisTemplate.opsForValue().get(key);
    if (!StrUtil.isBlank(typeJson)) {
      List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
      return Result.ok(shopTypeList);
    }
    //为空，查询
    List<ShopType> shopTypeList = query().orderByAsc("sort").list();
    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));
    return Result.ok(shopTypeList);
  }
}
