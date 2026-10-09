package com.drawbridge.openapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.domain.entity.ApiToken;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 业务方令牌鉴权：SK → SHA-256 → 查令牌 → 查应用。
 * 与 octopus 的 AK 策略一致：直接查库保证吊销实时生效（索引点查，虚拟线程下开销可忽略）。
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final ApiTokenMapper tokenMapper;

    public AuthContext resolve(String sk) {
        String hash = com.drawbridge.common.Ids.sha256Hex(sk);
        ApiToken token = tokenMapper.selectOne(
                Wrappers.<ApiToken>lambdaQuery().eq(ApiToken::getSkHash, hash));
        if (token == null || token.getStatus() == null || token.getStatus() != 1) {
            return null;
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        Set<String> allowed = null;
        if (token.getAllowedGroups() != null && !token.getAllowedGroups().isBlank()) {
            allowed = com.drawbridge.common.JsonUtil.readTree(token.getAllowedGroups())
                    .valueStream().filter(com.fasterxml.jackson.databind.JsonNode::isTextual)
                    .map(com.fasterxml.jackson.databind.JsonNode::asText)
                    .collect(java.util.stream.Collectors.toSet());
        }
        return new AuthContext(token, allowed);
    }

    /** 令牌即接入方身份：任务归属与查询隔离都按 tokenId。 */
    public record AuthContext(ApiToken token, Set<String> allowedGroups) {

        public boolean canUse(String groupCode) {
            return allowedGroups == null || allowedGroups.contains(groupCode);
        }
    }
}
