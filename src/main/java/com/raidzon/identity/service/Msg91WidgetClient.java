package com.raidzon.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Verifies tokens with MSG91 before reading the provider-returned phone identity. */
public final class Msg91WidgetClient {
    private static final URI ENDPOINT = URI.create("https://control.msg91.com/api/v5/widget/verifyAccessToken");
    private final String authKey;
    private final ObjectMapper json;
    private final HttpClient client;

    public Msg91WidgetClient(String authKey, ObjectMapper json) {
        this(authKey, json, configuredClient(authKey));
    }

    Msg91WidgetClient(String authKey, ObjectMapper json, HttpClient client) {
        validateKey(authKey);
        this.authKey = authKey;
        this.json = json;
        this.client = client;
    }

    private static void validateKey(String authKey) {
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalStateException("Configure the MSG91 server AuthKey before verifying widget tokens.");
        }
    }

    private static HttpClient configuredClient(String authKey) {
        validateKey(authKey);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String verifyPhone(String accessToken) {
        var result = verifyAccessToken(accessToken);
        var message = result.path("message");
        if (!message.isTextual() || !message.textValue().matches("\\+?[1-9][0-9]{7,14}")) {
            throw new AuthFailure(401, "INVALID_WIDGET_IDENTITY", "Verification did not return a valid phone number.");
        }
        String phone = message.textValue();
        return phone.startsWith("+") ? phone : "+" + phone;
    }

    public JsonNode verifyAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 16384) {
            throw new AuthFailure(400, "INVALID_WIDGET_TOKEN", "A valid widget verification token is required.");
        }
        try {
            var body = json.writeValueAsString(Map.of("authkey", authKey, "access-token", accessToken));
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AuthFailure(503, "WIDGET_VERIFICATION_UNAVAILABLE", "Phone verification is temporarily unavailable.");
            }
            var result = json.readTree(response.body());
            if (result == null || !result.isObject() || !"success".equals(result.path("type").asText())) {
                throw new AuthFailure(401, "INVALID_WIDGET_TOKEN", "Phone verification failed. Please start again.");
            }
            return result;
        } catch (AuthFailure failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AuthFailure(503, "WIDGET_VERIFICATION_UNAVAILABLE", "Phone verification was interrupted.");
        } catch (Exception failure) {
            // Never propagate provider messages, payloads, credentials or HTTP exception causes.
            throw new AuthFailure(503, "WIDGET_VERIFICATION_UNAVAILABLE", "Phone verification is temporarily unavailable.");
        }
    }
}
