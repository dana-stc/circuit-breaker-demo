package com.example.circuitbreaker.model;

import java.math.BigDecimal;

/**
 * @param source where the answer came from:
 *               REMOTE (normal), FALLBACK_ERROR (call failed) or FALLBACK_CIRCUIT_OPEN (call was blocked)
 */
public record PriceResponse(String product, BigDecimal price, String source) {
}
