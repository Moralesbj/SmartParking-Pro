package com.smartparking.common.errors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every error leaves as application/problem+json (RFC 9457) without stack traces or SQL details. */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail business(ApiException ex) {
        var p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status()), ex.getMessage());
        if (ex.code() != null) p.setProperty("code", ex.code());
        return p;
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "El registro está duplicado, tiene referencias o entra en conflicto con otra operación"); }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail locking() { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Otra operación concurrente modificó el registro; intente de nuevo"); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ProblemDetail invalid(Exception ex) { return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Datos inválidos: revise los campos y las fechas"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Revise los campos indicados");
        problem.setProperty("fields", ex.getBindingResult().getFieldErrors().stream().map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return problem;
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missing() { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Ruta no encontrada"); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ProblemDetail method() { return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ProblemDetail denied() { return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Permiso denegado"); }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno. Si persiste, contacte a soporte con la hora del evento");
    }
}
