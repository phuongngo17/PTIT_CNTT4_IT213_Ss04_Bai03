package com.logistics.etl.service;

import com.logistics.etl.dto.IncidentExtraction;
import com.logistics.etl.entity.IncidentReport;
import com.logistics.etl.enums.Urgency;
import com.logistics.etl.exception.IncidentExtractionException;
import com.logistics.etl.exception.IncidentValidationException;
import com.logistics.etl.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * Service ETL bóc tách và xử lý dữ liệu sự cố với kiến trúc Defensive Programming.
 * - Constructor Injection
 * - @Transactional rõ ràng
 * - Làm sạch Markdown JSON an toàn
 * - Defensive Validation trước khi mapping và lưu database
 * - Logging chuẩn SLF4J
 */
@Service
public class IncidentETLService {

    private static final Logger log = LoggerFactory.getLogger(IncidentETLService.class);

    /**
     * Regex biển số xe Việt Nam:
     * - ^[0-9]{2}: 2 chữ số mã tỉnh/thành (VD: 29, 30, 51)
     * - [A-Z]{1,2}: 1 hoặc 2 chữ cái series xe (VD: A, B, F, LD)
     * - -: Dấu gạch ngang phân cách
     * - [0-9]{4,5}: 4 hoặc 5 chữ số thứ tự (VD: 12345)
     * - (\\.[0-9]{2})?: Tùy chọn có dấu chấm 2 số cuối (VD: 123.45)
     */
    private static final Pattern LICENSE_PLATE_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{1,2}-[0-9]{3,5}(\\.[0-9]{2})?$");

    private final ChatModel chatModel;
    private final IncidentRepository repository;

    public IncidentETLService(
            ChatModel chatModel,
            IncidentRepository repository
    ) {
        this.chatModel = chatModel;
        this.repository = repository;
    }

    /**
     * Quy trình ETL 15 bước xử lý báo cáo sự cố từ tin nhắn thô.
     *
     * @param rawMessage tin nhắn thô từ tài xế
     * @return IncidentReport entity đã được lưu vào database
     */
    @Transactional
    public IncidentReport processReport(String rawMessage) {
        // Bước 1: Validate rawMessage đầu vào
        if (rawMessage == null || rawMessage.trim().isBlank()) {
            throw new IllegalArgumentException("Tin nhắn sự cố (rawMessage) không được để trống!");
        }

        // Bước 2: Log bắt đầu nhận message
        log.info("Starting incident ETL processing. messageLength={}", rawMessage.length());

        String safeOrderCode = "UNKNOWN";
        try {
            // Bước 3: Tạo BeanOutputConverter định kiểu cho DTO Record
            BeanOutputConverter<IncidentExtraction> converter =
                    new BeanOutputConverter<>(IncidentExtraction.class);

            // Bước 4: Lấy formatInstructions từ converter
            String formatInstructions = converter.getFormat();

            // Bước 5: Tạo Prompt có cấu trúc rõ ràng (Role, Objective, Rules, Output Schema, Input)
            String promptText = """
                    ROLE:
                    Bạn là AI chuyên gia bóc tách thông tin sự cố vận tải và logistics từ tin nhắn của tài xế.

                    OBJECTIVE:
                    Trích xuất chính xác các trường: orderCode, licensePlate, incidentType, urgency.

                    RULES:
                    - Không được suy đoán dữ liệu không có trong tin nhắn.
                    - Nếu không có dữ liệu cho trường nào, hãy để giá trị null.
                    - urgency BẮT BUỘC phải là một trong 4 giá trị: LOW, MEDIUM, HIGH, CRITICAL.
                    - Chỉ trả về chuỗi JSON thuần túy, không chèn markdown code block, không thêm lời giải thích.

                    OUTPUT FORMAT:
                    %s

                    INPUT MESSAGE:
                    "%s"
                    """.formatted(formatInstructions, rawMessage);

            Prompt prompt = new Prompt(promptText);

            // Bước 6: Gọi ChatModel gửi yêu cầu tới AI
            log.debug("Sending prompt to ChatModel for incident extraction");
            var chatResponse = chatModel.call(prompt);

            // Bước 7: Lấy raw AI response
            String rawResponse = chatResponse.getResult().getOutput().getContent();
            log.debug("Received raw AI response for incident ETL");

            // Bước 8: Làm sạch Markdown và JSON formatting
            String cleanedResponse = cleanAiResponse(rawResponse);

            // Bước 9: BeanOutputConverter chuyển đổi JSON thành DTO Record
            IncidentExtraction dto = converter.convert(cleanedResponse);
            if (dto == null) {
                throw new IncidentExtractionException("BeanOutputConverter trả về null sau khi parse!");
            }

            if (dto.orderCode() != null) {
                safeOrderCode = dto.orderCode();
            }

            // Bước 10: Log parse thành công
            log.info("AI extraction parsed successfully. orderCode={}, licensePlate={}, urgency={}",
                    dto.orderCode(), dto.licensePlate(), dto.urgency());

            // Bước 11: Defensive Validation kiểm tra dữ liệu DTO
            validateExtraction(dto);
            log.info("Incident extraction validation passed. orderCode={}", dto.orderCode());

            // Bước 12: Mapping an toàn từ DTO sang JPA Entity
            Urgency urgency = Urgency.fromString(dto.urgency());
            IncidentReport entity = new IncidentReport(
                    dto.orderCode().trim().toUpperCase(),
                    dto.licensePlate().trim().toUpperCase(),
                    dto.incidentType().trim(),
                    urgency
            );

            // Bước 13: Lưu Entity vào Database qua JPA Repository
            IncidentReport saved = repository.save(entity);

            // Bước 14: Log save thành công
            log.info("Incident report saved successfully. id={}, orderCode={}",
                    saved.getId(), saved.getOrderCode());

            // Bước 15: Trả về Entity đã persist
            return saved;

        } catch (Exception ex) {
            log.error("Incident ETL failed. orderCode={}, message={}",
                    safeOrderCode, ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Làm sạch response từ AI: loại bỏ Markdown fence (```json ... ```), khoảng trắng thừa.
     *
     * @param response chuỗi trả về thô từ AI
     * @return chuỗi JSON sạch
     */
    public String cleanAiResponse(String response) {
        if (response == null || response.trim().isBlank()) {
            throw new IncidentExtractionException("AI response rỗng (null hoặc blank)!");
        }

        String cleaned = response.trim();

        // Loại bỏ Markdown code block ```json hoặc ``` ở đầu và cuối
        cleaned = cleaned.replaceAll("(?s)^\\s*```(?:json)?\\s*", "")
                         .replaceAll("(?s)\\s*```\\s*$", "")
                         .trim();

        // Nếu chuỗi còn bao quanh bởi dấu ngoặc kép hoặc ký tự lạ ngoài JSON object
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace >= firstBrace) {
            cleaned = cleaned.substring(firstBrace, lastBrace + 1).trim();
        }

        if (cleaned.isBlank()) {
            throw new IncidentExtractionException("AI response is empty after cleaning Markdown fences!");
        }

        return cleaned;
    }

    /**
     * Defensive validation chi tiết từng trường dữ liệu của DTO.
     *
     * @param dto đối tượng DTO cần validate
     */
    public void validateExtraction(IncidentExtraction dto) {
        if (dto == null) {
            throw new IncidentValidationException("Dữ liệu trích xuất (IncidentExtraction) không được null!");
        }

        // 1. Validate orderCode
        if (dto.orderCode() == null || dto.orderCode().trim().isBlank()) {
            throw new IncidentValidationException("Validation thất bại: orderCode không được để trống (null hoặc blank)!");
        }

        // 2. Validate licensePlate
        if (dto.licensePlate() == null || dto.licensePlate().trim().isBlank()) {
            throw new IncidentValidationException("Validation thất bại: licensePlate không được để trống (null hoặc blank)!");
        }

        String formattedPlate = dto.licensePlate().trim().toUpperCase();
        if (!LICENSE_PLATE_PATTERN.matcher(formattedPlate).matches()) {
            throw new IncidentValidationException(
                    "Validation thất bại: Biển số xe '" + dto.licensePlate()
                    + "' sai định dạng! Định dạng chuẩn VD: 30A-12345, 51F-123.45, 29B-12345"
            );
        }

        // 3. Validate incidentType
        if (dto.incidentType() == null || dto.incidentType().trim().isBlank()) {
            throw new IncidentValidationException("Validation thất bại: incidentType không được để trống!");
        }

        // 4. Validate urgency
        if (dto.urgency() == null || dto.urgency().trim().isBlank()) {
            throw new IncidentValidationException("Validation thất bại: urgency không được để trống!");
        }

        try {
            Urgency.fromString(dto.urgency());
        } catch (IllegalArgumentException ex) {
            throw new IncidentValidationException("Validation thất bại: " + ex.getMessage(), ex);
        }
    }
}
