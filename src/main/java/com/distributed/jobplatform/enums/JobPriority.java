package com.distributed.jobplatform.enums;

public enum JobPriority {
    HIGH(1),
    MEDIUM(5),
    LOW(10);

    private final int numericPriority;

    JobPriority(int numericPriority) {
        this.numericPriority = numericPriority;
    }

    public int getNumericPriority() {
        return numericPriority;
    }
}
