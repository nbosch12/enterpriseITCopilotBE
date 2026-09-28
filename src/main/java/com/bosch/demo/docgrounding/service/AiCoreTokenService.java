package com.bosch.demo.docgrounding.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.bosch.demo.docgrounding.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@Slf4j
public class AiCoreTokenService {

    /** Refresh the token 5 minutes before it actually expires. */
    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000L;

    private final WebClient webClient;
    private final AppProperties properties;

    // Simple in-memory cache — thread-safe with volatile semantics via AtomicReference/AtomicLong
    private final AtomicReference<String> cachedToken = new AtomicReference<>();
    private final AtomicLong tokenExpiryMs = new AtomicLong(0);

    public AiCoreTokenService(WebClient.Builder builder, AppProperties properties) {
        this.properties = properties;
        this.webClient = builder.build();
    }

    /**
     * Returns a valid access token, re-using a cached one whenever possible.
     * A new token is fetched only when the cache is empty or nearing expiry.
     */
    public Mono<String> getAccessToken() {
        String token = cachedToken.get();
        if (token != null && System.currentTimeMillis() < tokenExpiryMs.get()) {
            return Mono.just(token);
        }
        log.debug("Fetching new SAP AI Core access token");
        return fetchAndCacheToken();
    }

    private Mono<String> fetchAndCacheToken() {
        AppProperties.SapAiCore ai = properties.getSapAiCore();
        String basic = ai.getClientId() + ":" + ai.getClientSecret();
        return webClient.post()
                .uri(ai.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)))
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(Map.class)
                .flatMap(response -> {
                    if (response == null || response.get("access_token") == null) {
                        return Mono.error(new IllegalStateException(
                                "Could not retrieve SAP AI Core access token"));
                    }
                    String newToken = response.get("access_token").toString();
                    // expires_in is in seconds; default to 1 hour if absent
                    long expiresInMs = response.get("expires_in") != null
                            ? Long.parseLong(response.get("expires_in").toString()) * 1000L
                            : 3600_000L;
                    cachedToken.set(newToken);
                    tokenExpiryMs.set(System.currentTimeMillis() + expiresInMs - REFRESH_BUFFER_MS);
                    log.debug("SAP AI Core token cached, expires in ~{}s",
                            (expiresInMs / 1000) - (REFRESH_BUFFER_MS / 1000));
                    return Mono.just(newToken);
                });
    }

    public String getAccessTokenSync() {
        return getAccessToken().block();
    }
}
