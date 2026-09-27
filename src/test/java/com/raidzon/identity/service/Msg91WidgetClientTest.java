package com.raidzon.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Msg91WidgetClientTest {
    final HttpClient http = mock(HttpClient.class);
    @SuppressWarnings("unchecked") final HttpResponse<String> response = mock(HttpResponse.class);
    final Msg91WidgetClient client = new Msg91WidgetClient("server-test-key", new ObjectMapper(), http);

    @Test void usesFixedServerVerificationEndpointWithoutSecretsInUrl() throws Exception {
        when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"type\":\"success\"}");
        assertEquals("success", client.verifyAccessToken("widget-test-token").path("type").asText());
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), any());
        var request = captured.getValue();
        assertEquals("https://control.msg91.com/api/v5/widget/verifyAccessToken", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(10, request.timeout().orElseThrow().toSeconds());
    }

    @Test void rejectsFailureMalformedAndUnavailableResponsesWithoutLeakingDetails() throws Exception {
        when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        for (String body : new String[]{"{\"type\":\"error\",\"message\":\"private data\"}", "{}", "null", "not-json"}) {
            when(response.body()).thenReturn(body);
            var failure = assertThrows(AuthFailure.class, () -> client.verifyAccessToken("widget-test-token"));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("private data"));
        }
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("{\"type\":\"success\"}");
        assertEquals(503, assertThrows(AuthFailure.class, () -> client.verifyAccessToken("widget-test-token")).status());
    }

    @Test void readsPhoneOnlyFromSuccessfulProviderResponse() throws Exception {
        when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        for (String phone : new String[]{"919876543210", "+919876543210"}) {
            when(response.body()).thenReturn("{\"type\":\"success\",\"message\":\"" + phone + "\"}");
            assertEquals("+919876543210", client.verifyPhone("widget-test-token"));
        }
        for (String message : new String[]{"null", "1234567890", "{}", "\"person@example.com\"", "\"success\"", "\"+0123456789\"", "\"+919876543210 trailing\""}) {
            when(response.body()).thenReturn("{\"type\":\"success\",\"message\":" + message + "}");
            assertEquals(401, assertThrows(AuthFailure.class, () -> client.verifyPhone("widget-test-token")).status());
        }
        when(response.body()).thenReturn("{\"type\":\"error\",\"message\":\"919876543210\"}");
        assertThrows(AuthFailure.class, () -> client.verifyPhone("widget-test-token"));
    }

    @Test void rejectsMissingConfigurationAndInvalidInputWithoutNetworkCalls() {
        assertThrows(IllegalStateException.class, () -> new Msg91WidgetClient("", new ObjectMapper()));
        assertThrows(AuthFailure.class, () -> client.verifyAccessToken(null));
        assertThrows(AuthFailure.class, () -> client.verifyAccessToken(" "));
        assertThrows(AuthFailure.class, () -> client.verifyAccessToken("x".repeat(16385)));
        verifyNoInteractions(http);
    }
}
