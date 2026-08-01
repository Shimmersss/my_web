package com.web.backen.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handle(AuthException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "code", exception.getStatus(), "message", exception.getMessage()));
    }
}
