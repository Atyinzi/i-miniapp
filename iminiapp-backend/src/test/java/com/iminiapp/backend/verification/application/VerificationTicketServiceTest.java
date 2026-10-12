package com.iminiapp.backend.verification.application;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationTicketServiceTest {
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 9);

    @Test
    void 军事体验在开始前十五分钟至结束后十五分钟内允许核销() {
        assertDoesNotThrow(() -> VerificationTicketService.validateExperienceTimeSlot(
                "EXPERIENCE", SERVICE_DATE, "09:00-10:00", LocalDateTime.of(2026, 8, 9, 8, 45)));
        assertDoesNotThrow(() -> VerificationTicketService.validateExperienceTimeSlot(
                "EXPERIENCE", SERVICE_DATE, "09:00-10:00", LocalDateTime.of(2026, 8, 9, 10, 15)));
    }

    @Test
    void 军事体验过早核销会被拒绝() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> VerificationTicketService.validateExperienceTimeSlot(
                        "EXPERIENCE", SERVICE_DATE, "09:00-10:00", LocalDateTime.of(2026, 8, 9, 8, 44)));

        assertEquals(CommonErrorCode.VERIFICATION_CONFLICT, exception.errorCode());
        assertEquals("军事体验尚未到可核销时间，预约时段为 09:00-10:00，可提前15分钟核销", exception.getMessage());
    }

    @Test
    void 军事体验过晚核销会被拒绝() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> VerificationTicketService.validateExperienceTimeSlot(
                        "EXPERIENCE", SERVICE_DATE, "09:00-10:00", LocalDateTime.of(2026, 8, 9, 10, 16)));

        assertEquals(CommonErrorCode.VERIFICATION_CONFLICT, exception.errorCode());
        assertEquals("军事体验已超过可核销时间，预约时段为 09:00-10:00，结束后15分钟停止核销", exception.getMessage());
    }

    @Test
    void 普通门票不受时段校验影响() {
        assertDoesNotThrow(() -> VerificationTicketService.validateExperienceTimeSlot(
                "TICKET", SERVICE_DATE, null, LocalDateTime.of(2026, 8, 9, 23, 59)));
        assertDoesNotThrow(() -> VerificationTicketService.validateExperienceTimeSlot(
                null, SERVICE_DATE, "无效时段", LocalDateTime.of(2026, 8, 9, 0, 1)));
    }
}
