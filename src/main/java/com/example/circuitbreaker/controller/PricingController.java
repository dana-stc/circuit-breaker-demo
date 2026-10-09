package com.example.circuitbreaker.controller;

import com.example.circuitbreaker.client.FailureMode;
import com.example.circuitbreaker.client.PricingClient;
import com.example.circuitbreaker.model.PriceResponse;
import com.example.circuitbreaker.service.PricingService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class PricingController {

    private final PricingService service;
    private final PricingClient client;
    private final CircuitBreakerRegistry registry;

    public PricingController(PricingService service, PricingClient client, CircuitBreakerRegistry registry) {
        this.service = service;
        this.client = client;
        this.registry = registry;
    }

    /** The protected endpoint. Try it while the remote service is healthy, failing or slow. */
    @GetMapping("/prices/{product}")
    public PriceResponse price(@PathVariable String product) {
        return service.getPrice(product);
    }

    /** Switch the fake remote service: HEALTHY, FAILING or SLOW. */
    @PostMapping("/simulate/{mode}")
    public Map<String, Object> simulate(@PathVariable FailureMode mode) {
        client.setMode(mode);
        return status();
    }

    /** Quick view of the breaker state and its metrics. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        var cb = registry.circuitBreaker("pricing");
        var m = cb.getMetrics();
        return Map.of(
                "remoteMode", client.getMode(),
                "breakerState", cb.getState(),
                "failureRatePercent", m.getFailureRate(),
                "slowCallRatePercent", m.getSlowCallRate(),
                "bufferedCalls", m.getNumberOfBufferedCalls(),
                "failedCalls", m.getNumberOfFailedCalls(),
                "notPermittedCalls", m.getNumberOfNotPermittedCalls(),
                "realRemoteCalls", client.getRealCalls());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(IllegalArgumentException e) {
        return e.getMessage();
    }
}
