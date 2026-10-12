package com.iminiapp.backend.verification.api;

import com.iminiapp.backend.identity.web.AuthenticatedPrincipal;
import com.iminiapp.backend.identity.web.AuthenticationInterceptor;
import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.verification.application.VerificationTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaffVerificationControllerSecurityTests {
    private final VerificationTicketService service = mock(VerificationTicketService.class);
    private final StaffVerificationController controller = new StaffVerificationController(service);

    @Test
    void exposesOnlyThePermissionProtectedAdministratorRoute() throws Exception {
        PostMapping mapping = StaffVerificationController.class
                .getMethod("consume", ConsumeVerificationRequest.class, HttpServletRequest.class)
                .getAnnotation(PostMapping.class);

        assertArrayEquals(new String[]{"/api/v1/admin/verifications/consume"}, mapping.value());
    }

    @Test
    void administratorWithVerificationPermissionCanConsume() {
        AuthenticatedPrincipal principal = principal("ADMIN", List.of("verification.use"));
        ConsumeVerificationRequest body = new ConsumeVerificationRequest("BFHV-1", "GATE-1");

        assertDoesNotThrow(() -> controller.consume(body, request(principal)));

        verify(service).consume(principal, "BFHV-1", "GATE-1");
    }

    @Test
    void ordinaryUserCannotUseVerificationEvenWithForgedPermissionList() {
        AuthenticatedPrincipal principal = principal("USER", List.of("verification.use"));

        assertThrows(BusinessException.class,
                () -> controller.consume(new ConsumeVerificationRequest("BFHV-1", "GATE-1"), request(principal)));

        verifyNoInteractions(service);
    }

    @Test
    void administratorWithoutVerificationPermissionCannotConsume() {
        AuthenticatedPrincipal principal = principal("ADMIN", List.of("orders.view"));

        assertThrows(BusinessException.class,
                () -> controller.consume(new ConsumeVerificationRequest("BFHV-1", "GATE-1"), request(principal)));

        verifyNoInteractions(service);
    }

    private static AuthenticatedPrincipal principal(String accountType, List<String> permissions) {
        return new AuthenticatedPrincipal("9", "staff", accountType, List.of("VERIFIER"),
                "PASSWORD", "staff-account", permissions);
    }

    private static HttpServletRequest request(AuthenticatedPrincipal principal) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthenticationInterceptor.PRINCIPAL_ATTRIBUTE)).thenReturn(principal);
        return request;
    }
}
