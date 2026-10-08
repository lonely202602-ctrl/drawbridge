package com.drawbridge.openapi.dto;

import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务方 API 出入参（与 docs/API.md 对应）。
 */
public final class OpenApiDtos {

    private OpenApiDtos() {
    }

    public record ImageSubmitRequest(
            String model,
            String prompt,
            Integer n,
            String size,
            String aspectRatio,
            String image,
            List<String> images,
            JsonNode options) {
    }

    /** 任务摘要（提交/查询/列表统一形状）。 */
    public record TaskSummary(
            String taskId,
            String model,
            String status,
            int n,
            ResultView result,
            ErrorView error,
            BigDecimal charge,
            LocalDateTime createdAt,
            LocalDateTime finishTime) {

        public static TaskSummary of(GenerateTask task) {
            ResultView result = null;
            if (task.getResult() != null && !task.getResult().isBlank()) {
                JsonNode node = com.drawbridge.common.JsonUtil.readTree(task.getResult());
                List<String> urls = new java.util.ArrayList<>();
                node.path("urls").forEach(u -> {
                    if (u.isTextual()) {
                        urls.add(u.asText());
                    }
                });
                String note = node.path("note").isTextual() ? node.get("note").asText() : null;
                result = new ResultView(urls, note);
            }
            ErrorView error = task.getErrorCode() == null ? null
                    : new ErrorView(task.getErrorCode(), task.getErrorMsg());
            return new TaskSummary(task.getTaskId(), task.getGroupCode(),
                    task.getStatus(), task.getImageCount() == null ? 1 : task.getImageCount(),
                    TaskStatus.SUCCEEDED.name().equals(task.getStatus()) ? result : null,
                    error, task.getFreezeAmount(), task.getCreatedAt(), task.getFinishTime());
        }
    }

    public record ResultView(List<String> urls, String note) {
    }

    public record ErrorView(String code, String message) {
    }

    public record ModelView(String model, String displayName, BigDecimal price, String strategy) {
    }

    public record BalanceView(BigDecimal quotaTotal, BigDecimal quotaUsed, String unit) {
    }
}
