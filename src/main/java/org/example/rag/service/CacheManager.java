package org.example.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 缓存管理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheManager {
    private final RedisTemplate<String, Object> redisTemplate;
    /**
     * 默认设置缓存过期时间，单位：小时
     */
    private static final Duration DEFAULT_EXPIRE = Duration.ofHours(1);
    /**
     * 表示空值的占位符, 防止缓存穿透
     */
    private static final String NULL_VALUE = "NULL_PLACEHOLDER";

    /**
     * 获取缓存值
     * @param key 缓存键
     * @return 缓存值，如果不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key){
        try{
            Object value = redisTemplate.opsForValue().get(key);
            if (NULL_VALUE.equals(value)) {
                return null;
            }
            if(value != null){
                log.debug("缓存命中，key: {}", key);
                return (T) value;
            }
            log.debug("缓存未命中，key: {}", key);
            return null;
        }catch (Exception e){
            log.error("获取缓存失败，key: {}", key, e);
            return null;
        }
    }
    /**
     * 设置缓存值，使用默认过期时间
     * @param key 缓存键
     * @param value 缓存值
     */
    public void put(String key, Object value, Duration expire){
        try{
            if(value == null){
                value=NULL_VALUE;
            }
            redisTemplate.opsForValue().set(key, value, expire);
            log.debug("缓存设置成功，key: {}", key);
        }catch (Exception e){
            log.error("设置缓存失败，key: {}", key, e);
        }
    }
    public void put(String key, Object value){
        put(key, value, DEFAULT_EXPIRE);
    }
    /**
     * 删除缓存
     * @param key 缓存键
     * @return 删除是否成功
     */
    public boolean delete(String key){
        try{
            Boolean result = redisTemplate.delete(key);
            log.debug("缓存删除成功，key: {}", key);
            return result;
        }catch (Exception e){
            log.error("删除缓存失败，key: {}", key, e);
            return false;
        }
    }
    /**
     * 判断缓存是否存在
     * @param key 缓存键
     */
    public boolean exists(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("检查缓存存在性失败，key: {}", key, e);
            return false;
        }
    }
    // ========== 高级操作 ==========

    /**
     * 获取缓存，如果不存在则加载
     * 这是缓存使用的最常用方法，实现了"缓存先查，不存在再加载"的模式。
     *
     * @param key 缓存键
     * @param loader 数据加载器（当缓存不存在时调用）
     * @param expire 过期时间
     * @param <T> 返回值类型
     * @return 缓存值或加载的值
     */
    public <T> T getOrLoad(String key, Supplier<T> loader, Duration expire) {
        T cachedValue = get(key);
        if(cachedValue != null){
            return cachedValue;
        }
        //如果缓存不存在，加锁防止并发加载
        //todo: 这里可以使用分布式锁优化

        // 3. 双重检查（其他线程可能已经加载了）
        cachedValue = get(key);
        if (cachedValue != null) {
            return cachedValue;
        }

        // 4. 执行加载逻辑
        try {
            log.debug("缓存加载，key={}", key);
            T loadedValue = loader.get();

            // 5. 写入缓存
            put(key, loadedValue, expire);

            return loadedValue;

        } catch (Exception e) {
            log.error("缓存加载失败，key={}", key, e);
            throw new RuntimeException("缓存加载失败", e);
        }
    }
    public <T> T getOrLoad(String key, Supplier<T> loader) {
        return getOrLoad(key, loader, DEFAULT_EXPIRE);
    }
    // ========== 批量操作 ==========
    /**
     * 批量删除缓存
     * @param keys 缓存键集合
     * @return 删除的数量
     */
    public long deleteBatch(String... keys) {
        try {
            Long count = redisTemplate.delete(List.of(keys));
            log.debug("批量删除缓存，count={}", count);
            return count;

        } catch (Exception e) {
            log.error("批量删除缓存失败", e);
            return 0;
        }
    }
    public String generateKey(String module,String identifier,String... params){
        StringBuilder key =new StringBuilder(module).append(":").append(identifier);
        for(String param:params){
            key.append(":").append(param);
        }
        return key.toString();
    }
}
