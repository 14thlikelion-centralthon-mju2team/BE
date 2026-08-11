package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.CalendarConnectionResponse;
import com.hq.backend.calendar.dto.ConnectCalendarRequest;
import com.hq.backend.common.auth.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/calendar/google")
@RequiredArgsConstructor
public class CalendarController {

    private final CalendarService calendarService;

    @PostMapping("/connect")
    public CalendarConnectionResponse connect(
            @CurrentUserId UUID userId, @Valid @RequestBody ConnectCalendarRequest request) {
        return calendarService.connect(userId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@CurrentUserId UUID userId) {
        calendarService.disconnect(userId);
    }
}
