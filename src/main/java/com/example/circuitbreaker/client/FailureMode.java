package com.example.circuitbreaker.client;

public enum FailureMode {
    /** The remote service answers quickly and correctly. */
    HEALTHY,
    /** The remote service throws an error on every call. */
    FAILING,
    /** The remote service answers correctly but takes 2 seconds. */
    SLOW
}
