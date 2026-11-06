package com.recon.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an {@link ApiError}. The parent class handles Spring's own errors, such
 * as a missing part or an oversized upload, and routes them through handleExceptionInternal.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidFileException.class)
    ResponseEntity<ApiError> handleInvalidFile(InvalidFileException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> handleBadMultipart(MultipartException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "Malformed multipart request", request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // The cause is logged, not returned, so internal details never reach the client.
        log.error("Unhandled error on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(status).headers(headers).body(toApiError(status, message, request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = ex.getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(status).headers(headers).body(toApiError(status, message, request));
    }

    // The two overrides below replace Spring's messages, which expose Java class names.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(status)
                .headers(headers)
                .body(toApiError(status, "Malformed JSON request", request));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String name = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
        return ResponseEntity.status(status)
                .headers(headers)
                .body(toApiError(status, "Invalid value for " + name, request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = ex instanceof ErrorResponse response
                ? response.getBody().getDetail()
                : ex.getMessage();
        return ResponseEntity.status(status).headers(headers).body(toApiError(status, message, request));
    }

    private ApiError toApiError(HttpStatusCode status, String message, WebRequest request) {
        return build(status, message, ((ServletWebRequest) request).getRequest().getRequestURI());
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(build(status, message, path));
    }

    private ApiError build(HttpStatusCode status, String message, String path) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String error = resolved != null ? resolved.getReasonPhrase() : "Error";
        return new ApiError(Instant.now(), status.value(), error, message, path);
    }
}
