package com.smartparking.common.errors;

/** Business error mapped to RFC 9457 Problem Details. {@code code} is a stable machine-readable identifier. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String message) { this(status, message, null); }
    public ApiException(int status, String message, String code) { super(message); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException conflict(String message) { return new ApiException(409, message); }
    public static ApiException notFound() { return new ApiException(404, "Registro no encontrado"); }
}
