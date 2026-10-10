package com.drawbridge.openapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.common.Ids;
import com.drawbridge.common.JsonUtil;
import com.drawbridge.common.PageResult;
import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.mapper.GenerateTaskMapper;
import com.drawbridge.domain.mapper.ModelGroupMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.infrastructure.config.ConfigKeys;
import com.drawbridge.infrastructure.config.SystemConfigService;
import com.drawbridge.openapi.dto.OpenApiDtos;
import com.drawbridge.task.SubmitExecutor;
import com.drawbridge.task.TaskStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务方 API：提交生图任务 / 查询任务 / 任务列表 / 模型表。
 * drawbridge 是纯网关：不计价不扣费，提交只做 鉴权 + 模型检查 + 限流。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
public class OpenApiController {

    private final ChannelConfigService configService;
    private final SystemConfigService config;
    private final GenerateTaskMapper taskMapper;
    private final ModelGroupMapper groupMapper;
    private final SubmitExecutor submitExecutor;
    private final TokenRateLimiter rateLimiter;

    @PostMapping("/images/tasks")
    public ApiResponse<OpenApiDtos.TaskSummary> submit(
            @RequestAttribute(OpenApiAuthInterceptor.ATTR_AUTH) TokenService.AuthContext ctx,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OpenApiDtos.ImageSubmitRequest body) {

        String groupCode = body.model() == null ? "" : body.model().trim();
        ModelGroup group = configService.groupByCode(groupCode);
        if (group == null || group.getEnabled() == null || group.getEnabled() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模型不存在或已停用: " + groupCode);
        }
        if (!ctx.canUse(groupCode)) {
            throw new BizException(ErrorCode.FORBIDDEN, "令牌未授权访问模型: " + groupCode);
        }
        if (!rateLimiter.allowSubmit(ctx.token().getId())) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        validatePayload(body);

        int n = body.n() == null ? 1 : body.n();
        // 幂等：同应用同 key 直接返回已有任务
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            GenerateTask existing = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                    .eq(GenerateTask::getTokenId, ctx.token().getId())
                    .eq(GenerateTask::getClientRequestId, idempotencyKey)
                    .last("limit 1"));
            if (existing != null) {
                return ApiResponse.ok(OpenApiDtos.TaskSummary.of(existing));
            }
        }

        GenerateTask task = new GenerateTask();
        task.setTaskId(Ids.newTaskId());
        
        task.setTokenId(ctx.token().getId());
        task.setGroupCode(groupCode);
        task.setStatus(TaskStatus.SUBMITTED.name());
        task.setImageCount(n);
        task.setBizRequest(JsonUtil.truncateBytes(JsonUtil.toJson(body),
                config.getInt(ConfigKeys.RETENTION_BODY_MAX_BYTES)));
        task.setClientRequestId(idempotencyKey);
        LocalDateTime now = LocalDateTime.now();
        task.setNextPollTime(now);
        task.setExpiresAt(now.plusNanos(config.getLong(ConfigKeys.TASK_MAX_LIFETIME_MS) * 1_000_000));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发幂等竞争：退回已存在的任务
            GenerateTask existing = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                    .eq(GenerateTask::getTokenId, ctx.token().getId())
                    .eq(GenerateTask::getClientRequestId, idempotencyKey)
                    .last("limit 1"));
            return ApiResponse.ok(OpenApiDtos.TaskSummary.of(existing));
        }

        // 直派流水线（虚拟线程后台执行：路由 → 上游提交 → 同步等待/轮询都发生在后台），
        // 请求线程不等上游，立即返回任务号，业务方轮询查询接口跟进。
        submitExecutor.kick(task.getId());
        return ApiResponse.ok(OpenApiDtos.TaskSummary.of(task));
    }

    @GetMapping("/images/tasks/{taskId}")
    public ApiResponse<OpenApiDtos.TaskSummary> query(
            @RequestAttribute(OpenApiAuthInterceptor.ATTR_AUTH) TokenService.AuthContext ctx,
            @PathVariable String taskId) {
        requireQueryQuota(ctx);
        GenerateTask task = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getTaskId, taskId)
                .eq(GenerateTask::getTokenId, ctx.token().getId()));
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return ApiResponse.ok(OpenApiDtos.TaskSummary.of(task));
    }

    @GetMapping("/images/tasks")
    public ApiResponse<PageResult<OpenApiDtos.TaskSummary>> list(
            @RequestAttribute(OpenApiAuthInterceptor.ATTR_AUTH) TokenService.AuthContext ctx,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        requireQueryQuota(ctx);
        var wrapper = Wrappers.<GenerateTask>lambdaQuery()
                .eq(GenerateTask::getTokenId, ctx.token().getId())
                .orderByDesc(GenerateTask::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(GenerateTask::getStatus, status);
        }
        Page<GenerateTask> result = taskMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        List<OpenApiDtos.TaskSummary> records = result.getRecords().stream()
                .map(OpenApiDtos.TaskSummary::of).toList();
        return ApiResponse.ok(new PageResult<>(result.getTotal(), records));
    }

    @GetMapping("/images/models")
    public ApiResponse<List<OpenApiDtos.ModelView>> models(
            @RequestAttribute(OpenApiAuthInterceptor.ATTR_AUTH) TokenService.AuthContext ctx) {
        List<OpenApiDtos.ModelView> models = groupMapper.selectList(
                        Wrappers.<ModelGroup>lambdaQuery().eq(ModelGroup::getEnabled, 1))
                .stream()
                .filter(g -> ctx.canUse(g.getGroupCode()))
                .map(g -> new OpenApiDtos.ModelView(g.getGroupCode(),
                        g.getDisplayName(), g.getStrategy()))
                .toList();
        return ApiResponse.ok(models);
    }

    /** 查询/列表共享查询配额：轮询是合法行为（默认 600 次/分/令牌），挡的是写坏的循环与滥用。 */
    private void requireQueryQuota(TokenService.AuthContext ctx) {
        if (!rateLimiter.allowQuery(ctx.token().getId())) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    private void validatePayload(OpenApiDtos.ImageSubmitRequest body) {
        boolean hasPrompt = body.prompt() != null && !body.prompt().isBlank();
        boolean hasImage = body.images() != null && !body.images().isEmpty();
        if (!hasPrompt && !hasImage) {
            throw new BizException(ErrorCode.PARAM_INVALID, "prompt 与 images 至少提供一个");
        }
        if (body.prompt() != null && body.prompt().length() > 4000) {
            throw new BizException(ErrorCode.PARAM_INVALID, "prompt 超过 4000 字符");
        }
        if (body.n() != null && (body.n() < 1 || body.n() > 6)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "n 取值 1-6");
        }
        if (body.images() != null) {
            if (body.images().size() > 4) {
                throw new BizException(ErrorCode.PARAM_INVALID, "参考图最多 4 张");
            }
            body.images().forEach(this::checkUrl);
        }
    }

    private void checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "参考图必须是公网可访问的 http(s) URL: " + u);
        }
    }
}
