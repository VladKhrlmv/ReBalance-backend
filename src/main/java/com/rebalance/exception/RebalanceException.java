package com.rebalance.exception;

import lombok.Getter;

@Getter
public class RebalanceException extends RuntimeException {
    private final RebalanceErrorType errorType;
    /** Optional text appended to the error description (e.g. which split rule failed). */
    private final String extraDetail;

    public RebalanceException(RebalanceErrorType errorType) {
        this(errorType, null);
    }

    public RebalanceException(RebalanceErrorType errorType, String extraDetail) {
        super(errorType.name() + (extraDetail == null ? "" : ": " + extraDetail));
        this.errorType = errorType;
        this.extraDetail = extraDetail;
    }

    public String getDetail() {
        return extraDetail == null ? errorType.getDescription() : errorType.getDescription() + extraDetail;
    }
}
