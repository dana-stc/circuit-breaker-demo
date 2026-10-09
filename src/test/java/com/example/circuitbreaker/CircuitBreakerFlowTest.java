package com.example.circuitbreaker;

import com.example.circuitbreaker.client.FailureMode;
import com.example.circuitbreaker.client.PricingClient;
import com.example.circuitbreaker.model.PriceResponse;
import com.example.circuitbreaker.service.PricingService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CircuitBreakerFlowTest {

    @Autowired PricingService service;
    @Autowired PricingClient client;
    @Autowired CircuitBreakerRegistry registry;

    CircuitBreaker breaker;

    @BeforeEach
    void reset() {
        breaker = registry.circuitBreaker("pricing");
        breaker.reset();
        client.setMode(FailureMode.HEALTHY);
    }

    @Test
    void closedBreakerReturnsRemoteAnswer() {
        PriceResponse r = service.getPrice("laptop");

        assertThat(r.source()).isEqualTo("REMOTE");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void breakerOpensAfterFailuresThenRecovers() {
        client.setMode(FailureMode.FAILING);

        // 5 failed calls (= minimumNumberOfCalls) -> failure rate 100% -> OPEN
        for (int i = 0; i < 5; i++) {
            assertThat(service.getPrice("laptop").source()).isEqualTo("FALLBACK_ERROR");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN the remote service is not touched any more
        int callsBefore = client.getRealCalls();
        assertThat(service.getPrice("laptop").source()).isEqualTo("FALLBACK_CIRCUIT_OPEN");
        assertThat(client.getRealCalls()).isEqualTo(callsBefore);

        // The service gets fixed; breaker moves to HALF_OPEN (we skip the 10s wait)
        client.setMode(FailureMode.HEALTHY);
        breaker.transitionToHalfOpenState();

        // 3 successful trial calls -> CLOSED again
        for (int i = 0; i < 3; i++) {
            assertThat(service.getPrice("laptop").source()).isEqualTo("REMOTE");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void businessErrorsDoNotOpenTheBreaker() {
        for (int i = 0; i < 10; i++) {
            try {
                service.getPrice("unknown-product");
            } catch (IllegalArgumentException expected) {
                // the fallback rethrows business errors
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
