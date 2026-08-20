package com.hq.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class GoogleCalendarSyncClientTest {

    private static final String EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    private static final String SYNC_FIELDS =
            "items(id,status,summary,start(dateTime),end(dateTime)),nextPageToken,nextSyncToken";

    @Test
    void 증분_동기화는_모든_페이지를_수집하고_원래_syncToken과_pageToken을_함께_보낸다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        String syncToken = "sync+token=/opaque";
        String pageToken = "page+token=/opaque";

        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertSyncRequest(
                        request.getURI(), syncToken, null, false, now))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .contains("syncToken=sync%2Btoken%3D%2Fopaque"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"one","status":"confirmed","summary":"first",\
                        "start":{"dateTime":"2026-08-20T01:00:00Z"},\
                        "end":{"dateTime":"2026-08-20T02:00:00Z"}}],\
                        "nextPageToken":"page+token=/opaque","nextSyncToken":"discard-me"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertSyncRequest(
                        request.getURI(), syncToken, pageToken, false, now))
                .andExpect(request -> assertThat(request.getURI().getRawQuery())
                        .contains("syncToken=sync%2Btoken%3D%2Fopaque", "pageToken=page%2Btoken%3D%2Fopaque"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"two","status":"confirmed","summary":"second",\
                        "start":{"dateTime":"2026-08-20T03:00:00Z"},\
                        "end":{"dateTime":"2026-08-20T04:00:00Z"}}],\
                        "nextSyncToken":"final-sync-token"}
                        """, MediaType.APPLICATION_JSON));

        var result = client(builder).fetchAll("access-token-value", syncToken, now);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().events()).extracting(event -> event.id())
                .containsExactly("one", "two");
        assertThat(result.orElseThrow().nextSyncToken()).isEqualTo("final-sync-token");
        server.verify();
    }

    @Test
    void 초기_동기화는_30일_범위와_startTime_정렬을_보낸다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Instant now = Instant.parse("2026-08-20T00:00:00Z");

        server.expect(method(HttpMethod.GET))
                .andExpect(request -> assertSyncRequest(request.getURI(), null, null, true, now))
                .andRespond(withSuccess("{\"items\":[],\"nextSyncToken\":\"initial-sync-token\"}",
                        MediaType.APPLICATION_JSON));

        var result = client(builder).fetchAll("access-token-value", null, now);

        assertThat(result).contains(new GoogleSyncBatch(java.util.List.of(), "initial-sync-token"));
        server.verify();
    }

    @Test
    void http410만_만료된_syncToken으로_구분하고_다른_HTTP_실패는_조용히_비어_반환한다() {
        RestClient.Builder goneBuilder = RestClient.builder();
        MockRestServiceServer goneServer = MockRestServiceServer.bindTo(goneBuilder).build();
        goneServer.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.GONE));

        assertThatThrownBy(() -> client(goneBuilder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z")))
                .isInstanceOf(GoogleSyncTokenExpiredException.class);
        goneServer.verify();

        RestClient.Builder failedBuilder = RestClient.builder();
        MockRestServiceServer failedServer = MockRestServiceServer.bindTo(failedBuilder).build();
        failedServer.expect(method(HttpMethod.GET)).andRespond(withServerError());

        assertThat(client(failedBuilder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z"))).isEmpty();
        failedServer.verify();
    }

    @Test
    void 역직렬화_실패와_4xx는_동기화_실패로만_처리한다() {
        RestClient.Builder unauthorizedBuilder = RestClient.builder();
        MockRestServiceServer unauthorizedServer = MockRestServiceServer.bindTo(unauthorizedBuilder).build();
        unauthorizedServer.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client(unauthorizedBuilder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z"))).isEmpty();
        unauthorizedServer.verify();

        RestClient.Builder malformedBuilder = RestClient.builder();
        MockRestServiceServer malformedServer = MockRestServiceServer.bindTo(malformedBuilder).build();
        malformedServer.expect(method(HttpMethod.GET)).andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThat(client(malformedBuilder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z"))).isEmpty();
        malformedServer.verify();
    }

    @Test
    void 빈_nextPageToken은_추가_요청_없이_정상_종료한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"items\":[],\"nextPageToken\":\"\",\"nextSyncToken\":\"final\"}",
                MediaType.APPLICATION_JSON));

        var result = client(builder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z"));

        assertThat(result).contains(new GoogleSyncBatch(java.util.List.of(), "final"));
        server.verify();
    }

    @Test
    void 반복된_nextPageToken은_추가_요청_없이_실패로_종료한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"items\":[],\"nextPageToken\":\"repeat\"}", MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"items\":[],\"nextPageToken\":\"repeat\"}", MediaType.APPLICATION_JSON));

        var result = client(builder).fetchAll(
                "access-token-value", "saved-sync-token", Instant.parse("2026-08-20T00:00:00Z"));

        assertThat(result).isEmpty();
        server.verify();
    }

    private DefaultGoogleCalendarSyncClient client(RestClient.Builder builder) {
        return new DefaultGoogleCalendarSyncClient(builder.build(), EVENTS_URL);
    }

    private void assertSyncRequest(
            java.net.URI uri, String syncToken, String pageToken, boolean initial, Instant now) {
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        assertThat(decoded(query.getFirst("fields"))).isEqualTo(SYNC_FIELDS);
        assertThat(decoded(query.getFirst("syncToken"))).isEqualTo(syncToken);
        assertThat(decoded(query.getFirst("pageToken"))).isEqualTo(pageToken);
        if (initial) {
            assertThat(decoded(query.getFirst("singleEvents"))).isEqualTo("true");
            assertThat(decoded(query.getFirst("orderBy"))).isEqualTo("startTime");
            assertThat(decoded(query.getFirst("timeMin"))).isEqualTo(now.toString());
            assertThat(decoded(query.getFirst("timeMax"))).isEqualTo(now.plusSeconds(30L * 24 * 60 * 60).toString());
        } else {
            assertThat(query).doesNotContainKeys("singleEvents", "orderBy", "timeMin", "timeMax");
        }
    }

    private String decoded(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
