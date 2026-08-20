package com.hq.backend.calendar;

import com.hq.backend.calendar.dto.GoogleCalendarSyncEvent;
import com.hq.backend.calendar.dto.GoogleCalendarSyncEventsResponse;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DefaultGoogleCalendarSyncClient implements GoogleCalendarSyncClient {

    private static final String FIELDS =
            "items(id,status,summary,start(dateTime),end(dateTime)),nextPageToken,nextSyncToken";
    private static final long INITIAL_SYNC_WINDOW_SECONDS = 30L * 24 * 60 * 60;

    private final RestClient restClient;
    private final String googleCalendarEventsUrl;

    public DefaultGoogleCalendarSyncClient(
            RestClient restClient,
            @Value("${oauth.google.calendar-events-url}") String googleCalendarEventsUrl) {
        this.restClient = restClient;
        this.googleCalendarEventsUrl = googleCalendarEventsUrl;
    }

    @Override
    public Optional<GoogleSyncBatch> fetchAll(String accessToken, String syncToken, Instant now)
            throws GoogleSyncTokenExpiredException {
        List<GoogleCalendarSyncEvent> events = new ArrayList<>();
        Set<String> seenPageTokens = new HashSet<>();
        String pageToken = null;
        String finalSyncToken = null;

        do {
            GoogleCalendarSyncEventsResponse response;
            try {
                response = restClient.get()
                        .uri(buildUri(syncToken, pageToken, now))
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(GoogleCalendarSyncEventsResponse.class);
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() == 410) {
                    throw new GoogleSyncTokenExpiredException();
                }
                return Optional.empty();
            } catch (RestClientException | IllegalStateException exception) {
                return Optional.empty();
            }

            if (response == null) {
                return Optional.empty();
            }
            if (response.items() != null) {
                events.addAll(response.items());
            }
            finalSyncToken = response.nextSyncToken();
            String nextPageToken = response.nextPageToken();
            if (nextPageToken == null || nextPageToken.isBlank()) {
                break;
            }
            if (!seenPageTokens.add(nextPageToken)) {
                return Optional.empty();
            }
            pageToken = nextPageToken;
        } while (true);

        return Optional.of(new GoogleSyncBatch(List.copyOf(events), finalSyncToken));
    }

    private URI buildUri(String syncToken, String pageToken, Instant now) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(googleCalendarEventsUrl)
                .queryParam("fields", FIELDS);
        Map<String, Object> uriVariables = new HashMap<>();
        if (syncToken == null) {
            builder.queryParam("singleEvents", true)
                    .queryParam("orderBy", "startTime")
                    .queryParam("timeMin", now)
                    .queryParam("timeMax", now.plusSeconds(INITIAL_SYNC_WINDOW_SECONDS));
        } else {
            builder.queryParam("syncToken", "{syncToken}");
            uriVariables.put("syncToken", syncToken);
        }
        if (pageToken != null) {
            builder.queryParam("pageToken", "{pageToken}");
            uriVariables.put("pageToken", pageToken);
        }
        return builder.encode().buildAndExpand(uriVariables).toUri();
    }
}
