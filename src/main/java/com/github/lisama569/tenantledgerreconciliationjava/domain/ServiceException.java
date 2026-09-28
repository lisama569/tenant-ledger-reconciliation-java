package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 业务异常，携带可区分的失败原因码。 */
public class ServiceException extends RuntimeException {
    private final FailureReason reason;

    public ServiceException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason getReason() {
        return reason;
    }
}
