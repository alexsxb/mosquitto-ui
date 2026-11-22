package com.dfc.mosquittoui.certificate;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The answers to HTTP-01 challenges while an order runs; the CA fetches them from /.well-known/acme-challenge/{token}. */
@ApplicationScoped
public class Http01Tokens {

    private final Map<String, String> answers = new ConcurrentHashMap<>();

    void put(String token, String authorization) {
        answers.put(token, authorization);
    }

    void remove(String token) {
        answers.remove(token);
    }

    /** @return null for a token that belongs to no running order */
    public String answer(String token) {
        return answers.get(token);
    }
}
