package com.cropguard;

/** Error with a message that is safe to show to the user, plus an HTTP status. */
public class GeminiException extends RuntimeException {

    private final int status;

    public GeminiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
