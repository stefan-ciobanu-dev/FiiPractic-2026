package com.fiipractic.stocks.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException ex) {
        // Log error to LogBull using MDC
        try {
            MDC.put("action", "user_not_found");
            MDC.put("errorType", "UserNotFoundException");
            MDC.put("httpStatus", String.valueOf(HttpStatus.NOT_FOUND.value()));
            log.error("User not found: {}", ex.getMessage());
        } finally {
            MDC.clear();
        }
        
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(PortfolioNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePortfolioNotFoundException(PortfolioNotFoundException ex) {
        // Log error to LogBull using MDC
        try {
            MDC.put("action", "portfolio_not_found");
            MDC.put("errorType", "PortfolioNotFoundException");
            MDC.put("httpStatus", String.valueOf(HttpStatus.NOT_FOUND.value()));
            log.error("Portfolio not found: {}", ex.getMessage());
        } finally {
            MDC.clear();
        }
        
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(DuplicateUserException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateUserException(DuplicateUserException ex) {
        // Log error to LogBull using MDC
        try {
            MDC.put("action", "duplicate_user");
            MDC.put("errorType", "DuplicateUserException");
            MDC.put("httpStatus", String.valueOf(HttpStatus.CONFLICT.value()));
            log.error("Duplicate user: {}", ex.getMessage());
        } finally {
            MDC.clear();
        }
        
        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        // Log validation error to LogBull using MDC
        try {
            MDC.put("action", "validation_failed");
            MDC.put("errorType", "ValidationException");
            MDC.put("httpStatus", String.valueOf(HttpStatus.BAD_REQUEST.value()));
            MDC.put("fieldCount", String.valueOf(errors.size()));
            log.error("Validation failed with {} errors", errors.size());
        } finally {
            MDC.clear();
        }

        ValidationErrorResponse response = new ValidationErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation failed",
                LocalDateTime.now(),
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        // Log unexpected errors to LogBull using MDC
        try {
            MDC.put("action", "unexpected_error");
            MDC.put("errorType", ex.getClass().getSimpleName());
            MDC.put("httpStatus", String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value()));
            log.error("Unexpected error", ex);
        } finally {
            MDC.clear();
        }
        
        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // records for error and validation responses

    public record ErrorResponse(int status, String message, LocalDateTime timestamp) {}

    public record ValidationErrorResponse(
            int status,
            String message,
            LocalDateTime timestamp,
            Map<String, String> errors
    ) {}
}
