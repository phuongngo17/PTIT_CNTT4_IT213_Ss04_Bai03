# Incident ETL Defensive Refactor

Dự án Java Spring Boot 3 thực hiện tối ưu hóa và tái cấu trúc (refactor) toàn diện quy trình **ETL (Extract - Transform - Load)** dữ liệu sự cố giao thông từ tin nhắn tài xế thông qua AI (Spring AI), áp dụng nguyên tắc **Defensive Programming (Lập trình phòng thủ)** chuẩn Enterprise Production.

---

## Objective

* Refactor hoàn toàn đoạn mã thô sơ ban đầu thành một service có chất lượng production-ready.
* Giải quyết triệt để 2 lỗi hiểm hóc:
  1. **Markdown JSON Leakage**: LLM bọc JSON trong Markdown code block (` ```json ... ``` `).
  2. **Dữ liệu rỗng / rác / ảo giác (Hallucination)**: LLM trả về null, chuỗi rỗng hoặc giá trị enum không hợp lệ.
* Thiết lập quy trình 15 bước xử lý ETL an toàn, kết hợp `@Transactional`, Constructor Injection và SLF4J logging có cấu trúc.

---

## Architecture

```text
               TIN NHẮN SỰ CỐ TỪ TÀI XẾ
                         │
                         ▼
        ┌────────────────────────────────┐
        │   IncidentETLService (Spring)  │
        │   @Transactional boundary      │
        └────────────────┬───────────────┘
                         │
                         ▼ (Prompt + formatInstructions)
              ChatModel (Spring AI / LLM)
                         │
                         ▼ (Raw AI Response)
        ┌────────────────────────────────┐
        │       cleanAiResponse()        │
        │  • Loại bỏ Markdown fences     │
        │  • Trích xuất JSON Object gốc  │
        └────────────────┬───────────────┘
                         │
                         ▼
         BeanOutputConverter<IncidentExtraction>
                         │
                         ▼
             IncidentExtraction (DTO Record)
                         │
                         ▼
        ┌────────────────────────────────┐
        │      validateExtraction()      │
        │  • orderCode NOT BLANK         │
        │  • licensePlate REGEX check    │
        │  • urgency ENUM check          │
        └───────┬────────────────┬───────┘
                │                │
            [ PASS ]         [ FAIL ]
                │                │
                ▼                ▼
         Mapping Entity      Throw Exception
                │                │
                ▼                ▼
        repository.save()     ROLLBACK
                │
                ▼
             COMMIT
```

---

## Defensive Validation

Dữ liệu do LLM sinh ra là **Untrusted Input**. Tầng Defensive Validation kiểm tra nghiêm ngặt:

1. **`orderCode`**: Bắt buộc không được null hoặc blank.
2. **`licensePlate`**: Kiểm tra theo biểu thức chính quy (Regex):
   ```text
   ^[0-9]{2}[A-Z]{1,2}-[0-9]{3,5}(\\.[0-9]{2})?$
   ```
   * Hỗ trợ chuẩn biển số xe Việt Nam: `30A-12345`, `51F-123.45`, `29B-12345`.
3. **`urgency`**: Bắt buộc thuộc đúng tập Enum `Urgency` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).

---

## AI Response Cleaning

Hàm `cleanAiResponse()` xử lý an toàn các trường hợp:
* Cắt bỏ Markdown code block ```` ```json ... ``` ```` hoặc ```` ``` ... ``` ````.
* Cắt tỉa khoảng trắng (trim).
* Cô lập chuỗi JSON từ dấu `{` đầu tiên đến dấu `}` cuối cùng.

---

## Transaction Management

* Service được đánh dấu `@Transactional`.
* Toàn bộ thao tác ghi dữ liệu `repository.save()` được bảo vệ; nếu có lỗi trong validation hoặc persistence, transaction sẽ tự động Rollback.
* **Lưu ý kiến trúc Production**: Gọi LLM qua mạng có thể có độ trễ (10-20s). Trong hệ thống tải cao, nên cân nhắc tách LLM call ra ngoài và chỉ mở transaction cho bước persistence.

---

## Logging

Áp dụng chuẩn **SLF4J**:
* Log nhận request kèm độ dài message.
* Log parse thành công DTO.
* Log validation pass.
* Log lưu entity thành công kèm ID.
* Log chi tiết lỗi và stack trace khi gặp sự cố mà không nuốt exception.

---

## Testing

Chạy toàn bộ 6 test cases bao quát:
```bash
mvn clean test
```

---

## Running

Đóng gói và chạy ứng dụng:
```bash
mvn clean package
java -jar target/incident-etl-defensive-refactor-0.0.1-SNAPSHOT.jar
```
