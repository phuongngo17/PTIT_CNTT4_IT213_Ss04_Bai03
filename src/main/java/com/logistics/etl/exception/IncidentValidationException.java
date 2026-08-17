package com.logistics.etl.exception;

/**
 * Exception ném ra khi dữ liệu bóc tách từ AI không vượt qua được Defensive Validation.
 */
public class IncidentValidationException extends RuntimeException {

    public IncidentValidationException(String message) {
        super(message);
    }

    public IncidentValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
