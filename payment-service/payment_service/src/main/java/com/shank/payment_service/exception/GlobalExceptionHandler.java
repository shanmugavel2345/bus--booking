package com.shank.payment_service.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> downstream(
            org.springframework.web.server.ResponseStatusException exception, HttpServletRequest request) {
        return response(HttpStatus.valueOf(exception.getStatusCode().value()), exception.getReason(), request, null);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(PaymentNotFoundException exception,
                                                       HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception,
                                                         HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> malformed(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Malformed request", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
            return response(status, status.getReasonPhrase(), request, null);
        }
        log.error("Unexpected payment request failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred", request, null);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String message,
                                                        HttpServletRequest request,
                                                        Map<String, String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        if (details != null) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }
}