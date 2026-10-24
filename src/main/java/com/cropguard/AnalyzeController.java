package com.cropguard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * POST /analyze  -  same request/response format as the Flask version,
 * so the existing script.js works without changes.
 */
@RestController
public class AnalyzeController {

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private final GeminiService geminiService;

    public AnalyzeController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "lang", defaultValue = "hi") String lang) throws IOException {

        if (image == null) {
            return error(400, "No image provided in the request.");
        }

        String filename = image.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return error(400, "No file selected for upload.");
        }

        String mimeType = mimeTypeFor(filename);
        if (mimeType == null) {
            return error(400, "Invalid file type. Only PNG, JPG, JPEG, GIF and WEBP are allowed.");
        }

        System.out.println("Selected Language = " + lang);

        Map<String, Object> result = geminiService.analyzeImage(image.getBytes(), mimeType, lang);

        Object isPlant = result.get("is_plant");
        if (Boolean.FALSE.equals(isPlant)) {
            return error(400,
                    "The image does not appear to be a plant. Please upload a clear photo of a leaf or crop.");
        }

        result.put("source", "gemini");

        Map<String, Object> body = new HashMap<>();
        body.put("result", result);
        return ResponseEntity.ok(body);
    }

    private static String mimeTypeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return MIME_BY_EXTENSION.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
