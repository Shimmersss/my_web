package com.web.backen.auth;

public class AuthException extends RuntimeException {
    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
