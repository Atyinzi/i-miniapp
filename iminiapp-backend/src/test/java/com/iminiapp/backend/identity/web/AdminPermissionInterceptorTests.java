package com.iminiapp.backend.identity.web;

import com.iminiapp.backend.shared.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminPermissionInterceptorTests {
    private final AdminPermissionInterceptor interceptor = new AdminPermissionInterceptor();

    @Test
    void allowsAccountWithRequiredPermission() {
        HttpServletRequest request = request("/api/v1/admin/settings/roles",
                principal("ADMIN", List.of("settings.manage")));
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void rejectsAccountWithoutRequiredPermission() {
        HttpServletRequest request = request("/api/v1/admin/settings/roles",
                principal("ADMIN", List.of("orders.view")));
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void protectsVerificationWithDedicatedPermission() {
        HttpServletRequest request = request("/api/v1/admin/sports/verifications",
                principal("ADMIN", List.of("verification.use")));
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void rejectsOrdinaryUserTokenFromAdminIdentityEndpoint() {
        HttpServletRequest request = request("/api/v1/admin/auth/me",
                principal("USER", List.of("verification.use")));
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void allowsAdministratorIdentityCheckWithoutBusinessPermission() {
        HttpServletRequest request = request("/api/v1/admin/auth/me", principal("ADMIN", List.of()));
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void keepsAdministratorLoginPublic() {
        HttpServletRequest request = request("/api/v1/admin/auth/login", null);
        assertDoesNotThrow(() -> interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void gateSecretsRequireDedicatedPermission() {
        HttpServletRequest catalogOnly = request("/api/v1/admin/gate-devices/1/rotate-secret",
                principal("ADMIN", List.of("catalog.manage")));
        HttpServletRequest gateManager = request("/api/v1/admin/gate-devices/1/rotate-secret",
                principal("ADMIN", List.of("gate.manage")));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(catalogOnly, mock(HttpServletResponse.class), new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(gateManager, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void notificationReadsAndMutationsUseDifferentPermissions() {
        HttpServletRequest viewerReading = request("GET", "/api/v1/admin/notifications/tasks",
                principal("ADMIN", List.of("notifications.view")));
        HttpServletRequest viewerRequeueing = request("POST", "/api/v1/admin/notifications/tasks/requeue-configured",
                principal("ADMIN", List.of("notifications.view")));
        HttpServletRequest managerRequeueing = request("POST", "/api/v1/admin/notifications/tasks/requeue-configured",
                principal("ADMIN", List.of("notifications.manage")));

        assertDoesNotThrow(() -> interceptor.preHandle(viewerReading, mock(HttpServletResponse.class), new Object()));
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(viewerRequeueing, mock(HttpServletResponse.class), new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(managerRequeueing, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void refundReviewCannotBePerformedByCatalogAdministrator() {
        HttpServletRequest catalogAdmin = request("POST", "/api/v1/admin/refunds/81/review",
                principal("ADMIN", List.of("catalog.manage")));
        HttpServletRequest refundReviewer = request("POST", "/api/v1/admin/refunds/81/review",
                principal("ADMIN", List.of("refunds.review")));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(catalogAdmin, mock(HttpServletResponse.class), new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(refundReviewer, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void operationalMutationsDoNotAcceptReadOrCatalogPermissions() {
        HttpServletRequest orderViewer = request("POST", "/api/v1/admin/orders/expire-overdue",
                principal("ADMIN", List.of("orders.view")));
        HttpServletRequest catalogManager = request("POST", "/api/v1/admin/hotels/operations/bookings/7/check-in",
                principal("ADMIN", List.of("catalog.manage")));
        HttpServletRequest operator = request("POST", "/api/v1/admin/hotels/operations/bookings/7/check-in",
                principal("ADMIN", List.of("operations.manage")));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(orderViewer, mock(HttpServletResponse.class), new Object()));
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(catalogManager, mock(HttpServletResponse.class), new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(operator, mock(HttpServletResponse.class), new Object()));
    }

    @Test
    void auditLogsRequireSettingsPermission() {
        HttpServletRequest reporter = request("GET", "/api/v1/admin/audit-logs",
                principal("ADMIN", List.of("reports.view")));
        HttpServletRequest settingsManager = request("GET", "/api/v1/admin/audit-logs",
                principal("ADMIN", List.of("settings.manage")));

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(reporter, mock(HttpServletResponse.class), new Object()));
        assertDoesNotThrow(() -> interceptor.preHandle(settingsManager, mock(HttpServletResponse.class), new Object()));
    }

    private static AuthenticatedPrincipal principal(String accountType, List<String> permissions) {
        return new AuthenticatedPrincipal("1", "operator", accountType, List.of("VERIFIER"),
                "PASSWORD", "operator", permissions);
    }

    private static HttpServletRequest request(String uri, AuthenticatedPrincipal principal) {
        return request("GET", uri, principal);
    }

    private static HttpServletRequest request(String method, String uri, AuthenticatedPrincipal principal) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(request.getAttribute(AuthenticationInterceptor.PRINCIPAL_ATTRIBUTE)).thenReturn(principal);
        return request;
    }
}
