package com.example.circuitbreaker.client;

/** Thrown when the remote service fails. Listed in recordExceptions, so it counts as a failure. */
public class RemoteServiceException extends RuntimeException {

    public RemoteServiceException(String message) {
        super(message);
    }
}
