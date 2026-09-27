package com.raidzon.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Server-only SendOTP adapter. Exceptions deliberately exclude provider payloads and credentials. */
public final class Msg91SmsSender implements SmsSender {
    private final HttpClient client;
    private final ObjectMapper json;
    private final String authKey;
    private final String templateId;
    private final URI endpoint;

    public Msg91SmsSender(String authKey, String templateId, ObjectMapper json) {
        this(authKey, templateId, json, configuredClient(authKey,templateId), URI.create("https://control.msg91.com/api/v5/otp"));
    }

    private static HttpClient configuredClient(String key,String template) {
        validateConfiguration(key,template);
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static void validateConfiguration(String key,String template) {
        if (key == null || key.isBlank() || template == null || !template.matches("[A-Za-z0-9]+"))
            throw new IllegalStateException("MSG91 requires an auth key and an approved template ID.");
    }

    // Package-private transport injection permits local HTTP tests without sending SMS.
    Msg91SmsSender(String authKey, String templateId, ObjectMapper json, HttpClient client, URI endpoint) {
        validateConfiguration(authKey,templateId);
        this.authKey = authKey; this.templateId = templateId; this.json = json;
        this.client = client; this.endpoint = endpoint;
    }

    @Override public boolean available() { return true; }

    @Override public void sendCode(String phone, String code) {
        if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}") || code == null || !code.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Invalid SMS destination or code.");
        // SendOTP accepts a caller-generated OTP. Verification remains device-bound in AuthService.
        var uri = URI.create(endpoint + "?template_id=" + templateId + "&mobile=" + phone.substring(1)
                + "&otp=" + code + "&otp_length=6&otp_expiry=5");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("authkey", authKey).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !"success".equals(json.readTree(response.body()).path("type").asText()))
                throw new IllegalStateException("SMS provider rejected the request.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMS request interrupted.");
        } catch (Exception error) {
            // Do not attach causes: HTTP errors can contain the URL, OTP or provider response.
            throw new IllegalStateException("SMS provider could not accept the request.");
        }
    }
}
