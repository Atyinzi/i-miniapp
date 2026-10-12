package com.iminiapp.backend.dining.api;

import com.iminiapp.backend.dining.application.DiningManagementService;
import com.iminiapp.backend.dining.application.DiningReservationService;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.iminiapp.backend.dining.api.DiningModels.*;

@RestController
@RequestMapping("/api/v1/dining/catalog")
public class DiningCatalogController {
    private final DiningManagementService management;
    private final DiningReservationService reservations;

    public DiningCatalogController(DiningManagementService management, DiningReservationService reservations) {
        this.management = management;
        this.reservations = reservations;
    }

    @GetMapping("/stores")
    public ApiResponse<List<StoreResponse>> stores(HttpServletRequest request) {
        return ApiResponse.success(management.stores().stream()
                .filter(item -> "ACTIVE".equals(item.status())).toList(), TraceIds.from(request));
    }

    @GetMapping("/tables")
    public ApiResponse<List<TableResponse>> tables(
            @RequestParam long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
            @RequestParam(defaultValue = "120") int diningMinutes,
            @RequestParam(defaultValue = "1") int guestCount,
            HttpServletRequest request) {
        return ApiResponse.success(reservations.availableTables(storeId, date, time, diningMinutes, guestCount),
                TraceIds.from(request));
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> categories(@RequestParam long storeId, HttpServletRequest request) {
        return ApiResponse.success(management.categories(storeId).stream()
                .filter(item -> "ACTIVE".equals(item.status())).toList(), TraceIds.from(request));
    }

    @GetMapping("/dishes")
    public ApiResponse<List<DishResponse>> dishes(@RequestParam long storeId, HttpServletRequest request) {
        return ApiResponse.success(management.dishes(storeId, "ACTIVE"), TraceIds.from(request));
    }
}
