package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.CalendarConnectionStatusResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.calendar.dto.DensityResponse;
import com.hq.backend.common.auth.CurrentUserId;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;
    private final CalendarSyncService calendarSyncService;

    @PostMapping("/calendar/google/connect")
    public CalendarConnectionResponse connect(
            @CurrentUserId UUID userId, @Valid @RequestBody ConnectCalendarRequest request) {
        return calendarService.connect(userId, request);
    }

    @DeleteMapping("/calendar/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@CurrentUserId UUID userId) {
        calendarService.disconnect(userId);
    }

    @GetMapping("/calendar/google/status")
    public CalendarConnectionStatusResponse googleConnectionStatus(@CurrentUserId UUID userId) {
        return calendarService.getGoogleConnectionStatus(userId);
    }

    /** Manual sync intentionally delegates to the no-AI CalendarSyncService entry point. */
    @PostMapping("/calendar/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sync(@CurrentUserId UUID userId) {
        calendarSyncService.syncForUser(userId);
    }

    @GetMapping("/calendar/density")
    public DensityResponse density(
            @CurrentUserId UUID userId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return calendarService.getDensity(userId, date);
    }
}
