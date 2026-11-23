package ru.tsu.accounts.identity;

import org.springframework.http.HttpStatus;

public class UserCommandException extends RuntimeException {
    private final HttpStatus status;
    private final String reasonCode;
    private final String fieldName;

    public UserCommandException(HttpStatus status, String reasonCode, String message) {
        this(status, reasonCode, message, null);
    }

    public UserCommandException(HttpStatus status, String reasonCode, String message, String fieldName) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
        this.fieldName = fieldName;
    }

    public HttpStatus status() { return status; }
    public String reasonCode() { return reasonCode; }
    public String fieldName() { return fieldName; }
}
