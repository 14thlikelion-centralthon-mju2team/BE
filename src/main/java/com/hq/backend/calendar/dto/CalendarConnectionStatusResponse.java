package com.hq.backend.calendar.dto;

/** Minimal connection state consumed by the calendar sync UI. */
public record CalendarConnectionStatusResponse(boolean connected) {
}
