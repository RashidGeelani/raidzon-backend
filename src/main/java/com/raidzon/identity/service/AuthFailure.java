package com.raidzon.identity.service;

public final class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;
    public AuthFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
