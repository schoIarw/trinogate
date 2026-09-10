package com.trinogate.flow;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Outcome of {@link FlowController#admit(String)}. */
public class Admission {

    public enum Status {
        ALLOWED,
        RATE_LIMITED,
        QUEUE_FULL,
        QUEUE_TIMEOUT
    }

    private final Status status;
    private final int retryAfterSeconds;
    private final ConcurrencyController.Permit permit;

    private Admission(Status status, int retryAfterSeconds, ConcurrencyController.Permit permit) {
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.permit = permit;
    }

    public static Admission allowed(ConcurrencyController.Permit permit) {
        return new Admission(Status.ALLOWED, 0, permit);
    }

    public static Admission rateLimited(int retryAfterSeconds) {
        return new Admission(Status.RATE_LIMITED, retryAfterSeconds, null);
    }

    public static Admission queueFull() {
        return new Admission(Status.QUEUE_FULL, 5, null);
    }

    public static Admission queueTimeout() {
        return new Admission(Status.QUEUE_TIMEOUT, 1, null);
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    public Status getStatus() {
        return status;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ConcurrencyController.Permit getPermit() {
        return permit;
    }

    @Override
    public String toString() {
        return "Admission{" + status.name().toLowerCase(Locale.ROOT) + ", retryAfter=" + retryAfterSeconds + "s}";
    }
}
