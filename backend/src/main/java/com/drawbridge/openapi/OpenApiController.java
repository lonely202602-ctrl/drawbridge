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
        // 归一化：image（单张，OpenAI 习惯）并入 images 首位。
        // 比例/档位没传就存 null——适配器"没传不发参数"，模型用自己的默认（修图不裁方图）；
        // 不再强填 1:1/1k 默认（那是把"用户没说"偷换成"用户要方图"）
        List<String> merged = new java.util.ArrayList<>();
        if (body.image() != null && !body.image().isBlank()) {
            merged.add(body.image().trim());
        }
        if (body.images() != null) {
            body.images().forEach(u -> {
                if (u != null && !u.isBlank()) {
                    merged.add(u.trim());
                }
            });
        }
        String ratio = body.aspectRatio() == null || body.aspectRatio().isBlank()
                ? null : body.aspectRatio().trim();
        String tier = body.resolution() == null || body.resolution().isBlank()
                ? null : body.resolution().trim().toLowerCase();
        // 旧版 size（deprecated）：auto 视为没传；1024X1024 大写 X 不挑；格式不认识直接 400，
        // 不静默丢弃——老调用方的尺寸意图丢了会拿到完全不相干的图
        String legacySize = body.size();
        if (legacySize != null) {
            legacySize = legacySize.trim();
            if (legacySize.equalsIgnoreCase("auto")) {
                legacySize = null;
            } else if (!legacySize.matches("\\d{3,4}[xX]\\d{3,4}")) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "size 已废弃，请改用 aspectRatio（W:H）+ resolution（1k/2k/4k）；兼容格式为 宽x高（如 1024x1024）: "
                                + legacySize);
            } else {
                legacySize = legacySize.toLowerCase(java.util.Locale.ROOT);
            }
        }
        OpenApiDtos.ImageSubmitRequest req = new OpenApiDtos.ImageSubmitRequest(body.model(), body.prompt(),
                body.n(), tier, ratio, legacySize, null, merged.isEmpty() ? null : merged, body.options());
        validatePayload(req);

        int n = req.n() == null ? 1 : req.n();
        // 幂等：同令牌同 key 直接返回已有任务。空白键归一成 null（不该占唯一键，更不该静默复用旧任务）
        String idemKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (idemKey != null && idemKey.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "幂等键最长 64 字符");
        }
        if (idemKey != null) {
            GenerateTask existing = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                    .eq(GenerateTask::getTokenId, ctx.token().getId())
                    .eq(GenerateTask::getClientRequestId, idemKey)
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
        // biz_request 完整存档（MEDIUMTEXT）：执行链路从它解析入参，截断会导致参数静默丢失
        task.setBizRequest(JsonUtil.toJson(req));
        task.setClientRequestId(idemKey);
        LocalDateTime now = LocalDateTime.now();
        task.setNextPollTime(now);
        task.setExpiresAt(now.plusNanos(config.getLong(ConfigKeys.TASK_MAX_LIFETIME_MS) * 1_000_000));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发幂等竞争：唯一键是 (token_id, client_request_id)，冲突必属本令牌，回查退回已有任务
            GenerateTask existing = taskMapper.selectOne(Wrappers.<GenerateTask>lambdaQuery()
                    .eq(GenerateTask::getTokenId, ctx.token().getId())
                    .eq(GenerateTask::getClientRequestId, idemKey)
                    .last("limit 1"));
            if (existing == null) {
                log.error("幂等冲突但按令牌回查不到（不应发生）: tokenId={}, key={}", ctx.token().getId(), idemKey);
                throw new BizException(ErrorCode.PARAM_INVALID, "幂等键冲突，请更换后重试");
            }
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
        Page<GenerateTask> result = taskMapper.selectPage(
                new Page<>(page, Math.max(1, Math.min(size, 100))), wrapper);
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
        if (body.n() != null && body.n() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一个任务固定出一张图，不支持 n 参数，多张图请提交多个任务");
        }
        if (body.images() != null) {
            // 数量不设网关上限：各模型参考图容量不同（gpt-image 系 14 张、nano 系更少），
            // 超出时上游会给出精确报错（Crun 422 errors 明细），网关代为判断反而会错杀合法请求
            body.images().forEach(this::checkUrl);
        }
        if (body.aspectRatio() != null && !body.aspectRatio().matches("[1-9][0-9]{0,4}:[1-9][0-9]{0,4}")) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "aspectRatio 须为 W:H 形式的比例（如 1:1、16:9、9:16）: " + body.aspectRatio());
        }
        if (body.resolution() != null && !java.util.Set.of("1k", "2k", "4k").contains(body.resolution())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "resolution 只支持 1k/2k/4k: " + body.resolution());
        }
        if (body.options() != null
                && JsonUtil.toJson(body.options()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new BizException(ErrorCode.PARAM_INVALID, "options 序列化后不能超过 64KB");
        }
    }

    private void checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String u = url.trim();
        if (u.length() > 2048) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参考图 URL 过长（>2048 字符）");
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "参考图必须是公网可访问的 http(s) URL: " + u);
        }
    }
}
