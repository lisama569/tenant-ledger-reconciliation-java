package com.github.lisama569.tenantledgerreconciliationjava.domain;

/** 业务失败异常，携带可区分的失败码与可读原因。 */
public class LedgerException extends RuntimeException {

    private final FailureCode failureCode;

    public LedgerException(FailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public FailureCode getFailureCode() {
        return failureCode;
    }
}
