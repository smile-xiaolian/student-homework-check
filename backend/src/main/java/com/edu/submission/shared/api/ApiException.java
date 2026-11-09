package com.edu.submission.shared.api;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldErrorItem> fieldErrors;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String title, String detail,
                        List<FieldErrorItem> fieldErrors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiException badRequest(String code, String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, title, detail);
    }

    public static ApiException unauthorized(String code, String title, String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, title, detail);
    }

    public static ApiException forbidden(String code, String title, String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, code, title, detail);
    }

    public static ApiException notFound(String code, String title, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, title, detail);
    }

    public static ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    public static ApiException unprocessable(String code, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public List<FieldErrorItem> fieldErrors() {
        return fieldErrors;
    }
}

