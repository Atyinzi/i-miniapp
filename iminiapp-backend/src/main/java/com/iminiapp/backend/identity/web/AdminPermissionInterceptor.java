package com.iminiapp.backend.identity.web;

import com.iminiapp.backend.shared.error.BusinessException;
import com.iminiapp.backend.shared.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminPermissionInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/admin/") || uri.equals("/api/v1/admin/auth/login")) return true;

        Object value = request.getAttribute(AuthenticationInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof AuthenticatedPrincipal principal) || !"ADMIN".equals(principal.accountType())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "Only authenticated administrator accounts may use admin APIs");
        }

        String required = requiredPermission(uri, request.getMethod());
        if (required != null && !principal.hasPermission(required)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN,
                    "Current administrator role lacks permission: " + required);
        }
        return true;
    }

    private String requiredPermission(String uri, String method) {
        if (!uri.startsWith("/api/v1/admin/") || uri.startsWith("/api/v1/admin/auth/")) return null;
        if (uri.startsWith("/api/v1/admin/access/")) return null;
        if (uri.startsWith("/api/v1/admin/dashboard")) return "dashboard.view";
        if (uri.startsWith("/api/v1/admin/orders")) {
            return "GET".equalsIgnoreCase(method) ? "orders.view" : "operations.manage";
        }
        if (uri.contains("refund") || uri.contains("after-sales")) return "refunds.review";
        if (uri.contains("verification")) return "verification.use";
        if (uri.startsWith("/api/v1/admin/users")) return "users.manage";
        if (uri.startsWith("/api/v1/admin/vehicle-employees")
                || uri.startsWith("/api/v1/admin/vehicle-departments")
                || uri.startsWith("/api/v1/admin/vehicle-driver-accounts")
                || (uri.startsWith("/api/v1/admin/vehicle-drivers/") && uri.endsWith("/account"))) {
            return "users.manage";
        }
        if (uri.startsWith("/api/v1/admin/admin-accounts")) return "settings.manage";
        if (uri.startsWith("/api/v1/admin/articles") || uri.startsWith("/api/v1/admin/banners")) {
            return "content.manage";
        }
        if (uri.startsWith("/api/v1/admin/settings")) return "settings.manage";
        if (uri.startsWith("/api/v1/admin/audit-logs")) return "settings.manage";
        if (uri.startsWith("/api/v1/admin/gate-devices")) return "gate.manage";
        if (uri.startsWith("/api/v1/admin/notifications")
                && !"GET".equalsIgnoreCase(method)) return "notifications.manage";
        if (uri.startsWith("/api/v1/admin/notifications")) return "notifications.view";
        if (uri.contains("report")) return "reports.view";
        if (isOperationalEndpoint(uri)) return "operations.manage";
        return "catalog.manage";
    }

    private boolean isOperationalEndpoint(String uri) {
        return uri.contains("/operations/")
                || uri.startsWith("/api/v1/admin/hotels/inventory-alerts")
                || uri.startsWith("/api/v1/admin/meetings/approvals")
                || uri.startsWith("/api/v1/admin/meetings/bookings")
                || uri.startsWith("/api/v1/admin/dining/orders")
                || uri.startsWith("/api/v1/admin/dining/kitchen-tickets")
                || uri.startsWith("/api/v1/admin/sports/bookings")
                || uri.startsWith("/api/v1/admin/cultural-store/carriers")
                || uri.startsWith("/api/v1/admin/cultural-store/fulfillment")
                || uri.startsWith("/api/v1/admin/vehicle-applications")
                || uri.startsWith("/api/v1/admin/vehicle-rentals")
                || uri.startsWith("/api/v1/admin/vehicle-rental-dispatches")
                || uri.startsWith("/api/v1/admin/vehicle-dispatches");
    }
}
