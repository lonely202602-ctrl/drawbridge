package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.Ids;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.domain.entity.ApiToken;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端-令牌：发 SK（只显示一次）、授权分组、启停。
 * 令牌即接入方身份，任务归属与查询隔离都按 tokenId；无"应用"概念。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/tokens")
public class TokenAdminController {

    private final ApiTokenMapper tokenMapper;
    private final com.drawbridge.infrastructure.AesGcmCipher cipher;

    @Data
    public static class TokenCreateRequest {
        @NotBlank(message = "令牌名不能为空")
        private String name;
        /** 授权分组编码列表；空=全部 */
        private List<String> allowedGroups;
    }

    @Data
    public static class TokenUpdateRequest {
        private Integer status;
        private List<String> allowedGroups;
        private LocalDateTime expiresAt;
    }

    /** 发令牌：SK 只在本响应出现一次。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Validated @RequestBody TokenCreateRequest body) {
        String sk = Ids.newSk();
        ApiToken token = new ApiToken();
        token.setName(body.getName());
        token.setSkHash(Ids.sha256Hex(sk));
        token.setSkPrefix(sk.substring(0, 10));
        token.setSkEnc(cipher.encrypt(sk));
        token.setStatus(1);
        token.setAllowedGroups(body.getAllowedGroups() == null || body.getAllowedGroups().isEmpty()
                ? null : JsonUtil.toJson(body.getAllowedGroups()));
        LocalDateTime now = LocalDateTime.now();
        token.setCreatedAt(now);
        token.setUpdatedAt(now);
        tokenMapper.insert(token);
        return ApiResponse.ok(Map.of(
                "id", token.getId(),
                "sk", sk,
                "prefix", token.getSkPrefix(),
                "hint", "SK 仅此一次展示，请立即保存"));
    }

    /** 全量令牌列表（脱敏，不回传 SK）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> rows = tokenMapper.selectList(Wrappers.<ApiToken>lambdaQuery()
                        .orderByDesc(ApiToken::getId))
                .stream()
                .map(this::view)
                .toList();
        return ApiResponse.ok(rows);
    }

    /** 查看完整 SK（AES 解密）；旧版本创建的令牌只存了哈希，无法找回。 */
    @GetMapping("/{id}/sk")
    public ApiResponse<Map<String, String>> revealSk(@PathVariable Long id) {
        ApiToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "令牌不存在");
        }
        if (token.getSkEnc() == null || token.getSkEnc().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该令牌创建于旧版本（仅存哈希），SK 无法找回，请删除后重新签发");
        }
        return ApiResponse.ok(Map.of("sk", cipher.decrypt(token.getSkEnc())));
    }

    private Map<String, Object> view(ApiToken t) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", t.getId());
        row.put("name", t.getName());
        row.put("prefix", t.getSkPrefix() + "***");
        row.put("status", t.getStatus());
        // null=不限分组；[]=无授权分组（曾限定的分组被删除）。两者必须区分：
        // 都归一成空列表的话，管理端会把"零权限"显示成"全部"，排障时完全对不上
        row.put("allowedGroups", t.getAllowedGroups() == null ? null
                : JsonUtil.readTree(t.getAllowedGroups()).valueStream().map(JsonNode::asText).toList());
        row.put("expiresAt", t.getExpiresAt() == null ? "" : t.getExpiresAt());
        return row;
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody TokenUpdateRequest body) {
        ApiToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "令牌不存在");
        }
        if (body.getAllowedGroups() != null) {
            // 清空列表=恢复"全部"（null）。updateById 的默认策略会跳过 null 字段写不进库，
            // 必须用 UpdateWrapper 显式 set；写完把实体上的旧值清掉，防下面 updateById 又写回去
            tokenMapper.update(null, Wrappers.<ApiToken>lambdaUpdate()
                    .eq(ApiToken::getId, id)
                    .set(ApiToken::getAllowedGroups, body.getAllowedGroups().isEmpty()
                            ? null : JsonUtil.toJson(body.getAllowedGroups())));
            token.setAllowedGroups(null);
        }
        if (body.getStatus() != null) {
            token.setStatus(body.getStatus());
        }
        if (body.getExpiresAt() != null) {
            token.setExpiresAt(body.getExpiresAt());
        }
        token.setUpdatedAt(LocalDateTime.now());
        tokenMapper.updateById(token);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tokenMapper.deleteById(id);
        return ApiResponse.ok();
    }
}
