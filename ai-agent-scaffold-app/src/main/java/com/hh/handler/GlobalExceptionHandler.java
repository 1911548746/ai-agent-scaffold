package com.hh.handler;

import com.hh.api.response.Response;
import com.hh.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     */
    @ExceptionHandler
    public Response exceptionHandler(AppException ex){

        log.error("业务异常, 异常信息：{}", ex.getMessage());

        return Response.error(ex.getMessage());
    }

    /**
     * 捕获其他异常
     */
    @ExceptionHandler
    public Response exceptionHandler(Exception ex){

        String message = ex.getMessage();

        log.error("系统异常, 异常信息：{}", message);

        return Response.error("操作失败, 请稍后再试");
    }

}
