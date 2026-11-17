package com.example.alpaca.web;

import com.example.alpaca.broker.BrokerClient.BrokerStreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Relays Alpaca error responses (status + body) to the caller instead of turning them into 500s. */
@RestControllerAdvice
public class AlpacaErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AlpacaErrorHandler.class);

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<String> upstreamError(RestClientResponseException e) {
        log.warn("Alpaca returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
        MediaType type = e.getResponseHeaders() != null && e.getResponseHeaders().getContentType() != null
                ? e.getResponseHeaders().getContentType()
                : MediaType.APPLICATION_JSON;
        return ResponseEntity.status(e.getStatusCode()).contentType(type).body(e.getResponseBodyAsString());
    }

    @ExceptionHandler(BrokerStreamException.class)
    ProblemDetail streamError(BrokerStreamException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail unreachable(ResourceAccessException e) {
        log.warn("Alpaca unreachable", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Alpaca API unreachable: " + e.getMessage());
    }
}
