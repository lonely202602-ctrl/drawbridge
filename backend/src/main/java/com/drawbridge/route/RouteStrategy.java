package com.drawbridge.route;

/**
 * 分组路由策略。
 */
public enum RouteStrategy {
    /** 轮询：按成员排序依次轮流 */
    POLLING,
    /** 随机：等概率打乱 */
    RANDOM,
    /** 故障转移：严格按排序顺序，第一个可用优先 */
    FAILOVER,
    /** 加权分配：按 weight 加权随机（Efraimidis-Spirakis） */
    WEIGHTED
}
