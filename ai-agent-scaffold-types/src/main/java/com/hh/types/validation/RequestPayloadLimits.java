package com.hh.types.validation;

/**
 * HTTP 请求及其内部命令共用的载荷边界。
 *
 * <p>这些值用于在进入 Agent Runner 前拒绝明显异常的输入，避免单次请求长期占用
 * 内存、数据库字段和模型上下文。传输层仍应根据部署环境设置请求体上限。</p>
 */
public final class RequestPayloadLimits {

    /** Agent、用户等业务标识的最大字符数。 */
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    /** ADK sessionId 的最大字符数，兼容当前 UUID 形式并预留其他实现。 */
    public static final int MAX_SESSION_ID_LENGTH = 128;

    /** 单段文本最大字符数，避免超大 prompt 直接进入模型调用。 */
    public static final int MAX_TEXT_LENGTH = 16_000;

    /** 单个内联二进制数据的最大字节数。 */
    public static final int MAX_INLINE_DATA_BYTES = 4 * 1024 * 1024;

    /** 单次命令全部内联二进制数据的总字节数。 */
    public static final int MAX_INLINE_DATA_TOTAL_BYTES = 8 * 1024 * 1024;

    /** 一次命令最多携带的内联二进制数据个数。 */
    public static final int MAX_INLINE_DATA_COUNT = 4;

    private RequestPayloadLimits() {
        // 常量类不应被实例化。
    }
}
