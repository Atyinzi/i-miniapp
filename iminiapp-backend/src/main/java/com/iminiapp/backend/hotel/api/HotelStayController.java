package com.iminiapp.backend.hotel.api;

import com.iminiapp.backend.hotel.api.HotelOperationModels.CheckInCredentialResponse;
import com.iminiapp.backend.hotel.application.HotelCheckinCredentialService;
import com.iminiapp.backend.identity.web.CurrentPrincipal;
import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app/hotel/bookings")
public class HotelStayController {
    private final HotelCheckinCredentialService service;
    public HotelStayController(HotelCheckinCredentialService service){this.service=service;}

    @PostMapping("/{reservationId}/check-in-code")
    public ApiResponse<CheckInCredentialResponse> issue(@PathVariable long reservationId,
                                                        HttpServletRequest request){
        return ApiResponse.success("住宿入住码生成成功",
                service.issue(CurrentPrincipal.from(request),reservationId),TraceIds.from(request));
    }
}
