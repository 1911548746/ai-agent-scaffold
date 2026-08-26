package com.hh.api.agent.dto.stream;

import com.hh.types.enums.ResponseCode;

/**
 * SSE 发生未预期错误时发送给客户端的公开错误事件。
 *
 * <p>流已经开始后无法可靠切换为普通 JSON 响应，因此使用独立 DTO 固化事件格式，
 * 且绝不能把模型、工具或 SDK 的异常消息放入其中。</p>
 */
public record StreamErrorResponseDTO(String code, String info) {

    private static final String INTERNAL_ERROR_INFO = "操作失败, 请稍后再试";

    /**
     * 返回与普通 HTTP 未知错误一致的稳定错误码和公开文案。
     */
    public static StreamErrorResponseDTO internalError() {
        return new StreamErrorResponseDTO(ResponseCode.UN_ERROR.getCode(), INTERNAL_ERROR_INFO);
    }

    /**
     * 为已分类的安全异常保留稳定错误码；code 和 info 必须来自项目内置枚举，不能传入供应商消息。
     */
    public static StreamErrorResponseDTO knownError(ResponseCode responseCode) {
        return new StreamErrorResponseDTO(responseCode.getCode(), responseCode.getInfo());
    }

}
