package com.raidzon.identity.controller;

import com.raidzon.identity.service.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice @Profile("postgres")
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<?> missing(){return response(404,"NOT_FOUND","Route not found.");}
    @ExceptionHandler(AuthFailure.class) ResponseEntity<?> auth(AuthFailure error){return response(error.status(),error.code(),error.getMessage());}
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> forbidden(SecurityException error){return response(403,"FORBIDDEN","This account or scoring device cannot write this match.");}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException error){
        String message=error.getMessage()==null?"Invalid request.":error.getMessage();
        if(message.contains("reused"))return response(409,"ID_REUSED",message);
        if(message.contains("version does not"))return response(409,"VERSION_CONFLICT",message);
        if(message.equals("Match not found."))return response(404,"NOT_FOUND",message);
        return response(422,"INVALID_REQUEST",message);
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(){return response(400,"INVALID_JSON","Request body is invalid.");}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(){return response(500,"SERVER_ERROR","The request could not be completed. Your local history is safe.");}
    private ResponseEntity<?> response(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message));}
}
