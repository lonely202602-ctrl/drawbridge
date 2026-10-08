package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.Ids;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.domain.entity.ApiToken;
import com.drawbridge.domain.entity.App;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import com.drawbridge.domain.mapper.AppMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端-应用与令牌：发 SK（只显示一次）、授权分组、额度、启停。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class TokenAdminController {

    private final AppMapper appMapper;
    private final ApiTokenMapper tokenMapper;
    private final ChannelConfigService configService;

    @Data
    public static class AppUpsertRequest {
        @NotBlank(message = "应用名不能为空")
        private String name;
        private String contact;
        private String remark;
    }

    @Data
    public static class TokenCreateRequest {
        @NotBlank(message = "令牌名不能为空")
        private String name;
        /** 元；空=不限额 */
        private BigDecimal quotaTotal;
        /** 授权分组编码列表；空=全部 */
        private List<String> allowedGroups;
        private LocalDateTime expiresAt;
    }

    @Data
    public static class TokenUpdateRequest {
        private Integer status;
        private BigDecimal quotaTotal;
        private List<String> allowedGroups;
        private LocalDateTime expiresAt;
    }

    // ------------------------------------------------------------- apps

    @GetMapping("/apps")
    public ApiResponse<List<App>> apps() {
        return ApiResponse.ok(appMapper.selectList(
                Wrappers.<App>lambdaQuery().orderByDesc(App::getId)));
    }

    @PostMapping("/apps")
    public ApiResponse<Long> createApp(@Validated @RequestBody AppUpsertRequest body) {
        App app = new App();
        app.setName(body.getName());
        app.setContact(body.getContact());
        app.setRemark(body.getRemark());
        app.setEnabled(1);
        LocalDateTime now = LocalDateTime.now();
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        appMapper.insert(app);
        return ApiResponse.ok(app.getId());
    }

    @PutMapping("/apps/{id}")
    public ApiResponse<Void> updateApp(@PathVariable Long id, @RequestBody AppUpsertRequest body) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        if (body.getName() != null) {
            app.setName(body.getName());
        }
        if (body.getContact() != null) {
            app.setContact(body.getContact());
        }
        if (body.getRemark() != null) {
            app.setRemark(body.getRemark());
        }
        app.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(app);
        return ApiResponse.ok();
    }

    @PostMapping("/apps/{id}/disable")
    public ApiResponse<Void> disableApp(@PathVariable Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        app.setEnabled(0);
        app.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(app);
        return ApiResponse.ok();
    }

    @PostMapping("/apps/{id}/enable")
    public ApiResponse<Void> enableApp(@PathVariable Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        app.setEnabled(1);
        app.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(app);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------- tokens

    /** 发令牌：SK 只在本响应出现一次。 */
    @PostMapping("/apps/{id}/tokens")
    public ApiResponse<Map<String, Object>> createToken(@PathVariable Long id,
                                                        @Validated @RequestBody TokenCreateRequest body) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "应用不存在");
        }
        String sk = Ids.newSk();
        ApiToken token = new ApiToken();
        token.setAppId(id);
        token.setName(body.getName());
        token.setSkHash(Ids.sha256Hex(sk));
        token.setSkPrefix(sk.substring(0, 10));
        token.setStatus(1);
        token.setQuotaTotal(body.getQuotaTotal());
        token.setQuotaUsed(BigDecimal.ZERO);
        token.setAllowedGroups(body.getAllowedGroups() == null || body.getAllowedGroups().isEmpty()
                ? null : JsonUtil.toJson(body.getAllowedGroups()));
        token.setExpiresAt(body.getExpiresAt());
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

    @GetMapping("/apps/{id}/tokens")
    public ApiResponse<List<Map<String, Object>>> tokens(@PathVariable Long id) {
        List<Map<String, Object>> rows = tokenMapper.selectList(Wrappers.<ApiToken>lambdaQuery()
                        .eq(ApiToken::getAppId, id)
                        .orderByDesc(ApiToken::getId))
                .stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "name", t.getName(),
                        "prefix", t.getSkPrefix() + "***",
                        "status", t.getStatus(),
                        "quotaTotal", t.getQuotaTotal() == null ? "不限" : t.getQuotaTotal(),
                        "quotaUsed", t.getQuotaUsed(),
                        "allowedGroups", t.getAllowedGroups() == null ? List.of()
                                : JsonUtil.readTree(t.getAllowedGroups()).valueStream()
                                .map(JsonNode::asText).toList(),
                        "expiresAt", t.getExpiresAt() == null ? "" : t.getExpiresAt()))
                .toList();
        return ApiResponse.ok(rows);
    }

    @PutMapping("/tokens/{id}")
    public ApiResponse<Void> updateToken(@PathVariable Long id, @RequestBody TokenUpdateRequest body) {
        ApiToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "令牌不存在");
        }
        if (body.getStatus() != null) {
            token.setStatus(body.getStatus());
        }
        if (body.getQuotaTotal() != null) {
            token.setQuotaTotal(body.getQuotaTotal());
        }
        if (body.getAllowedGroups() != null) {
            token.setAllowedGroups(body.getAllowedGroups().isEmpty()
                    ? null : JsonUtil.toJson(body.getAllowedGroups()));
        }
        if (body.getExpiresAt() != null) {
            token.setExpiresAt(body.getExpiresAt());
        }
        token.setUpdatedAt(LocalDateTime.now());
        tokenMapper.updateById(token);
        return ApiResponse.ok();
    }

    @DeleteMapping("/tokens/{id}")
    public ApiResponse<Void> deleteToken(@PathVariable Long id) {
        tokenMapper.deleteById(id);
        return ApiResponse.ok();
    }
}
