package org.example.rag.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class) // 拦截所有未知的 Exception
    public Result<String> handleException(Exception e) {
        log.error("系统异常: ", e);
        // 无论系统出什么错，前端收到的永远是标准的 Result 对象
        return Result.failed(e.getMessage());
    }

    // 你还可以增加特定的异常处理，比如 IllegalArgumentException
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<String> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.failed("参数错误: " + e.getMessage());
    }
}
