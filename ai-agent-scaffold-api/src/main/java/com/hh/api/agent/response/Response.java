package com.hh.api.agent.response;

import com.hh.types.enums.ResponseCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 7000723935764546321L;

    private String code;
    private String info;
    private T data;

    public static <T> Response<T> success(T data) {
        return new Response<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), data);
    }

    public static <T> Response<T> error(String info) {
        return new Response<>(ResponseCode.UN_ERROR.getCode(), info, null);
    }

    /**
     * 构造携带明确业务错误码的失败响应。
     *
     * <p>业务异常不能被统一降级为未知错误码，否则调用方无法依据稳定错误码处理失败场景。
     */
    public static <T> Response<T> error(String code, String info) {
        return new Response<>(code, info, null);
    }

}
