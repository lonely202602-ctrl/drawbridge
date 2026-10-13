package com.drawbridge.health;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.UpstreamAccount;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.UpstreamAccountMapper;
import com.drawbridge.infrastructure.AesGcmCipher;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上游账号登记簿：账号键计算、upstream_account 行惰性创建与容量读写。
 * 账号 = 同一 base_url+apiKey 的上游身份；同键渠道共享一个并发池（槽位按账号计）。
 *
 * 归一化规则（保证"同一家上游不同写法"落同一个键）：
 *   整串转小写（含路径——上游路径约定为 /v1 之类小写，对键的一致性优先于路径大小写语义）
 *   + 去结尾斜杠；端口与路径原样保留参与哈希。
 * 初始容量 = 同账号各渠道 max_concurrency 的最小值（保守取管理员配过的最严限制），无则默认 5。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRegistry {

    public static final int DEFAULT_CAPACITY = 5;

    private final ChannelMapper channelMapper;
    private final UpstreamAccountMapper accountMapper;
    private final AesGcmCipher cipher;
    private final ChannelConfigService configService;

    /** 启动补算：存量渠道的 account_key 与缺失的账号行（密文在 Java 侧解密后计算，SQL 做不了）。 */
    @PostConstruct
    void migrateExistingChannels() {
        int fixed = 0;
        for (Channel c : channelMapper.selectList(null)) {
            try {
                boolean changed = false;
                if (c.getAccountKey() == null || c.getAccountKey().isBlank()) {
                    if (c.getApiKeyEnc() != null && !c.getApiKeyEnc().isBlank()) {
                        c.setAccountKey(computeKey(c.getBaseUrl(), cipher.decrypt(c.getApiKeyEnc())));
                        changed = true;
                    }
                }
                if (changed) {
                    channelMapper.updateById(c);
                    fixed++;
                }
                if (c.getAccountKey() != null && !c.getAccountKey().isBlank()) {
                    ensureAccount(c.getAccountKey());
                }
            } catch (Exception e) {
                log.error("渠道账号键补算失败（该渠道将无法参与派发，请在管理端重新保存）: channelId={}",
                        c.getId(), e);
            }
        }
        if (fixed > 0) {
            // 补算写库晚于配置快照首载：不刷新的话快照里渠道没有账号键，派发器会一直派不出去
            configService.refresh();
            log.info("存量渠道账号键补算完成: {} 个，配置快照已刷新", fixed);
        }
    }

    /** 归一化 + 哈希。base_url 与明文 key；任一为空返回 null（无 Key 的渠道不参与派发）。 */
    public String computeKey(String baseUrl, String plainApiKey) {
        if (baseUrl == null || baseUrl.isBlank() || plainApiKey == null || plainApiKey.isBlank()) {
            return null;
        }
        String normalized = baseUrl.trim().toLowerCase().replaceAll("/+$", "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((normalized + "|" + plainApiKey.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 确保账号行存在（幂等）。缺行时初始容量取同账号渠道 max_concurrency 的最小值（保守）。
     * 并发撞唯一键按 DuplicateKey 捕获后重查。
     */
    public void ensureAccount(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return;
        }
        if (accountMapper.selectById(accountKey) != null) {
            return;
        }
        try {
            UpstreamAccount row = new UpstreamAccount();
            row.setAccountKey(accountKey);
            row.setCapacity(defaultCapacityFor(accountKey));
            LocalDateTime now = LocalDateTime.now();
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            accountMapper.insert(row);
            log.info("上游账号行已创建: accountKey={}, capacity={}", shortKey(accountKey), row.getCapacity());
        } catch (DuplicateKeyException e) {
            // 并发惰性创建撞唯一键：另一线程已建好，直接复用
            log.debug("账号行已被并发创建: accountKey={}", shortKey(accountKey));
        }
    }

    /** 账号行是否已存在（容量写入口径用：已有池子不得被新建/换绑渠道悄悄改写）。 */
    public boolean accountExists(String accountKey) {
        return accountKey != null && !accountKey.isBlank()
                && accountMapper.selectById(accountKey) != null;
    }

    /** 容量读取；缺行按 0 处理（暂停派发，监控页可见，由 ensureAccount/保存渠道补齐）。 */
    public int capacityOf(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return 0;
        }
        UpstreamAccount row = accountMapper.selectById(accountKey);
        return row == null || row.getCapacity() == null ? 0 : row.getCapacity();
    }

    /** 管理端显式设容量（保存渠道/监控页调参）。 */
    public void setCapacity(String accountKey, int capacity) {
        ensureAccount(accountKey);
        int capped = Math.max(0, Math.min(capacity, 10_000));
        accountMapper.update(null, Wrappers.<UpstreamAccount>lambdaUpdate()
                .eq(UpstreamAccount::getAccountKey, accountKey)
                .set(UpstreamAccount::getCapacity, capped)
                .set(UpstreamAccount::getUpdatedAt, LocalDateTime.now()));
    }

    private int defaultCapacityFor(String accountKey) {
        List<Channel> siblings = channelMapper.selectList(Wrappers.<Channel>lambdaQuery()
                .eq(Channel::getAccountKey, accountKey));
        return siblings.stream()
                .map(Channel::getMaxConcurrency)
                .filter(v -> v != null && v > 0)
                .min(Integer::compareTo)
                .orElse(DEFAULT_CAPACITY);
    }

    /** 日志/展示用短键（前 8 位），避免整串刷屏。 */
    public static String shortKey(String accountKey) {
        return accountKey == null ? "-" : accountKey.substring(0, Math.min(8, accountKey.length()));
    }
}
