package com.logistics.etl.enums;

public enum Urgency {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * Parse an toàn từ chuỗi sang enum Urgency, hỗ trợ trim và uppercase.
     *
     * @param value chuỗi giá trị từ AI
     * @return Urgency tương ứng
     * @throws IllegalArgumentException nếu giá trị không hợp lệ hoặc rỗng
     */
    public static Urgency fromString(String value) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException("Urgency không được để trống (null hoặc blank)!");
        }

        String normalized = value.trim().toUpperCase();
        try {
            return Urgency.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Urgency '" + value + "' không hợp lệ! Giá trị bắt buộc phải là một trong: [LOW, MEDIUM, HIGH, CRITICAL]"
            );
        }
    }
}
