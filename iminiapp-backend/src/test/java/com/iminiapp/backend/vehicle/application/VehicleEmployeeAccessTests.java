package com.iminiapp.backend.vehicle.application;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import com.iminiapp.backend.vehicle.api.VehicleApplicationCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VehicleEmployeeAccessTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final VehicleEmployeeService employees = mock(VehicleEmployeeService.class);
    private final VehicleApplicationService service = new VehicleApplicationService(jdbc, employees);
    private final AuthenticatedPrincipal user = new AuthenticatedPrincipal(
            "user-1", "普通用户", "USER", List.of("ROLE_USER"));

    @Test
    void ordinaryUserCannotCreateInternalVehicleApplication() {
        BusinessException forbidden = new BusinessException(CommonErrorCode.FORBIDDEN, "未绑定员工档案");
        when(employees.requireActiveEmployee(user)).thenThrow(forbidden);
        VehicleApplicationCreateRequest request = new VehicleApplicationCreateRequest(
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2),
                "办公楼", "示例景区", 3, BigDecimal.valueOf(30), "现场协调");

        BusinessException actual = assertThrows(BusinessException.class,
                () -> service.create(user, "employee-access-test", request));

        assertSame(forbidden, actual);
        verifyNoInteractions(jdbc);
    }

    @Test
    void unboundUserCannotReadInternalVehicleApplicationHistory() {
        BusinessException forbidden = new BusinessException(CommonErrorCode.FORBIDDEN, "未绑定员工档案");
        when(employees.requireBoundEmployee(user)).thenThrow(forbidden);

        BusinessException actual = assertThrows(BusinessException.class, () -> service.listMine(user));

        assertSame(forbidden, actual);
        verifyNoInteractions(jdbc);
    }
}
