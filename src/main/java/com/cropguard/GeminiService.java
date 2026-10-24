package com.cropguard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Sends the leaf image to the Gemini REST API and returns the parsed JSON result.
 * (Java replacement for analyze_image() in app.py)
 */
@Service
public class GeminiService {

    private static final String GEMINI_PROMPT = """
            You are an expert agricultural botanist and plant pathologist.

            Analyze this plant/leaf image and return ONLY a valid JSON object.

            JSON format:
            {
              "plant_name": "Common name of the plant",
              "is_plant": true,
              "is_healthy": true,
              "disease_name": "Healthy",
              "confidence": 90,
              "severity": "none",
              "causes": ["cause1","cause2","cause3"],
              "treatment": ["step1","step2","step3","step4","step5"],
              "medicine": ["Medicine Name 1 - Dosage/Quantity and usage method", "Medicine Name 2 - Dosage"],
              "prevention": ["tip1","tip2","tip3"]
            }

            severity options:
            none
            moderate
            severe
            critical

            Return JSON only.
            """;

    private static final Map<String, String> LANGUAGES = Map.of(
            "hi", "Hindi",
            "en", "English",
            "bn", "Bengali",
            "ur", "Urdu",
            "ta", "Tamil",
            "te", "Telugu",
            "kn", "Kannada",
            "mr", "Marathi",
            "gu", "Gujarati",
            "pa", "Punjabi");

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public GeminiService(@Value("${gemini.api-key:}") String apiKey,
                         @Value("${gemini.model:gemini-2.5-flash}") String model,
                         @Value("${gemini.timeout-seconds:90}") long timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public Map<String, Object> analyzeImage(byte[] imageBytes, String mimeType, String language) {

        if (apiKey.isEmpty() || apiKey.startsWith("PASTE_")) {
            throw new GeminiException(
                    "Gemini API key is not set. Set GEMINI_API_KEY (or create local.properties) and restart the server.",
                    500);
        }

        String responseLanguage = LANGUAGES.getOrDefault(language, "English");
        String prompt = GEMINI_PROMPT + """

                IMPORTANT:

                Return ALL values in %s language.

                Translate:
                - plant_name
                - disease_name
                - causes
                - treatment
                - medicine
                - prevention

                Keep JSON keys in English.
                Translate only values.

                Return JSON only.
                """.formatted(responseLanguage);

        String requestBody = buildRequestBody(prompt, imageBytes, mimeType);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + model + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeminiException("Gemini Error: request was interrupted.", 500);
        } catch (IOException e) {
            throw new GeminiException("Gemini Error: could not reach Gemini (" + e.getMessage() + ")", 500);
        }

        if (response.statusCode() != 200) {
            throw new GeminiException(describeHttpError(response.statusCode(), response.body()), 500);
        }

        String rawText = extractText(response.body());
        return parseResult(rawText);
    }

    // ------------------------------------------------------------------ helpers

    private String buildRequestBody(String prompt, byte[] imageBytes, String mimeType) {
        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> imagePart = Map.of("inline_data", Map.of(
                "mime_type", mimeType,
                "data", Base64.getEncoder().encodeToString(imageBytes)));

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(textPart, imagePart))),
                "generationConfig", Map.of("responseMimeType", "application/json"));
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new GeminiException("Gemini Error: could not build request.", 500);
        }
    }

    private String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                if (part.has("text")) {
                    sb.append(part.get("text").asText());
                }
            }
            if (sb.length() == 0) {
                String reason = root.path("promptFeedback").path("blockReason").asText("");
                throw new GeminiException(reason.isEmpty()
                        ? "Gemini API failed to return a valid JSON response."
                        : "Gemini blocked this image (" + reason + ").", 500);
            }
            return sb.toString().trim();
        } catch (JsonProcessingException e) {
            throw new GeminiException("Gemini API failed to return a valid JSON response.", 500);
        }
    }

    private Map<String, Object> parseResult(String rawText) {
        // Remove ```json fences if the model added them (same as the Python version)
        if (rawText.contains("```")) {
            rawText = rawText.replace("```json", "").replace("```", "").trim();
        }
        try {
            return mapper.readValue(rawText, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new GeminiException("Gemini API failed to return a valid JSON response.", 500);
        }
    }

    private String describeHttpError(int status, String body) {
        String googleMessage = "";
        try {
            googleMessage = mapper.readTree(body).path("error").path("message").asText("");
        } catch (Exception ignored) {
            // body was not JSON
        }
        if (status == 429) {
            return "Gemini Error: free-tier limit reached. Please wait a minute and try again.";
        }
        if (status == 400 || status == 401 || status == 403) {
            return "Gemini Error: the API key was rejected or is invalid"
                    + (googleMessage.isEmpty() ? "." : " (" + googleMessage + ").");
        }
        return "Gemini Error (" + status + ")" + (googleMessage.isEmpty() ? "." : ": " + googleMessage);
    }
}
