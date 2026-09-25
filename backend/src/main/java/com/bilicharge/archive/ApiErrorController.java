package com.bilicharge.archive;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ApiErrorController implements ErrorController {
    @RequestMapping("/error")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = raw instanceof Integer value ? value : 500;
        String code = status == 404 ? "NOT_FOUND" : "REQUEST_FAILED";
        String message = status == 404 ? "资源不存在" : "请求失败";
        Object id = request.getAttribute(AccessFilter.REQUEST_ID);
        return ResponseEntity.status(status).body(new ApiError(code, message, id == null ? "" : id.toString()));
    }
}
