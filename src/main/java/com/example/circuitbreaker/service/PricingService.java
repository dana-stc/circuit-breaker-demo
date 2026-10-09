package com.example.circuitbreaker.service;

import com.example.circuitbreaker.client.PricingClient;
import com.example.circuitbreaker.model.PriceResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** Safe value we return when we cannot get a real price. */
    private static final BigDecimal DEFAULT_PRICE = BigDecimal.ZERO;

    private final PricingClient client;

    public PricingService(PricingClient client) {
        this.client = client;
    }

    /**
     * Every call goes through the circuit breaker named "pricing" (see application.yml).
     * If the call fails, or the breaker is OPEN, Spring calls a fallback method instead.
     */
    @CircuitBreaker(name = "pricing", fallbackMethod = "fallback")
    public PriceResponse getPrice(String product) {
        BigDecimal price = client.fetchPrice(product);
        return new PriceResponse(product, price, "REMOTE");
    }

    /**
     * All methods named "fallback" are candidates. Resilience4j picks the one whose
     * exception parameter is the closest match to what was thrown.
     *
     * Fallback for "the breaker is OPEN and blocked the call".
     */
    private PriceResponse fallback(String product, CallNotPermittedException e) {
        log.warn("Circuit is OPEN, remote service not called for '{}'", product);
        return new PriceResponse(product, DEFAULT_PRICE, "FALLBACK_CIRCUIT_OPEN");
    }

    /**
     * Business error (ignored by the breaker). A fallback would hide it, so we rethrow it
     * and the controller turns it into a 404.
     */
    private PriceResponse fallback(String product, IllegalArgumentException e) {
        throw e;
    }

    /** Fallback for "the remote call really failed" (any other exception). */
    private PriceResponse fallback(String product, Throwable t) {
        log.warn("Remote call failed for '{}': {}", product, t.getMessage());
        return new PriceResponse(product, DEFAULT_PRICE, "FALLBACK_ERROR");
    }
}
