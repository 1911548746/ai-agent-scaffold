package com.hh.application.agent.model;

/** 标识一次 Runtime 发布或 Reload 的触发来源。 */
public enum RuntimeReloadTrigger {

    STARTUP,
    MANUAL,
    FILE_WATCH,
    FILE_WATCH_RETRY
}
