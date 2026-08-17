package com.logistics.etl.exception;

/**
 * Exception ném ra khi quá trình nhận và parse dữ liệu từ AI gặp lỗi.
 */
public class IncidentExtractionException extends RuntimeException {

    public IncidentExtractionException(String message) {
        super(message);
    }

    public IncidentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
