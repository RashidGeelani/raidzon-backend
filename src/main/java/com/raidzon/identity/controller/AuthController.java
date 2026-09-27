package com.raidzon.identity.controller;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
import com.raidzon.identity.service.Msg91WidgetClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

@RestController @Profile("postgres") @RequestMapping("/api/v1/auth")
public class AuthController {
    public record ChallengeInput(String phone,UUID deviceId,String deviceSecret) {}
    public record VerifyInput(UUID challengeId,String code,UUID deviceId,String deviceSecret) {}
    private final AuthService auth;
    private final Msg91WidgetClient widget;
    public AuthController(AuthService auth, ObjectMapper json,
            @Value("${raidzon.auth.widget-enabled:false}") boolean enabled,
            @Value("${raidzon.sms.msg91.auth-key:}") String key,
            @Value("${raidzon.auth.pepper:}") String pepper){
        this.auth=auth;
        if(enabled && pepper.length()<32) throw new IllegalStateException("Configure an authentication pepper before enabling widget sign-in.");
        this.widget=enabled?new Msg91WidgetClient(key,json):null;
    }
    @GetMapping("/capabilities") public Map<String,Boolean> capabilities(){return Map.of("smsAvailable",auth.available(),"widgetAvailable",widget!=null);}
    public record WidgetInput(String accessToken,UUID deviceId,String deviceSecret) {}
    @PostMapping("/widget") public AuthService.Session widget(@RequestBody WidgetInput input,HttpServletRequest request){
        return auth.verifyWidget(input.accessToken(),input.deviceId(),input.deviceSecret(),request.getRemoteAddr(),widget);
    }
    @PostMapping("/challenges") public AuthService.Challenge challenge(@RequestBody ChallengeInput input,HttpServletRequest request){
        return auth.request(input.phone(),input.deviceId(),input.deviceSecret(),request.getRemoteAddr());
    }
    @PostMapping("/verify") public AuthService.Session verify(@RequestBody VerifyInput input){
        return auth.verify(input.challengeId(),input.code(),input.deviceId(),input.deviceSecret());
    }
    @GetMapping("/me") public AuthIdentity me(@RequestAttribute("identity") AuthIdentity identity){return identity;}
    @PostMapping("/logout") public Map<String,Boolean> logout(@RequestAttribute("authToken") String token){auth.logout(token);return Map.of("signedOut",true);}
}
