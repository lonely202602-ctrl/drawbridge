package com.drawbridge.adapter;

/**
 * 协议族：适配器按协议族实现，而不是按厂商。
 * VOLCANO_ARK 为视频协议，生图阶段占位。
 */
public enum ProtocolFamily {
    /** OpenAI Images 同步：POST {base}/images/generations 直接出结果（apiyi/laozhang/uuapi/nezhagate 同步模型） */
    OPENAI_IMAGES_SYNC,
    /** OpenAI 风格异步任务：提交返回任务号 + 轮询查询（nezhagate jobs / uuapi async tasks，字段指针可配） */
    OPENAI_ASYNC_TASK,
    /** Crun 私有任务协议：CreateTask/TaskInfo，x-api-key，{code,message,data} 壳 */
    CRUN_TASK,
    /** 火山方舟 Seedance（视频阶段启用） */
    VOLCANO_ARK
}
