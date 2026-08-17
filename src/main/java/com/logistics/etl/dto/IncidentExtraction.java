package com.logistics.etl.dto;

/**
 * Java Record DTO hứng dữ liệu bóc tách từ LLM thông qua BeanOutputConverter.
 * Bất biến, không chứa ID database hay JPA annotations.
 */
public record IncidentExtraction(
    String orderCode,
    String licensePlate,
    String incidentType,
    String urgency
) {}
