package com.cropguard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/** Always answers with {"error": "..."} so the frontend can show the message. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<Map<String, Object>> handleGemini(GeminiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(Map.of("error", "Image is too large. Maximum size is 16 MB."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Server Error: " + e.getMessage()));
    }
}
