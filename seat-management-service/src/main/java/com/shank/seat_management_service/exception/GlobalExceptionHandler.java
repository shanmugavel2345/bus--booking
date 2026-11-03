package com.shank.seat_management_service.exception;

import java.time.Instant;
import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException failure) {
        return error(failure.getStatusCode().value(), failure.getReason());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<?> invalid(Exception failure) { return error(400, "Invalid request"); }
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    ResponseEntity<?> conflict(Exception failure) { return error(409, "Concurrent inventory change; retry the request"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception failure) {
        log.error("Unexpected seat request failure", failure);
        return error(500, "Unexpected error occurred");
    }
    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now(), "status", status,
                "message", message == null ? "Request failed" : message));
    }
}