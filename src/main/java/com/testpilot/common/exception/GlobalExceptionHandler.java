package com.testpilot.common.exception;

import com.testpilot.common.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(e.getCode()).body(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Result.fail(400, "文件大小不能超过 50MB"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest().body(Result.fail(400, "缺少必填的 file 参数"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        return ResponseEntity.internalServerError().body(Result.fail(500, "服务器内部错误"));
    }
}
