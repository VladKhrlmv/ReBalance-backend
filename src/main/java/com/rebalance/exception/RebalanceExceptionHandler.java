package com.rebalance.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class RebalanceExceptionHandler extends ResponseEntityExceptionHandler {
    public static ProblemDetail problem(RebalanceErrorType errorType, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorType.getStatus(), detail);
        problemDetail.setTitle(errorType.name());
        return problemDetail;
    }

    private static ResponseEntity<Object> response(RebalanceErrorType errorType, String detail) {
        return ResponseEntity.status(errorType.getStatus()).body(problem(errorType, detail));
    }

    @ExceptionHandler(RebalanceException.class)
    ResponseEntity<Object> handleRebalanceException(RebalanceException e) {
        return response(e.getErrorType(), e.getDetail());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return response(RebalanceErrorType.RB_102, RebalanceErrorType.RB_102.getDescription());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return response(RebalanceErrorType.RB_997, RebalanceErrorType.RB_997.getDescription());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException e) {
        String errors = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining(", "));
        return response(RebalanceErrorType.RB_998, RebalanceErrorType.RB_998.getDescription() + errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return response(RebalanceErrorType.RB_998, RebalanceErrorType.RB_998.getDescription() + "invalid value for " + e.getName());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining(", "));
        return response(RebalanceErrorType.RB_998, RebalanceErrorType.RB_998.getDescription() + errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        String errors = ex.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(", "));
        return response(RebalanceErrorType.RB_998, RebalanceErrorType.RB_998.getDescription() + errors);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return response(RebalanceErrorType.RB_999, RebalanceErrorType.RB_999.getDescription());
    }
}
