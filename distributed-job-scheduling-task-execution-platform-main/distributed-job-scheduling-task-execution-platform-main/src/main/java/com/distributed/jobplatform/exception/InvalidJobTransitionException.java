package com.distributed.jobplatform.exception;

public class InvalidJobTransitionException extends RuntimeException {
    public InvalidJobTransitionException(String message) {
        super(message);
    }
}
