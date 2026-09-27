package com.raidzon.identity.service;

/** Provider adapter. Never log codes or return them through the public API. */
public interface SmsSender {
    boolean available();
    void sendCode(String phone, String code);
}
