package com.bilicharge.archive;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
final class ApiExceptionHandler {
    @ExceptionHandler(AdminException.class)
    ResponseEntity<ApiError> admin(AdminException error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(
                new ApiError(error.code(), error.getMessage(), requestId(request)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException error, HttpServletRequest request) {
        String code = switch (error.getStatusCode().value()) {
            case 401 -> "UNAUTHORIZED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "REQUEST_FAILED";
        };
        return ResponseEntity.status(error.getStatusCode()).body(
                new ApiError(code, error.getReason(), requestId(request)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> badJson(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError("BAD_REQUEST", "请求 JSON 无效", requestId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError("NOT_FOUND", "资源不存在", requestId(request)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> wrongMethod(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                new ApiError("METHOD_NOT_ALLOWED", "请求方法不支持", requestId(request)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> wrongContentType(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
                new ApiError("UNSUPPORTED_MEDIA_TYPE", "请求内容类型不支持", requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError("INTERNAL_ERROR", "服务器内部错误", requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(AccessFilter.REQUEST_ID);
        return value == null ? "" : value.toString();
    }
}
