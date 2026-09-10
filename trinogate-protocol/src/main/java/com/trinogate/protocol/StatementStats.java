package com.trinogate.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Minimal {@code StatementStats}; unknown fields from the backend are dropped. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatementStats {

    public String state;
    public Long queuedTimeMillis;
    public Long elapsedTimeMillis;
    public Long cpuTimeMillis;
    public Long wallTimeMillis;
    public Long processedRows;
    public Long processedBytes;
    public Long physicalInputBytes;
    public Integer runningTasks;
    public Integer totalTasks;

    public static StatementStats of(String state) {
        StatementStats s = new StatementStats();
        s.state = state;
        return s;
    }
}
