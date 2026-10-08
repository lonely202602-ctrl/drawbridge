package com.drawbridge.billing;

import com.drawbridge.domain.entity.GenerateTask;
import com.drawbridge.domain.entity.UsageLog;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import com.drawbridge.domain.mapper.UsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计量与额度：提交时按我方售价×张数冻结（原子扣减），终态结算/退款，落用量流水。
 * 失败任务不向业务方收费（退款），成本金额仅供对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final ApiTokenMapper tokenMapper;
    private final UsageLogMapper usageLogMapper;

    /** 冻结（提交前调用）。返回 false=额度不足或令牌不可用。 */
    public boolean freeze(Long tokenId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return tokenMapper.freeze(tokenId, amount, LocalDateTime.now()) > 0;
    }

    /** 失败退款（解冻）。 */
    public void refund(Long tokenId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        tokenMapper.refund(tokenId, amount, LocalDateTime.now());
    }

    /** 成功结算：冻结额即实际扣费（我方按张计价，提交时已确定）。 */
    public void settleSuccess(GenerateTask task, BigDecimal costAmount) {
        task.setSettleAmount(task.getFreezeAmount());
        writeUsage(task, 1, costAmount, null);
    }

    /** 失败结算：退款 + 失败流水。 */
    public void settleFailure(GenerateTask task, String errorKind) {
        refund(task.getTokenId(), task.getFreezeAmount());
        task.setSettleAmount(BigDecimal.ZERO);
        writeUsage(task, 0, BigDecimal.ZERO, errorKind);
    }

    private void writeUsage(GenerateTask task, int success, BigDecimal costAmount, String errorKind) {
        try {
            UsageLog usage = new UsageLog();
            usage.setTaskId(task.getTaskId());
            usage.setAppId(task.getAppId());
            usage.setTokenId(task.getTokenId());
            usage.setGroupCode(task.getGroupCode());
            usage.setChannelId(task.getChannelId());
            usage.setSuccess(success);
            usage.setImageCount(task.getImageCount() == null ? 1 : task.getImageCount());
            usage.setBizAmount(success == 1
                    && task.getFreezeAmount() != null ? task.getFreezeAmount() : BigDecimal.ZERO);
            usage.setCostAmount(costAmount == null ? BigDecimal.ZERO : costAmount);
            usage.setErrorKind(errorKind);
            usage.setCreatedAt(LocalDateTime.now());
            usageLogMapper.insert(usage);
        } catch (Exception e) {
            // 流水失败不影响任务状态（终态已定，监控对账可补偿）
            log.error("用量流水写入失败: taskId={}", task.getTaskId(), e);
        }
    }
}
