package com.edu.submission.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.title(), exception.getMessage(),
                request, exception.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<FieldErrorItem> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败",
                "请检查表单字段后重试。", request, fields);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception,
                                                            HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行该操作",
                "当前账号没有访问此资源的权限。", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用",
                "请求处理失败，请稍后重试并联系管理员。", request, List.of());
    }

    private FieldErrorItem fieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage();
        return new FieldErrorItem(error.getField(), "INVALID_FIELD", message);
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, String code, String title,
                                                   String detail, HttpServletRequest request,
                                                   List<FieldErrorItem> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://errors.edu-submission.local/" + code.toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", TraceIds.current());
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}

