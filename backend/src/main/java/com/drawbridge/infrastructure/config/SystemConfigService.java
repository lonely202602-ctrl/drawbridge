package com.drawbridge.infrastructure.config;

import com.drawbridge.domain.entity.SystemConfig;
import com.drawbridge.domain.mapper.SystemConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运营参数中心：启动时全量加载 + 缺失键自动种子默认值；
 * 设置页修改后 upsert 并刷新内存缓存，转发链路读缓存不查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigMapper mapper;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        refresh();
        seedDefaults();
    }

    public synchronized void refresh() {
        cache.clear();
        for (SystemConfig row : mapper.selectList(null)) {
            cache.put(row.getConfigKey(), row.getConfigValue());
        }
    }

    /** 首启把缺失键落库（默认值以代码为准），已存在的以 DB 为准。 */
    private void seedDefaults() {
        LocalDateTime now = LocalDateTime.now();
        for (ConfigKeys ck : ConfigKeys.values()) {
            if (cache.containsKey(ck.getKey())) {
                continue;
            }
            SystemConfig row = new SystemConfig();
            row.setConfigKey(ck.getKey());
            row.setConfigValue(ck.getDefaultValue());
            row.setDescription(ck.getDescription());
            row.setUpdatedAt(now);
            try {
                mapper.insert(row);
                cache.put(ck.getKey(), ck.getDefaultValue());
            } catch (Exception e) {
                log.warn("配置默认值写入跳过（可能已存在并发写入）: key={}", ck.getKey());
            }
        }
        log.info("运营参数已加载: {} 项", cache.size());
    }

    public String get(String key, String def) {
        return cache.getOrDefault(key, def);
    }

    public int getInt(ConfigKeys ck) {
        return Integer.parseInt(get(ck.getKey(), ck.getDefaultValue()));
    }

    public long getLong(ConfigKeys ck) {
        return Long.parseLong(get(ck.getKey(), ck.getDefaultValue()));
    }

    public double getDouble(ConfigKeys ck) {
        return Double.parseDouble(get(ck.getKey(), ck.getDefaultValue()));
    }

    /** 设置页保存：逐键 upsert 后刷新缓存。 */
    public synchronized void updateAll(Map<String, String> values) {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> e : values.entrySet()) {
            SystemConfig existing = mapper.selectById(e.getKey());
            if (existing == null) {
                SystemConfig row = new SystemConfig();
                row.setConfigKey(e.getKey());
                row.setConfigValue(e.getValue());
                row.setDescription(descriptionOf(e.getKey()));
                row.setUpdatedAt(now);
                mapper.insert(row);
            } else {
                existing.setConfigValue(e.getValue());
                existing.setUpdatedAt(now);
                mapper.updateById(existing);
            }
        }
        refresh();
    }

    public Map<String, String> all() {
        Map<String, String> sorted = new TreeMap<>();
        for (ConfigKeys ck : ConfigKeys.values()) {
            sorted.put(ck.getKey(), get(ck.getKey(), ck.getDefaultValue()));
        }
        return sorted;
    }

    private String descriptionOf(String key) {
        for (ConfigKeys ck : ConfigKeys.values()) {
            if (ck.getKey().equals(key)) {
                return ck.getDescription();
            }
        }
        return null;
    }
}
