package org.community.mifos.agentic.loan.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.community.mifos.agentic.loan.document.CurpDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loan-document agent specialized in Mexican CURP constancias.
 * Pipeline: (1) optional PDF→image, (2) Tesseract OCR, (3) Ollama vision structured extract,
 * (4) business validations for loan origination.
 *
 * Validation rules:
 * <ul>
 *   <li>Mifos / application display name must match CURP name</li>
 *   <li>CURP must be certified / verified with the Civil Registry (from document or format)</li>
 *   <li>Issue date must be within the last 30 days</li>
 * </ul>
 */
@Service
public class CurpDocumentAgent {

    private static final Logger log = LoggerFactory.getLogger(CurpDocumentAgent.class);

    /** Official CURP pattern: 4 letters + YYMMDD + H/M + 5 alnum + 1 digit/letter + 1 digit */
    private static final Pattern CURP_CLAVE = Pattern.compile(
            "\\b([A-Z]{4}\\d{6}[HM][A-Z]{5}[0-9A-Z]\\d)\\b");

    private static final Pattern ISSUE_DATE_ES = Pattern.compile(
            "(?:Ciudad de M[eé]xico,?\\s*a\\s*)?(\\d{1,2})\\s+de\\s+([a-zA-Záéíóúñ]+)\\s+de\\s+(\\d{4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final WebClient ollama;
    private final String visionModel;
    private final String tesseractBin;
    private final String pdftoppmBin;
    private final int maxIssueAgeDays;
    private final ObjectMapper mapper = new ObjectMapper();

    public CurpDocumentAgent(
            WebClient.Builder builder,
            @Value("${loan.ollama.base-url:http://localhost:11434}") String ollamaUrl,
            @Value("${loan.ollama.vision-model:${loan.ollama.model:llava:latest}}") String visionModel,
            @Value("${loan.ocr.tesseract-bin:tesseract}") String tesseractBin,
            @Value("${loan.ocr.pdftoppm-bin:pdftoppm}") String pdftoppmBin,
            @Value("${loan.curp.max-issue-age-days:30}") int maxIssueAgeDays) {
        this.ollama = builder.baseUrl(ollamaUrl).build();
        this.visionModel = visionModel;
        this.tesseractBin = tesseractBin;
        this.pdftoppmBin = pdftoppmBin;
        this.maxIssueAgeDays = maxIssueAgeDays;
    }

    /**
     * Full CURP review for a local file path.
     *
     * @param path               filesystem path to PDF or image
     * @param expectedDisplayName Mifos / application full name that must match CURP name
     */
    public CurpDocument review(String path, String expectedDisplayName) {
        CurpDocument doc = new CurpDocument();
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            doc.addMessage("File not found: " + path);
            doc.setOverallValid(false);
            return doc;
        }

        String ocrText = runTesseractPipeline(file);
        doc.setOcrText(ocrText);

        String visionJson = runOllamaVision(file);
        doc.setVisionJson(visionJson);

        mergeExtraction(doc, ocrText, visionJson);
        validate(doc, expectedDisplayName);
        return doc;
    }

    // OCR / Vision

    private String runTesseractPipeline(Path file) {
        try {
            Path workDir = Files.createTempDirectory("curp-ocr-");
            List<Path> images = toImages(file, workDir);
            StringBuilder all = new StringBuilder();
            for (Path img : images) {
                all.append(tesseract(img)).append("\n");
            }
            String text = all.toString().trim();
            log.info("Tesseract OCR extracted {} chars from {}", text.length(), file.getFileName());
            return text;
        } catch (Exception e) {
            log.warn("Tesseract pipeline failed (will rely on vision if available): {}", e.getMessage());
            return "";
        }
    }

    private List<Path> toImages(Path file, Path workDir) throws IOException, InterruptedException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<Path> images = new ArrayList<>();
        if (name.endsWith(".pdf")) {
            Path outPrefix = workDir.resolve("page");
            ProcessBuilder pb = new ProcessBuilder(
                    pdftoppmBin, "-png", "-r", "200",
                    file.toAbsolutePath().toString(),
                    outPrefix.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                log.warn("pdftoppm exit {}: {}", code, out);
            }
            try (var stream = Files.list(workDir)) {
                stream.filter(f -> f.getFileName().toString().startsWith("page") && f.toString().endsWith(".png"))
                        .sorted()
                        .forEach(images::add);
            }
            if (images.isEmpty()) {
                // Fallback: try ImageMagick convert
                Path single = workDir.resolve("page-1.png");
                ProcessBuilder conv = new ProcessBuilder(
                        "convert", "-density", "200", file.toAbsolutePath().toString() + "[0]",
                        single.toAbsolutePath().toString());
                conv.redirectErrorStream(true);
                Process cp = conv.start();
                cp.waitFor();
                if (Files.exists(single)) images.add(single);
            }
        } else {
            images.add(file);
        }
        return images;
    }

    private String tesseract(Path image) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                tesseractBin, image.toAbsolutePath().toString(), "stdout",
                "-l", "spa+eng", "--psm", "6");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            log.warn("tesseract exit {} for {}: {}", code, image, text);
            return "";
        }
        return text;
    }

    private String runOllamaVision(Path file) {
        try {
            Path workDir = Files.createTempDirectory("curp-vis-");
            List<Path> images = toImages(file, workDir);
            if (images.isEmpty()) {
                log.warn("No images for vision model");
                return "";
            }
            byte[] bytes = Files.readAllBytes(images.get(0));
            String b64 = Base64.getEncoder().encodeToString(bytes);

            String prompt = """
                    You are a document specialist for Mexican CURP (Clave Única de Registro de Población).
                    Extract fields from this CURP constancia image. Return ONLY valid JSON:
                    {
                      "curpClave": "18-char CURP code",
                      "fullName": "FULL NAME AS PRINTED",
                      "registrationEntity": "entity if present",
                      "issueDate": "yyyy-MM-dd if present",
                      "civilRegistryVerified": true/false,
                      "confidence": 0.0-1.0
                    }
                    civilRegistryVerified=true if the document states CURP Certificada / verificada con el Registro Civil / RENAPO.
                    """;

            Map<String, Object> body = new HashMap<>();
            body.put("model", visionModel);
            body.put("prompt", prompt);
            body.put("images", List.of(b64));
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.1));

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = ollama.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();

            String text = resp != null ? String.valueOf(resp.getOrDefault("response", "")) : "";
            log.info("Ollama vision response length={}", text.length());
            return text;
        } catch (Exception e) {
            log.warn("Ollama vision failed: {}", e.getMessage());
            return "";
        }
    }

    // Merge + validate

    private void mergeExtraction(CurpDocument doc, String ocr, String visionRaw) {
        // Prefer structured vision JSON, fill gaps from OCR
        if (visionRaw != null && !visionRaw.isBlank()) {
            try {
                int s = visionRaw.indexOf('{');
                int e = visionRaw.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JsonNode n = mapper.readTree(visionRaw.substring(s, e + 1));
                    if (n.hasNonNull("curpClave")) doc.setCurpClave(n.get("curpClave").asText().trim().toUpperCase(Locale.ROOT));
                    if (n.hasNonNull("fullName")) doc.setFullName(n.get("fullName").asText().trim().toUpperCase(Locale.ROOT));
                    if (n.hasNonNull("registrationEntity")) doc.setRegistrationEntity(n.get("registrationEntity").asText());
                    if (n.hasNonNull("issueDate")) {
                        try {
                            doc.setIssueDate(LocalDate.parse(n.get("issueDate").asText().trim()));
                        } catch (DateTimeParseException ignored) { /* OCR fallback */ }
                    }
                    if (n.has("civilRegistryVerified")) {
                        doc.setCivilRegistryVerified(n.get("civilRegistryVerified").asBoolean(false));
                    }
                    if (n.has("confidence")) doc.setConfidence(n.get("confidence").asDouble(0.8));
                }
            } catch (Exception ex) {
                log.warn("Vision JSON parse failed: {}", ex.getMessage());
            }
        }

        String text = (ocr != null ? ocr : "") + "\n" + (visionRaw != null ? visionRaw : "");
        text = text.toUpperCase(Locale.ROOT);

        if (doc.getCurpClave() == null || doc.getCurpClave().isBlank()) {
            Matcher m = CURP_CLAVE.matcher(text.replaceAll("\\s+", ""));
            // also try with spaces
            if (!m.find()) {
                m = CURP_CLAVE.matcher(text);
            }
            if (m.find()) {
                doc.setCurpClave(m.group(1));
            }
        }

        if (doc.getFullName() == null || doc.getFullName().isBlank()) {
            // Sample layout: "Nombre: VICTOR MANUEL ROMERO RODRIGUEZ"
            Matcher nm = Pattern.compile("NOMBRE\\s*:?\\s*([A-ZÁÉÍÓÚÑ ]{5,80})").matcher(text);
            if (nm.find()) {
                doc.setFullName(nm.group(1).replaceAll("\\s+", " ").trim());
            }
        }

        if (doc.getIssueDate() == null && ocr != null) {
            Matcher dm = ISSUE_DATE_ES.matcher(ocr);
            if (dm.find()) {
                doc.setIssueDate(parseSpanishDate(dm.group(1), dm.group(2), dm.group(3)));
            }
        }

        if (!doc.isCivilRegistryVerified()) {
            String lower = (ocr != null ? ocr : "") + " " + (visionRaw != null ? visionRaw : "");
            lower = lower.toLowerCase(Locale.ROOT);
            boolean certified = lower.contains("curp certificada")
                    || lower.contains("verificada con el registro civil")
                    || lower.contains("registro civil")
                    || lower.contains("renapo");
            doc.setCivilRegistryVerified(certified);
        }

        if (doc.getConfidence() <= 0) {
            double c = 0.4;
            if (doc.getCurpClave() != null) c += 0.25;
            if (doc.getFullName() != null) c += 0.2;
            if (doc.getIssueDate() != null) c += 0.1;
            if (doc.isCivilRegistryVerified()) c += 0.05;
            doc.setConfidence(Math.min(c, 0.99));
        }
    }

    private void validate(CurpDocument doc, String expectedDisplayName) {
        // 1) Name match (Mifos display name vs CURP name)
        String expected = normalizeName(expectedDisplayName);
        String actual = normalizeName(doc.getFullName());
        boolean nameOk = !expected.isBlank() && !actual.isBlank()
                && (actual.equals(expected) || actual.contains(expected) || expected.contains(actual));
        // Token-set equality for reordered surnames
        if (!nameOk && !expected.isBlank() && !actual.isBlank()) {
            nameOk = tokenSet(expected).equals(tokenSet(actual));
        }
        doc.setNameMatches(nameOk);
        if (nameOk) {
            doc.addMessage("Name match OK: application/Mifos display name matches CURP name '" + doc.getFullName() + "'");
        } else {
            doc.addMessage("Name mismatch: expected '" + expectedDisplayName + "' vs CURP '" + doc.getFullName() + "'");
        }

        // 2) Civil registry verification
        boolean claveFormatOk = doc.getCurpClave() != null
                && CURP_CLAVE.matcher(doc.getCurpClave()).matches();
        boolean registryOk = doc.isCivilRegistryVerified() && claveFormatOk;
        doc.setRegistryOk(registryOk);
        if (registryOk) {
            doc.addMessage("Civil Registry OK: CURP " + doc.getCurpClave() + " certified/verified on document");
        } else if (!claveFormatOk) {
            doc.addMessage("Civil Registry FAIL: CURP clave missing or invalid format");
        } else {
            doc.addMessage("Civil Registry FAIL: document does not show CURP Certificada / Registro Civil verification");
        }

        // 3) Issue date within maxIssueAgeDays
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
}
