package com.iminiapp.backend.vehicle.api;

import com.iminiapp.backend.shared.api.ApiResponse;
import com.iminiapp.backend.shared.web.TraceIds;
import com.iminiapp.backend.vehicle.application.VehicleReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/v1/admin/vehicle-reports")
public class AdminVehicleReportController {
    private final VehicleReportService service; public AdminVehicleReportController(VehicleReportService service){this.service=service;}
    @GetMapping("/overview") public ApiResponse<VehicleReportOverviewResponse> overview(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,HttpServletRequest request){return ApiResponse.success(service.overview(startDate,endDate),TraceIds.from(request));}
    @GetMapping(value="/export",produces="text/csv") public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=vehicle-report.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(service.csv(startDate,endDate));}
    @GetMapping(value="/print",produces=MediaType.TEXT_HTML_VALUE) public ResponseEntity<String> print(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate){return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(service.printableHtml(startDate,endDate));}
}
