package com.vulnflow.shared.error;

import com.vulnflow.shared.exception.InvalidReportException;
import com.vulnflow.shared.exception.ResourceNotFoundException;
import com.vulnflow.shared.exception.UnsupportedReportMediaTypeException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidReportException.class)
    ResponseEntity<ApiError> handleInvalidReport(InvalidReportException exception, HttpServletRequest request) {
        return response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REPORT",
                exception.getMessage(),
                request,
                Map.of());
    }

    @ExceptionHandler(UnsupportedReportMediaTypeException.class)
    ResponseEntity<ApiError> handleUnsupportedReport(
            UnsupportedReportMediaTypeException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_REPORT_MEDIA_TYPE",
                exception.getMessage(),
                request,
                Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUploadSize(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "REPORT_TOO_LARGE",
                "The uploaded report exceeds the configured file size limit",
                request,
                Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                request,
                details);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "A request parameter is missing or invalid",
                request,
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Error inesperado al procesar la petición: método={}, ruta={}",
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                request,
                Map.of());
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> details) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                MDC.get("correlationId"),
                details);
        return ResponseEntity.status(status).body(body);
    }
}

