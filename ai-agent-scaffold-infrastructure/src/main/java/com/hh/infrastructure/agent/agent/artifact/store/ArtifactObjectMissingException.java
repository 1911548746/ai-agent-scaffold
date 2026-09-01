package com.hh.infrastructure.agent.agent.artifact.store;

/**
 * 表示附件元数据仍存在，但对应的物理对象已经不可读取。
 *
 * <p>该异常只用于本地文件不存在和对象存储明确返回对象不存在的场景。调用方可据此删除过期元数据；
 * 网络、鉴权和其他存储异常不能转换为该异常。</p>
 */
public class ArtifactObjectMissingException extends RuntimeException {

    public ArtifactObjectMissingException(String objectKey, Throwable cause) {
        super("artifact object is missing: " + objectKey, cause);
    }
}
