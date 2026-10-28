package com.distributed.jobplatform.exception;

public class DuplicateJobException extends RuntimeException {
    public DuplicateJobException(String message) {
        super(message);
    }
}
