package com.drawbridge.task;

/** 任务抵达终态事件：TaskFinisher 写成功后发布，派发器监听后立即补位（提交后触发，无事务也可用）。 */
public record TaskSettledEvent(Long taskId, boolean success) {
}
