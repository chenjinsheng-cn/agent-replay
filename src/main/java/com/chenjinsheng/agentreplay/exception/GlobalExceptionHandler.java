package com.chenjinsheng.agentreplay.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应。
 *
 * <p>业务异常给可读的中文说明，系统异常只给一句「内部错误」——
 * 堆栈不往外发。这跟短链那边是同一套做法，理由也一样：堆栈里可能带路径、带配置。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RunNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ForkNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> badRequest(ForkNotAllowedException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + "：" + err.getDefaultMessage())
                .orElse("参数校验失败");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "内部错误：" + e.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", status.getReasonPhrase());
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }
}
