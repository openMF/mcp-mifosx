/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.agentic.loan.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.community.mifos.agentic.loan.document.CurpDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CURP document agent for Mexican RENAPO constancias (PDF only).
 * <p>
 * Pipeline: PDF → in-process PNG (Apache PDFBox) → Ollama {@code /api/chat} vision
 * with {@code data:image/png;base64,...} → business validations.
 * <p>
 * Ollama vision models do not accept raw PDF bytes (400); the first page is
 * rendered to PNG in the JVM so no host {@code pdftoppm}/Tesseract is required.
 */
@Service
public class CurpDocumentAgent {

    private static final Logger log = LoggerFactory.getLogger(CurpDocumentAgent.class);

    private static final Pattern CURP_CLAVE = Pattern.compile(
            "\\b([A-Z]{4}\\d{6}[HM][A-Z]{5}[0-9A-Z]\\d)\\b");

    private static final Pattern ISSUE_DATE_ES = Pattern.compile(
            "(?:Ciudad de M[eé]xico,?\\s*a\\s*)?(\\d{1,2})\\s+de\\s+([a-zA-Záéíóúñ]+)\\s+de\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String VISION_PROMPT = """
        You are a document specialist for Mexican CURP constancias issued by RENAPO.
        Extract the fields from the image and return ONLY a valid JSON object. Do not include markdown formatting, explanations, or thinking tags.
        
        {
          "curpClave": "18-character code",
          "fullName": "Full name as printed on document",
          "registrationEntity": "State of registration",
          "issueDate": "YYYY-MM-DD",
          "civilRegistryVerified": true,
          "confidence": 0.95
        }
        
        IMPORTANT: Set "civilRegistryVerified" to true ONLY if the document explicitly contains BOTH phrases: "CURP Certificada" AND "verificada con el Registro Civil".
        """;

    private final WebClient ollama;
    private final String visionModel;
    private final int maxIssueAgeDays;
    private final float renderDpi;
    private final ObjectMapper mapper = new ObjectMapper();

    public CurpDocumentAgent(
            WebClient.Builder builder,
            @Value("${loan.ollama.base-url:http://localhost:11434}") String ollamaUrl,
            @Value("${loan.ollama.vision-model:qwen3-vl:8b}") String visionModel,
            @Value("${loan.curp.max-issue-age-days:30}") int maxIssueAgeDays,
            @Value("${loan.curp.render-dpi:120}") float renderDpi) {
        this.ollama = builder.baseUrl(ollamaUrl).build();
        this.visionModel = visionModel;
        this.maxIssueAgeDays = maxIssueAgeDays;
        this.renderDpi = renderDpi;
    }

    public CurpDocument review(String path, String expectedDisplayName) {
        CurpDocument doc = new CurpDocument();
        Path file = Path.of(path);

        if (!Files.isRegularFile(file)) {
            doc.addMessage("File not found: " + path);
            doc.setOverallValid(false);
            return doc;
        }

        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pdf")) {
            doc.addMessage("CURP must be a PDF issued by RENAPO (Federal Government). Got: " + file.getFileName());
            doc.setOverallValid(false);
            return doc;
        }

        try {
            byte[] pngBytes = renderPdfFirstPageToPng(Files.readAllBytes(file));
            log.info("Rendered CURP PDF first page to PNG ({} bytes) for vision model", pngBytes.length);

            String visionJson = runOllamaChatVisionPng(pngBytes);
            doc.setVisionJson(visionJson);
            doc.setVisionModel(visionModel);
            if (visionJson != null && !visionJson.isBlank()) {
                String preview = visionJson.length() > 500 ? visionJson.substring(0, 500) + "..." : visionJson;
                log.info("Vision raw response preview: {}", preview.replace("\n", " "));
            }

            mergeExtraction(doc, visionJson);
            validate(doc, expectedDisplayName);
        } catch (Exception e) {
            log.error("CURP review failed for {}: {}", path, e.getMessage(), e);
            doc.addMessage("CURP review error: " + e.getMessage());
            doc.setOverallValid(false);
        }
        return doc;
    }

    /** In-JVM PDF page 0 → PNG (no external pdftoppm). */
    private byte[] renderPdfFirstPageToPng(byte[] pdfBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() < 1) {
                throw new IllegalStateException("PDF has no pages");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, renderDpi, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", baos)) {
                throw new IllegalStateException("ImageIO failed to write PNG");
            }
            return baos.toByteArray();
        }
    }

    /**
     * POST /api/chat with PNG as data:image/png;base64,...
     * Tries OpenAI-style content parts; logs response body on 400.
     */
    @SuppressWarnings("unchecked")

    /**
     * Official Ollama vision format (NOT OpenAI content-array):
     * <pre>
     * POST /api/chat
     * {
     *   "model": "qwen3-vl:8b",
     *   "messages": [{
     *     "role": "user",
     *     "content": "<prompt string>",
     *     "images": ["<raw base64 PNG, no data: prefix>"]
     *   }],
     *   "stream": false
     * }
     * </pre>
     * OpenAI-style content arrays cause:
     * "cannot unmarshal array into Go struct field ChatRequest.messages.content of type string"
     */
    private String runOllamaChatVisionPng(byte[] pngBytes) {
        String b64 = Base64.getEncoder().encodeToString(pngBytes);

        // 1) Native Ollama /api/chat (correct for qwen3-vl, llava, gemma3, …)
        String result = postOllamaChat(b64);
        if (result != null && !result.isBlank()) {
            return result;
        }

        // 2) OpenAI-compatible endpoint (supports content array)
        result = postOpenAiCompatVision(b64);
        if (result != null && !result.isBlank()) {
            return result;
        }

        // 3) /api/generate
        return tryLegacyGenerateApi(b64);
    }

    @SuppressWarnings("unchecked")
    private String postOllamaChat(String pngBase64) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", VISION_PROMPT);   // MUST be a string
        message.put("images", List.of(pngBase64)); // raw base64, no data-URL prefix

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", visionModel);
        body.put("messages", List.of(message));
        body.put("stream", false);
        // qwen3-vl often dumps structured output into "thinking" and leaves content empty
        // when format=json is set; do not force format — parse JSON from content or thinking.
        body.put("think", false);
        body.put("options", Map.of(
                "temperature", 0.1,
                "num_predict", 2048
        ));

        try {
            log.info("Calling Ollama /api/chat (native images[]) model={} b64Chars={}",
                    visionModel, pngBase64.length());
            Map<String, Object> resp = ollama.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(300))
                    .block();

            String extracted = extractChatText(resp);
            if (extracted != null && !extracted.isBlank()) {
                log.info("Ollama /api/chat response length={}", extracted.length());
                return extracted;
            }
            if (resp != null) {
                log.warn("Ollama /api/chat empty content; keys={} message={}",
                        resp.keySet(), resp.get("message"));
            }
            return null;
        } catch (WebClientResponseException e) {
            log.warn("Ollama /api/chat failed {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Ollama /api/chat failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * qwen3-vl may return the usable text in message.content OR message.thinking
     * (known Ollama quirk when thinking/format interacts).
     */
    private String extractChatText(Map<String, Object> resp) {
        if (resp == null) return null;
        Object msg = resp.get("message");
        if (msg instanceof Map<?, ?> m) {
            Object content = m.get("content");
            if (content != null && !content.toString().isBlank()) {
                return content.toString().trim();
            }
            Object thinking = m.get("thinking");
            if (thinking != null && !thinking.toString().isBlank()) {
                log.info("Using message.thinking (content was empty) length={}", thinking.toString().length());
                return thinking.toString().trim();
            }
        }
        Object response = resp.get("response");
        if (response != null && !response.toString().isBlank()) {
            return response.toString().trim();
        }
        Object thinkingTop = resp.get("thinking");
        if (thinkingTop != null && !thinkingTop.toString().isBlank()) {
            log.info("Using top-level thinking field length={}", thinkingTop.toString().length());
            return thinkingTop.toString().trim();
        }
        return null;
    }

    /**
     * Optional path: OpenAI-compatible /v1/chat/completions (content array + image_url).
     * Works on newer Ollama builds; used only if native /api/chat fails.
     */
    @SuppressWarnings("unchecked")
    private String postOpenAiCompatVision(String pngBase64) {
        String dataUrl = "data:image/png;base64," + pngBase64;

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", VISION_PROMPT));
        content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", dataUrl)
        ));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", visionModel);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("stream", false);
        body.put("temperature", 0.1);

        try {
            log.info("Calling Ollama /v1/chat/completions (OpenAI-compat) model={}", visionModel);
            Map<String, Object> resp = ollama.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();

            if (resp == null) {
                return null;
            }
            Object choices = resp.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
                Object message = choice.get("message");
                if (message instanceof Map<?, ?> m) {
                    Object c = m.get("content");
                    if (c != null && !c.toString().isBlank()) {
                        String text = c.toString().trim();
                        log.info("OpenAI-compat vision response length={}", text.length());
                        return text;
                    }
                    Object reasoning = m.get("reasoning_content");
                    if (reasoning == null) reasoning = m.get("reasoning");
                    if (reasoning != null && !reasoning.toString().isBlank()) {
                        log.info("OpenAI-compat using reasoning field length={}", reasoning.toString().length());
                        return reasoning.toString().trim();
                    }
                }
            }
            log.warn("Unexpected OpenAI-compat response keys: {}", resp.keySet());
            return null;
        } catch (WebClientResponseException e) {
            log.warn("OpenAI-compat vision failed {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("OpenAI-compat vision failed: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String tryLegacyGenerateApi(String pngBase64) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", visionModel);
            body.put("prompt", VISION_PROMPT);
            body.put("images", List.of(pngBase64));
            body.put("stream", false);
            body.put("think", false);
            body.put("options", Map.of("temperature", 0.1, "num_predict", 2048));
            log.info("Falling back to Ollama /api/generate images[] model={}", visionModel);
            Map<String, Object> resp = ollama.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(300))
                    .block();
            String out = extractChatText(resp);
            if (out == null) out = "";
            log.info("Ollama /api/generate response length={}", out.length());
            return out;
        } catch (WebClientResponseException e) {
            log.warn("Legacy /api/generate failed {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return "";
        } catch (Exception e) {
            log.warn("Legacy /api/generate failed: {}", e.getMessage());
            return "";
        }
    }


    private void mergeExtraction(CurpDocument doc, String visionRaw) {
        if (visionRaw == null || visionRaw.isBlank()) {
            log.warn("Vision returned empty text – cannot extract CURP fields");
            return;
        }

        // Strip markdown fences and optional <think> wrappers
        String cleaned = visionRaw
                .replaceAll("(?s)<think>.*?</think>", " ")
                .replaceAll("(?s)```(?:json)?\\s*", " ")
                .replaceAll("```", " ")
                .trim();

        boolean parsed = false;
        try {
            int s = cleaned.indexOf('{');
            int e = cleaned.lastIndexOf('}');
            if (s >= 0 && e > s) {
                String json = cleaned.substring(s, e + 1);
                JsonNode n = mapper.readTree(json);
                // Support English + Spanish keys the model may invent
                String clave = firstText(n, "curpClave", "curp", "clave", "claveCurp", "CURP");
                if (clave != null) {
                    doc.setCurpClave(clave.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""));
                }
                String name = firstText(n, "fullName", "nombre", "name", "nombreCompleto");
                if (name != null) {
                    doc.setFullName(name.trim().toUpperCase(Locale.ROOT));
                }
                String entity = firstText(n, "registrationEntity", "entidad", "entidadRegistro");
                if (entity != null) {
                    doc.setRegistrationEntity(entity);
                }
                String issue = firstText(n, "issueDate", "fechaEmision", "fecha", "date");
                if (issue != null) {
                    try {
                        doc.setIssueDate(LocalDate.parse(issue.trim().substring(0, Math.min(10, issue.trim().length()))));
                    } catch (Exception ignored) {
                        Matcher dm = ISSUE_DATE_ES.matcher(issue);
                        if (dm.find()) {
                            doc.setIssueDate(parseSpanishDate(dm.group(1), dm.group(2), dm.group(3)));
                        }
                    }
                }
                if (n.has("civilRegistryVerified")) {
                    doc.setCivilRegistryVerified(n.get("civilRegistryVerified").asBoolean(false));
                } else if (n.has("verificado") || n.has("certificada")) {
                    JsonNode v = n.has("verificado") ? n.get("verificado") : n.get("certificada");
                    doc.setCivilRegistryVerified(v.asBoolean(false));
                }
                if (n.has("confidence")) {
                    doc.setConfidence(n.get("confidence").asDouble(0.8));
                }
                parsed = true;
                log.info("Parsed vision JSON clave={} name={} issueDate={}",
                        doc.getCurpClave(), doc.getFullName(), doc.getIssueDate());
            }
        } catch (Exception ex) {
            log.warn("Vision JSON parse failed: {} raw={}", ex.getMessage(),
                    cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned);
        }

        // Regex fallback on full raw text
        String text = cleaned.toUpperCase(Locale.ROOT);

        if (doc.getCurpClave() == null || doc.getCurpClave().isBlank()) {
            // Try matching on text with spaces removed first (most reliable for OCR artifacts)
            String textNoSpaces = text.replaceAll("\\s+", "");
            Matcher m = CURP_CLAVE.matcher(textNoSpaces);

            if (m.find()) {
                doc.setCurpClave(m.group(1));
                log.info("CURP clave from regex (no spaces): {}", doc.getCurpClave());
            } else {
                // Fallback to original text with spaces
                m = CURP_CLAVE.matcher(text);
                if (m.find()) {
                    doc.setCurpClave(m.group(1));
                    log.info("CURP clave from regex: {}", doc.getCurpClave());
                }
            }
        }

        if (doc.getFullName() == null || doc.getFullName().isBlank()) {
            // Thinking text often has: fullName: "VICTOR MANUEL ROMERO RODRIGUEZ"
            Matcher nm = Pattern.compile(
                    "(?:fullName|nombre(?:Completo)?|name)\\s*[:=]\\s*[\"']?([A-Za-zÁÉÍÓÚÑáéíóúñ][A-Za-zÁÉÍÓÚÑáéíóúñ .]{4,80})[\"']?",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(cleaned);
            if (nm.find()) {
                String candidate = nm.group(1).replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
                // stop at trailing junk like AS PRINTED
                candidate = candidate.replaceAll("\\s+AS\\s+PRINTED.*", "").trim();
                doc.setFullName(candidate);
                log.info("CURP name from labeled field: {}", doc.getFullName());
            } else {
                nm = Pattern.compile("NOMBRE\\s*:?\\s*([A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ ]{4,80})").matcher(text);
                if (nm.find()) {
                    doc.setFullName(nm.group(1).replaceAll("\\s+", " ").trim());
                    log.info("CURP name from NOMBRE: {}", doc.getFullName());
                }
            }
        }

        if (doc.getIssueDate() == null) {
            Matcher dm = ISSUE_DATE_ES.matcher(cleaned);
            if (dm.find()) {
                doc.setIssueDate(parseSpanishDate(dm.group(1), dm.group(2), dm.group(3)));
                log.info("CURP issueDate from Spanish text: {}", doc.getIssueDate());
            }
            if (doc.getIssueDate() == null) {
                Matcher iso = Pattern.compile(
                        "(?:issueDate|fechaEmision|fecha)\\s*[:=]\\s*[\"']?(\\d{4}-\\d{2}-\\d{2})",
                        Pattern.CASE_INSENSITIVE).matcher(cleaned);
                if (iso.find()) {
                    try {
                        doc.setIssueDate(LocalDate.parse(iso.group(1)));
                        log.info("CURP issueDate from ISO text: {}", doc.getIssueDate());
                    } catch (Exception ignored) {}
                }
            }
        }

        // Strict: must see official RENAPO phrase
        // "CURP Certificada: verificada con el Registro Civil"
        // Check if the phrase exists in the raw text (fallback only)
        boolean phraseFoundInRaw = containsCurpCertificadaPhrase(cleaned) 
                || containsCurpCertificadaPhrase(visionRaw);
                
        // Trust the model's JSON extraction if it explicitly set it to true.
        // Otherwise, fallback to the raw text phrase detection.
        if (doc.isCivilRegistryVerified()) {
            log.info("CURP certification verified via JSON extraction");
        } else if (phraseFoundInRaw) {
            doc.setCivilRegistryVerified(true);
            log.info("CURP certification phrase detected in raw text fallback");
        } else {
            log.warn("CURP certification phrase NOT found (required: CURP Certificada: verificada con el Registro Civil)");
        }

        if (doc.getConfidence() <= 0) {
            double c = parsed ? 0.7 : 0.4;
            if (doc.getCurpClave() != null) c += 0.15;
            if (doc.getFullName() != null) c += 0.1;
            if (doc.getIssueDate() != null) c += 0.05;
            if (doc.isCivilRegistryVerified()) c += 0.05;
            doc.setConfidence(Math.min(c, 0.99));
        }
    }

    private static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.hasNonNull(k)) {
                String v = n.get(k).asText();
                if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private void validate(CurpDocument doc, String expectedDisplayName) {
        String expected = normalizeName(expectedDisplayName);
        String actual = normalizeName(doc.getFullName());
        boolean nameOk = !expected.isBlank() && !actual.isBlank()
                && (actual.equals(expected) || actual.contains(expected) || expected.contains(actual));
        if (!nameOk && !expected.isBlank() && !actual.isBlank()) {
            nameOk = tokenSet(expected).equals(tokenSet(actual));
        }
        doc.setNameMatches(nameOk);
        if (nameOk) {
            doc.addMessage("Name match OK: application/Mifos display name matches CURP name '" + doc.getFullName() + "'");
        } else {
            doc.addMessage("Name mismatch: expected '" + expectedDisplayName + "' vs CURP '" + doc.getFullName() + "'");
        }

        boolean claveFormatOk = doc.getCurpClave() != null
                && CURP_CLAVE.matcher(doc.getCurpClave()).matches();
        boolean certifiedPhrase = doc.isCivilRegistryVerified();
        boolean registryOk = certifiedPhrase && claveFormatOk;
        doc.setRegistryOk(registryOk);
        if (registryOk) {
            doc.addMessage("Civil Registry OK: found 'CURP Certificada: verificada con el Registro Civil' and clave "
                    + doc.getCurpClave());
        } else if (!claveFormatOk) {
            doc.addMessage("Civil Registry FAIL: CURP clave missing or invalid format");
        } else if (!certifiedPhrase) {
            doc.addMessage("Civil Registry FAIL: missing required text "
                    + "'CURP Certificada: verificada con el Registro Civil'");
        } else {
            doc.addMessage("Civil Registry FAIL: certification or clave validation failed");
        }

        boolean dateOk = false;
        if (doc.getIssueDate() != null) {
            long age = ChronoUnit.DAYS.between(doc.getIssueDate(), LocalDate.now());
            dateOk = age >= 0 && age <= maxIssueAgeDays;
            if (dateOk) {
                doc.addMessage("Issue date OK: " + doc.getIssueDate() + " (" + age + " days old, max " + maxIssueAgeDays + ")");
            } else if (age < 0) {
                doc.addMessage("Issue date FAIL: " + doc.getIssueDate() + " is in the future");
            } else {
                doc.addMessage("Issue date FAIL: " + doc.getIssueDate() + " is " + age + " days old (max " + maxIssueAgeDays + ")");
            }
        } else {
            doc.addMessage("Issue date FAIL: could not extract issue date from CURP document");
        }
        doc.setIssueDateOk(dateOk);

        doc.setOverallValid(nameOk && registryOk && dateOk);
        if (doc.isOverallValid()) {
            doc.addMessage("CURP document ACCEPTED for loan origination");
        } else {
            doc.addMessage("CURP document REJECTED – one or more validation rules failed");
        }
    }

    private static String normalizeName(String name) {
        if (name == null) return "";
        return name.toUpperCase(Locale.ROOT)
                .replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U").replace("Ñ", "N")
                .replaceAll("[^A-Z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static java.util.Set<String> tokenSet(String normalized) {
        return new java.util.TreeSet<>(List.of(normalized.split(" ")));
    }

    private static LocalDate parseSpanishDate(String day, String monthEs, String year) {
        Map<String, Integer> months = Map.ofEntries(
                Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3),
                Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
                Map.entry("julio", 7), Map.entry("agosto", 8), Map.entry("septiembre", 9),
                Map.entry("setiembre", 9), Map.entry("octubre", 10), Map.entry("noviembre", 11),
                Map.entry("diciembre", 12)
        );
        Integer m = months.get(monthEs.toLowerCase(Locale.ROOT)
                .replace("á", "a").replace("é", "e").replace("í", "i"));
        if (m == null) return null;
        try {
            return LocalDate.of(Integer.parseInt(year), m, Integer.parseInt(day));
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
    * Detects the official RENAPO certification text on a CURP constancia,
    * e.g. "CURP Certificada: verificada con el Registro Civil".
    * Tolerant of case, extra whitespace, newlines and accents
    * ("Registro Civil" vs "Registro Cívíl" / OCR artifacts).
    */
   private static boolean containsCurpCertificadaPhrase(String text) {
       if (text == null || text.isBlank()) {
           return false;
       }
       // Normalize: lowercase, strip accents, collapse all whitespace to single spaces
       String norm = Normalizer.normalize(text, Normalizer.Form.NFD)
               .replaceAll("\\p{M}", "")          // drop combining marks (á → a)
               .toLowerCase(Locale.ROOT)
               .replaceAll("\\s+", " ");
       return norm.contains("curp certificada")
               && norm.contains("verificada con el registro civil");
   }
}
