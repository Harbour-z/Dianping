package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  public Result sendCode(String phone, HttpSession session) {
    // 校验手机号
    if(RegexUtils.isPhoneInvalid(phone)){
      //不符合返回错误信息
      return Result.fail("手机号格式错误111！");
    }
    //符合生成验证码
    String code = RandomUtil.randomNumbers(6);

    //保存验证码到redis
    stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

    //发送验证码
    log.info("发送短信验证码成功：{}",code);

    //返回ok
    return Result.ok();
  }

  @Override
  public Result login(LoginFormDTO loginForm, HttpSession session) {
    // 校验手机号和验证码
    String phone = loginForm.getPhone();
    if(RegexUtils.isPhoneInvalid(phone)){
      //不符合返回错误信息
      return Result.fail("手机号格式错误！");
    }
    // 从redis获取验证码并校验
    String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
    String code = loginForm.getCode();
    if(cacheCode==null || !cacheCode.equals(code)){
      // 不一致报错
      return Result.fail("验证码错误");
    }
    //一致，根据手机号查用户
    User user = query().eq("phone", phone).one();
    //判断用户是否存在
    if(user==null){
      //不存在则创建新用户并保存
      user=createUserWithPhone(phone);
    }
    //保存用户信息到redis中
    //随机生成token，作为登录令牌
    String token = UUID.randomUUID().toString(true);
    //将User对象转化为Hash存储
    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
    //Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
    //Map<String, Object> userMap = new HashMap<>();
    Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
        CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()));
//    userMap.put("id", String.valueOf(userDTO.getId()));
//    userMap.put("nickname", userDTO.getNickName());
//    if(!userDTO.getIcon().equals(null)) {
//      userMap.put("icon", userDTO.getIcon());
//    }
    //存储
    String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
    //设置有效期
    stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
    //返回token给前端
    return Result.ok(token);
  }

  private User createUserWithPhone(String phone){
    User user = new User();
    user.setPhone(phone);
    user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
    //保存用户
    save(user);
    return user;
  }
}
