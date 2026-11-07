package com.lailo.application.common.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps every error to RFC 9457 {@link ProblemDetail} with a stable {@code code} property. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Maps a business error to a problem response carrying its status, {@code code} and details.
     *
     * @param ex the business error
     * @return RFC 9457 problem detail
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        pd.setProperty("code", ex.getCode());
        ex.getDetails().forEach(pd::setProperty);
        return pd;
    }

    /**
     * Maps Bean Validation failures to a 400 with {@code code=VALIDATION_FAILED} and an {@code errors} map of
     * field name to the first message for that field.
     *
     * @param ex the binding exception raised for an invalid request body
     * @return RFC 9457 problem detail
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidation(WebExchangeBindException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Check the highlighted fields.");
        pd.setProperty("code", "VALIDATION_FAILED");
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Maps an unreadable request (bad JSON, wrong types) to a 400 with {@code code=BAD_REQUEST}.
     *
     * @param ex the input exception
     * @return RFC 9457 problem detail
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ProblemDetail handleBadInput(ServerWebInputException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request.");
        pd.setProperty("code", "BAD_REQUEST");
        return pd;
    }

    /**
     * Last-resort handler: logs the exception and returns a generic 500 that reveals no internals.
     *
     * @param ex any unhandled exception
     * @return RFC 9457 problem detail with {@code code=INTERNAL_ERROR}
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Try again in a moment.");
        pd.setProperty("code", "INTERNAL_ERROR");
        return pd;
    }
}
