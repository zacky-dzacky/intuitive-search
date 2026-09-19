package com.bank.intuitivesearch.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationError(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        log.error("Unhandled error serving search request", e);
        // Deliberately opaque: a search endpoint should never leak schema,
        // SQL, or customer data through an error body.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "search temporarily unavailable"));
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", Instant.now().toString());
        out.put("status", status.value());
        out.put("error", status.getReasonPhrase());
        out.put("message", message);
        return out;
    }
}
