package com.logistics.etl;

import com.logistics.etl.dto.IncidentExtraction;
import com.logistics.etl.entity.IncidentReport;
import com.logistics.etl.enums.Urgency;
import com.logistics.etl.exception.IncidentValidationException;
import com.logistics.etl.repository.IncidentRepository;
import com.logistics.etl.service.IncidentETLService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentETLServiceTests {

    @Mock
    private ChatModel chatModel;

    @Mock
    private IncidentRepository repository;

    private IncidentETLService etlService;

    @BeforeEach
    void setUp() {
        etlService = new IncidentETLService(chatModel, repository);
    }

    private void mockChatResponse(String content) {
        AssistantMessage assistantMessage = new AssistantMessage(content);
        Generation generation = new Generation(assistantMessage);
        ChatResponse response = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    @DisplayName("Test 1: JSON Sạch - AI trả về JSON chuẩn, ETL xử lý và lưu database thành công")
    void test1_CleanJsonSuccess() {
        String rawMessage = "Đơn hàng ORD-001 xe 30A-12345 bị hỏng lốp cấp bách.";
        String aiResponse = """
                {
                  "orderCode": "ORD-001",
                  "licensePlate": "30A-12345",
                  "incidentType": "TIRE_FAILURE",
                  "urgency": "HIGH"
                }
                """;

        mockChatResponse(aiResponse);

        IncidentReport mockSaved = new IncidentReport("ORD-001", "30A-12345", "TIRE_FAILURE", Urgency.HIGH);
        mockSaved.setId(1L);
        when(repository.save(any(IncidentReport.class))).thenReturn(mockSaved);

        IncidentReport result = etlService.processReport(rawMessage);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("ORD-001", result.getOrderCode());
        assertEquals("30A-12345", result.getLicensePlate());
        assertEquals("TIRE_FAILURE", result.getIncidentType());
        assertEquals(Urgency.HIGH, result.getUrgency());

        verify(repository).save(any(IncidentReport.class));
    }

    @Test
    @DisplayName("Test 2: Markdown JSON - AI bọc JSON trong Markdown fence, cleanAiResponse bóc tách an toàn và lưu DB")
    void test2_MarkdownJsonSuccess() {
        String rawMessage = "Đơn hàng ORD-002 xe 51F-123.45 bị tai nạn nghiêm trọng.";
        String aiResponseWithMarkdown = """
                ```json
                {
                  "orderCode": "ORD-002",
                  "licensePlate": "51F-123.45",
                  "incidentType": "ACCIDENT",
                  "urgency": "CRITICAL"
                }
                ```
                """;

        mockChatResponse(aiResponseWithMarkdown);

        IncidentReport mockSaved = new IncidentReport("ORD-002", "51F-123.45", "ACCIDENT", Urgency.CRITICAL);
        mockSaved.setId(2L);
        when(repository.save(any(IncidentReport.class))).thenReturn(mockSaved);

        IncidentReport result = etlService.processReport(rawMessage);

        assertNotNull(result);
        assertEquals(2L, result.getId());
        assertEquals("ORD-002", result.getOrderCode());
        assertEquals("51F-123.45", result.getLicensePlate());
        assertEquals(Urgency.CRITICAL, result.getUrgency());

        verify(repository).save(any(IncidentReport.class));
    }

    @Test
    @DisplayName("Test 3: orderCode rỗng/null - Defensive validation chặn lại và repository.save KHÔNG được gọi")
    void test3_OrderCodeNullRejected() {
        String rawMessage = "Xe 30A-12345 gặp sự cố động cơ.";
        String aiResponse = """
                {
                  "orderCode": null,
                  "licensePlate": "30A-12345",
                  "incidentType": "ENGINE_FAILURE",
                  "urgency": "MEDIUM"
                }
                """;

        mockChatResponse(aiResponse);

        IncidentValidationException ex = assertThrows(
                IncidentValidationException.class,
                () -> etlService.processReport(rawMessage)
        );

        assertTrue(ex.getMessage().contains("orderCode"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Test 4: licensePlate sai định dạng regex - Bị từ chối và repository.save KHÔNG được gọi")
    void test4_InvalidLicensePlateRejected() {
        String rawMessage = "Đơn ORD-003 xe biển số ABC-INVALID bị sự cố.";
        String aiResponse = """
                {
                  "orderCode": "ORD-003",
                  "licensePlate": "INVALID_PLATE_XYZ",
                  "incidentType": "TIRE_FAILURE",
                  "urgency": "LOW"
                }
                """;

        mockChatResponse(aiResponse);

        IncidentValidationException ex = assertThrows(
                IncidentValidationException.class,
                () -> etlService.processReport(rawMessage)
        );

        assertTrue(ex.getMessage().contains("Biển số xe"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Test 5: urgency không thuộc Enum - Bị từ chối và repository.save KHÔNG được gọi")
    void test5_InvalidUrgencyRejected() {
        String rawMessage = "Đơn ORD-004 xe 29B-12345 bị hỏng nặng.";
        String aiResponse = """
                {
                  "orderCode": "ORD-004",
                  "licensePlate": "29B-12345",
                  "incidentType": "BRAKE_FAILURE",
                  "urgency": "URGENT"
                }
                """;

        mockChatResponse(aiResponse);

        IncidentValidationException ex = assertThrows(
                IncidentValidationException.class,
                () -> etlService.processReport(rawMessage)
        );

        assertTrue(ex.getMessage().contains("urgency") || ex.getMessage().contains("Urgency"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Test 6: Database Exception - Lỗi database được propagate để Transaction rollback")
    void test6_DatabaseExceptionPropagatesForRollback() {
        String rawMessage = "Đơn ORD-005 xe 30A-99999.";
        String aiResponse = """
                {
                  "orderCode": "ORD-005",
                  "licensePlate": "30A-99999",
                  "incidentType": "OIL_LEAK",
                  "urgency": "LOW"
                }
                """;

        mockChatResponse(aiResponse);
        when(repository.save(any(IncidentReport.class)))
                .thenThrow(new DataIntegrityViolationException("Database constraint violation"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> etlService.processReport(rawMessage)
        );

        verify(repository).save(any(IncidentReport.class));
    }
}
