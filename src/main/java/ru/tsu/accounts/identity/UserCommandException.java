package ru.tsu.accounts.identity;

import org.springframework.http.HttpStatus;

public class UserCommandException extends RuntimeException {
    private final HttpStatus status;
    private final String reasonCode;

    public UserCommandException(HttpStatus status, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public HttpStatus status() { return status; }
    public String reasonCode() { return reasonCode; }
}
