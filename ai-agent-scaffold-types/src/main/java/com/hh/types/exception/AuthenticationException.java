package com.hh.types.exception;

/** 认证边界专用异常，便于统一映射 HTTP 401 而不影响普通业务异常。 */
public class AuthenticationException extends AppException {

    public AuthenticationException() {
        super(com.hh.types.enums.ResponseCode.AUTHENTICATION_REQUIRED);
    }
}
