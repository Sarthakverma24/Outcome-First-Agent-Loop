package com.formi.controller.client;

/**
 * Base class for all simulator API errors.
 *
 * We use checked exceptions here because every caller MUST decide what to do
 * when the API rejects a request — silently swallowing a 409 stale_base_version
 * is a governance bug. Checked exceptions make that choice visible at every call site.
 */
public class ApiException extends Exception {
    private final int httpStatus;
    private final String errorCode;   // the simulator's machine-readable code, e.g. "stale_base_version"
    private final String body;        // raw response body for logging

    public ApiException(int httpStatus, String errorCode, String body) {
        super("HTTP " + httpStatus + " [" + errorCode + "]: " + body);
        this.httpStatus = httpStatus;
        this.errorCode  = errorCode;
        this.body       = body;
    }

    public int    getHttpStatus() { return httpStatus; }
    public String getErrorCode()  { return errorCode; }
    public String getBody()       { return body; }
}
