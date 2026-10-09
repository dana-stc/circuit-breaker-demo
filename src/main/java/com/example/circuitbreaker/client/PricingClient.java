package com.example.circuitbreaker.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a remote "pricing" service.
 * In a real project this would be a RestClient / WebClient / Feign call.
 * The behaviour can be switched at runtime through {@link #setMode(FailureMode)}.
 */
@Component
public class PricingClient {

    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "laptop", new BigDecimal("999.99"),
            "phone", new BigDecimal("599.00"),
            "headphones", new BigDecimal("79.90"));

    private volatile FailureMode mode = FailureMode.HEALTHY;
    private final AtomicInteger realCalls = new AtomicInteger();

    public BigDecimal fetchPrice(String product) {
        int n = realCalls.incrementAndGet();
        log.info("Remote call #{} for '{}' (mode={})", n, product, mode);

        switch (mode) {
            case FAILING -> throw new RemoteServiceException("Pricing service is down");
            case SLOW -> sleep(2000);
            case HEALTHY -> { /* answer immediately */ }
        }

        BigDecimal price = PRICES.get(product.toLowerCase());
        if (price == null) {
            // A business error, NOT an outage. Configured in ignoreExceptions.
            throw new IllegalArgumentException("Unknown product: " + product);
        }
        return price;
    }

    public FailureMode getMode() {
        return mode;
    }

    public void setMode(FailureMode mode) {
        this.mode = mode;
    }

    /** How many times the remote service was really called (calls blocked by the breaker are not counted). */
    public int getRealCalls() {
        return realCalls.get();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
