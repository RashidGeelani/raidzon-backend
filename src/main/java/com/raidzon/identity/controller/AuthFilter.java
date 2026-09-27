package com.raidzon.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.identity.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component @Profile("postgres")
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final ObjectMapper json;
    private static final Set<String> PUBLIC=Set.of("/api/v1/auth/capabilities","/api/v1/auth/challenges","/api/v1/auth/verify","/api/v1/auth/widget");
    public AuthFilter(AuthService auth,ObjectMapper json){this.auth=auth;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI();
        if(!path.startsWith("/api/v1/")){chain.doFilter(request,response);return;}
        response.setHeader("Cache-Control","no-store");
        if(PUBLIC.contains(path)||request.getMethod().equals("OPTIONS") ||
            (request.getMethod().equals("GET") && path.matches("/api/v1/public/scorecards/[0-9a-fA-F-]{36}"))){chain.doFilter(request,response);return;}
        try {
            String header=request.getHeader("Authorization");
            String token=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;
            request.setAttribute("identity",auth.authenticate(token));
            request.setAttribute("authToken",token);
            chain.doFilter(request,response);
        } catch(AuthFailure error) {
            response.setStatus(error.status());response.setContentType("application/json");
            json.writeValue(response.getOutputStream(),Map.of("code",error.code(),"message",error.getMessage()));
        }
    }
}
