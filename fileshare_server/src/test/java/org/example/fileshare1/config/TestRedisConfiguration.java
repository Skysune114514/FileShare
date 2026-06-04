package org.example.fileshare1.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试 profile 下用内存 Map 模拟 Redis，无需本机启动 redis-server。
 * <p>
 * 覆盖 {@link StringRedisTemplate#opsForValue()} 的 get / set / setIfAbsent 与 {@link #getExpire}。
 */
@TestConfiguration
@Profile("test")
public class TestRedisConfiguration {

  private final Map<String, String> store = new ConcurrentHashMap<>();
  private final Map<String, Long> expireAtMs = new ConcurrentHashMap<>();

  /** 每个测试类开始前可调用，避免键残留。 */
  public void clear() {
    store.clear();
    expireAtMs.clear();
  }

  @Bean
  @Primary
  public StringRedisTemplate stringRedisTemplate() {
    StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(template.opsForValue()).thenReturn(ops);

    when(ops.get(anyString())).thenAnswer(inv -> {
      String key = inv.getArgument(0);
      evictIfExpired(key);
      return store.get(key);
    });

    doAnswer(inv -> {
      String key = inv.getArgument(0);
      String value = inv.getArgument(1);
      store.put(key, value);
      expireAtMs.remove(key);
      return null;
    }).when(ops).set(anyString(), anyString());

    doAnswer(inv -> {
      String key = inv.getArgument(0);
      String value = inv.getArgument(1);
      Duration ttl = inv.getArgument(2);
      store.put(key, value);
      if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
        expireAtMs.put(key, System.currentTimeMillis() + ttl.toMillis());
      } else {
        expireAtMs.remove(key);
      }
      return null;
    }).when(ops).set(anyString(), anyString(), any(Duration.class));

    when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
      String key = inv.getArgument(0);
      evictIfExpired(key);
      if (store.containsKey(key)) {
        return false;
      }
      String value = inv.getArgument(1);
      Duration ttl = inv.getArgument(2);
      store.put(key, value);
      if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
        expireAtMs.put(key, System.currentTimeMillis() + ttl.toMillis());
      }
      return true;
    });

    when(template.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(inv -> {
      String key = inv.getArgument(0);
      TimeUnit unit = inv.getArgument(1);
      Long at = expireAtMs.get(key);
      if (at == null) {
        return store.containsKey(key) ? -1L : -2L;
      }
      long diffMs = at - System.currentTimeMillis();
      if (diffMs <= 0) {
        store.remove(key);
        expireAtMs.remove(key);
        return -2L;
      }
      return unit.convert(diffMs, TimeUnit.MILLISECONDS);
    });

    return template;
  }

  private void evictIfExpired(String key) {
    Long at = expireAtMs.get(key);
    if (at != null && at <= System.currentTimeMillis()) {
      store.remove(key);
      expireAtMs.remove(key);
    }
  }
}
