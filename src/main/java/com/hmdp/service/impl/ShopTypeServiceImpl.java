package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有商铺类型
     * @return
     */
    @Override
    public List<ShopType> queryTypeList() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY + "all";
        // 1. 查询redis中是否有商铺类型缓存
        List<String> shopTypeList = stringRedisTemplate.opsForList()
                .range(key, 0, -1);
        // 2. 如果有，则直接返回
        if (shopTypeList != null && shopTypeList.size() > 0) {
//            log.info("redis中有，我在redis中获取的");
            // shopTypeList转list<ShopType>并返回
            return shopTypeList.stream()
                    .map(shopType -> JSONUtil.toBean(shopType, ShopType.class))
                    .toList();
        }

        // 3. 如果没有，查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
//        log.info("redis中没有，我在数据库中查询的");
        // 4. 写入redis缓存
        if (list != null && !list.isEmpty()) {
            List<String> jsonList = list.stream()
                    .map(JSONUtil::toJsonStr)
                    .toList();
            stringRedisTemplate.opsForList().leftPushAll(key, jsonList);
            // 设置过期时间
            stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TYPE_TTL, java.util.concurrent.TimeUnit.MINUTES);
        }

        // 5. 返回
        return list;
    }
}
