package com.raidzon.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Msg91SmsSenderTest {
    final HttpClient client=mock(HttpClient.class);
    @SuppressWarnings("unchecked") final HttpResponse<String> response=mock(HttpResponse.class);
    Msg91SmsSender sender(){return new Msg91SmsSender("test-key","template123",new ObjectMapper(),client,URI.create("https://control.msg91.com/api/v5/otp"));}
    @Test void sendsCustomSixDigitOtpWithoutPuttingAuthKeyInUrl() throws Exception {
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"type\":\"success\",\"message\":\"request-id\"}");
        sender().sendCode("+919876543210","012345");
        var captured=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(captured.capture(),any());
        var request=captured.getValue();
        assertEquals("test-key",request.headers().firstValue("authkey").orElseThrow());
        assertFalse(request.uri().toString().contains("test-key"));assertEquals("POST",request.method());
        assertTrue(request.uri().getQuery().contains("mobile=919876543210"));
        assertTrue(request.uri().getQuery().contains("otp=012345"));assertTrue(request.uri().getQuery().contains("otp_expiry=5"));
        assertEquals(10,request.timeout().orElseThrow().toSeconds());
    }
    @Test void providerErrorsAndMalformedResponsesAreSanitized() throws Exception {
        when(client.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        for(String body:new String[]{"{\"type\":\"error\",\"message\":\"sensitive\"}","not-json","null"}){
            when(response.body()).thenReturn(body);
            var error=assertThrows(IllegalStateException.class,()->sender().sendCode("+919876543210","012345"));
            assertNull(error.getCause());assertFalse(error.getMessage().contains("012345"));assertFalse(error.getMessage().contains("sensitive"));
        }
        when(response.statusCode()).thenReturn(401);when(response.body()).thenReturn("{\"type\":\"success\"}");
        assertThrows(IllegalStateException.class,()->sender().sendCode("+919876543210","012345"));
        when(client.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new java.io.IOException("sensitive URL"));
        assertNull(assertThrows(IllegalStateException.class,()->sender().sendCode("+919876543210","012345")).getCause());
    }
    @Test void incompleteConfigurationFailsClosed() {
        assertThrows(IllegalStateException.class,()->new Msg91SmsSender("","template123",new ObjectMapper()));
        assertThrows(IllegalStateException.class,()->new Msg91SmsSender("key","",new ObjectMapper()));
    }
}
