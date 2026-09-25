package com.bilicharge.archive;

import org.springframework.http.HttpStatus;

final class AdminException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    AdminException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
}
