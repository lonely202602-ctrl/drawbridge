package com.drawbridge.adapter;

import com.drawbridge.infrastructure.config.ChannelConfigService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 生图适配器 SPI：按协议族实现。提交链路只面向本接口。
 */
public interface ImageProvider {

    ProtocolFamily family();

    /**
     * 提交生成。
     *
     * @return Completed=同步上游直接出结果；Accepted=异步上游返回任务号
     * @throws UpstreamCallException 归一化后的上游错误
     */
    SubmitOutcome submit(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt);

    /**
     * 轮询异步任务状态（仅异步协议族会被调用）。
     */
    PollOutcome poll(String upstreamTaskId, ChannelConfigService.ChannelRuntime rt);

    /** 组装发上游的请求体（任务详情存档展示用，与 submit 内部实际发送体一致）。 */
    String buildRequestJson(SubmitCommand cmd, ChannelConfigService.ChannelRuntime rt);

    /** 提交命令：业务参数 + 该成员映射的上游模型名。 */
    record SubmitCommand(
            String upstreamModel,
            String prompt,
            int n,
            String size,
            String aspectRatio,
            List<String> imageUrls,
            Map<String, Object> options,
            int timeoutMs) {
    }

    /** 提交结果。 */
    sealed interface SubmitOutcome {
        /** 同步上游：结果已出。inlineB64=true 表示结果是 base64（不落盘，只随提交响应返回）。 */
        record Completed(List<String> resultUrls, boolean inlineB64,
                         String rawResponse, BigDecimal cost) implements SubmitOutcome {
        }

        /** 异步上游：受理成功。 */
        record Accepted(String upstreamTaskId, String rawResponse) implements SubmitOutcome {
        }
    }

    /** 轮询结果。 */
    record PollOutcome(Phase phase, List<String> resultUrls, boolean inlineB64,
                       String rawResponse, BigDecimal cost, String failMessage) {

        public enum Phase {RUNNING, SUCCEEDED, FAILED}

        public static PollOutcome running(String raw) {
            return new PollOutcome(Phase.RUNNING, null, false, raw, null, null);
        }

        public static PollOutcome succeeded(List<String> urls, boolean inlineB64, String raw, BigDecimal cost) {
            return new PollOutcome(Phase.SUCCEEDED, urls, inlineB64, raw, cost, null);
        }

        public static PollOutcome failed(String message, String raw) {
            return new PollOutcome(Phase.FAILED, null, false, raw, null, message);
        }
    }
}
