package com.drawbridge.route;

/**
 * 分组路由策略。
 */
public enum RouteStrategy {
    /** 轮询：按成员排序依次轮流（轮转记忆只在领取成功时推进） */
    POLLING,
    /** 随机：等概率打乱 */
    RANDOM,
    /** 故障转移：严格按排序顺序，第一个可用优先；饱和时等待不跳槽（出错才切换） */
    FAILOVER,
    /** 加权分配：按 weight 加权随机（Efraimidis-Spirakis） */
    WEIGHTED,
    /** 空闲优先：按成员顺序取第一个"所属账号并发未满"的；全满则排队等待 */
    IDLE_FIRST
}
