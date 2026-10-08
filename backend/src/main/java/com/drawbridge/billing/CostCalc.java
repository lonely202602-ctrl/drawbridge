package com.drawbridge.billing;

import com.drawbridge.domain.entity.Channel;

import java.math.BigDecimal;

/**
 * 上游成本估算：适配器给了实际成本（如 Crun credits）用实际值，否则用渠道单价×张数。
 */
public final class CostCalc {

    private CostCalc() {
    }

    public static BigDecimal costOf(Channel channel, BigDecimal adapterCost, int imageCount) {
        if (adapterCost != null) {
            return adapterCost;
        }
        if (channel == null || channel.getCostPrice() == null) {
            return BigDecimal.ZERO;
        }
        return channel.getCostPrice().multiply(BigDecimal.valueOf(Math.max(1, imageCount)));
    }
}
