package me.niteshh.nexoraflow.common.exception;

import org.springframework.http.HttpStatus;

import me.niteshh.nexoraflow.common.ReasonCode;

/**
 * Domain exception carrying the HTTP status and the machine-readable reason code the API reports.
 * Messages are written for API consumers and never contain internals such as stack traces or SQL.
 */
public class NexoraException extends RuntimeException {

    private final HttpStatus status;
    private final ReasonCode reasonCode;

    public NexoraException(HttpStatus status, ReasonCode reasonCode, String message) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public HttpStatus status() {
        return status;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    public static NexoraException notFound(String entity, String id) {
        return new NexoraException(HttpStatus.NOT_FOUND, null, entity + " '" + id + "' was not found");
    }

    public static NexoraException badRequest(String message) {
        return new NexoraException(HttpStatus.BAD_REQUEST, null, message);
    }

    public static NexoraException unprocessable(ReasonCode code, String message) {
        return new NexoraException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static NexoraException conflict(ReasonCode code, String message) {
        return new NexoraException(HttpStatus.CONFLICT, code, message);
    }

    public static NexoraException forbidden(ReasonCode code, String message) {
        return new NexoraException(HttpStatus.FORBIDDEN, code, message);
    }
}
