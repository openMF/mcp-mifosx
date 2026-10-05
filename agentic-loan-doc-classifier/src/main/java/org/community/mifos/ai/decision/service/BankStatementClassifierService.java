/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.community.mifos.ai.decision.client.OllamaSystemOneClient;
import org.community.mifos.ai.decision.dto.DocumentEvaluationRequest;
import org.community.mifos.ai.decision.dto.DocumentEvaluationResponse;
import org.community.mifos.ai.decision.util.ImageFormatDetector;

/**
 * Uses Ollama-hosted <strong>clef-flash</strong> (or clef) decision model via System One
 * to decide whether a loan-application document is a bank account statement.
 *
 * <p>Binary support (clef-flash multimodal):
 * <ul>
 *   <li>PNG, JPEG, WebP → base64 {@code images} array on {@code /v1/systemone}</li>
 *   <li>Plain text files → UTF-8 decode + text path</li>
 *   <li>PDF and other binaries → rejected (API only accepts images, not arbitrary files)</li>
 * </ul>
 *
 * @see <a href="https://ollama.com/library/clef-flash">clef-flash on Ollama</a>
 */
@Service
public class BankStatementClassifierService {

    private static final Logger log = LoggerFactory.getLogger(BankStatementClassifierService.class);

    private final TypeSafeClient typeSafeClient;
    private final OllamaSystemOneClient ollamaSystemOneClient;
    private final String model;
    private final double decisionThreshold;

    public BankStatementClassifierService(
            TypeSafeClient typeSafeClient,
            OllamaSystemOneClient ollamaSystemOneClient,
            @Value("${spring.ai.typesafe.model:clef-flash}") String model,
            @Value("${app.classifier.decision-threshold:0.75}") double decisionThreshold) {
        this.typeSafeClient = typeSafeClient;
        this.ollamaSystemOneClient = ollamaSystemOneClient;
        this.model = model;
        this.decisionThreshold = decisionThreshold;
    }

    /** Evaluate plain-text (or OCR-extracted) document content. */
    public DocumentEvaluationResponse evaluate(DocumentEvaluationRequest request) {
        Map<String, Object> state = buildState(
                request.documentText(),
                request.filename(),
                request.applicantId());

        SystemOneRequest systemOneRequest = SystemOneRequest.builder()
                .model(model)
                .state(state)
                .question("is_bank_account_statement", bankStatementNoul())
                .question("document_type", documentTypeChoice())
                .build();

        log.debug("Text path – calling decision model '{}'", model);
        SystemOneResponse response = typeSafeClient.systemOne(systemOneRequest);

        return toResponse(
                response.noulValue("is_bank_account_statement"),
                response.choiceValue("document_type"),
                response.choice("document_type").confidence(),
                response.model(),
                null,
                null);
    }

    /**
     * Evaluate one or more uploaded files.
     * <ul>
     *   <li>Images (PNG/JPEG/WebP, detected by magic bytes or content-type) → Clef-Flash vision</li>
     *   <li>Text-like files → UTF-8 text path</li>
     *   <li>Anything else (PDF, Office, etc.) → 400 with guidance</li>
     * </ul>
     */
    public DocumentEvaluationResponse evaluateFiles(List<MultipartFile> files,
                                                    String applicantId) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required");
        }

        List<byte[]> images = new ArrayList<>();
        List<String> imageNames = new ArrayList<>();
        String textContent = null;
        String textFilename = null;
        String textContentType = null;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename();
            String declared = file.getContentType();
            String resolvedImageType = ImageFormatDetector.resolveContentType(bytes, declared);

            if (resolvedImageType != null) {
                images.add(bytes);
                imageNames.add(filename != null ? filename : "image");
                log.info("Accepted image: filename={}, type={}, size={} bytes",
                        filename, resolvedImageType, bytes.length);
                continue;
            }

            if (isTextLike(declared, filename)) {
                if (textContent != null) {
                    throw new IllegalArgumentException(
                            "Only one text file is allowed per request (or use images only)");
                }
                textContent = new String(bytes, StandardCharsets.UTF_8);
                textFilename = filename;
                textContentType = normalizeContentType(declared);
                log.info("Accepted text file: filename={}, size={} bytes", filename, bytes.length);
                continue;
            }

            throw new IllegalArgumentException(
                    "Unsupported file '" + filename + "' (content-type: " + declared + "). " +
                    "clef-flash accepts base64 PNG, JPEG or WebP images only " +
                    "(see https://ollama.com/library/clef-flash). " +
                    "For PDFs: convert pages to PNG/JPEG/WebP or extract text first.");
        }

        if (!images.isEmpty()) {
            return evaluateImages(images, imageNames, applicantId, textContent);
        }

        if (textContent != null) {
            DocumentEvaluationRequest req =
                    new DocumentEvaluationRequest(textContent, textFilename, applicantId);
            DocumentEvaluationResponse r = evaluate(req);
            return new DocumentEvaluationResponse(
                    r.isBankAccountStatement(), r.probability(), r.decisionLabel(),
                    r.model(), r.explanation(), textFilename, textContentType);
        }

        throw new IllegalArgumentException("No non-empty files were provided");
    }

    /** Single-file convenience used by the controller. */
    public DocumentEvaluationResponse evaluateFile(MultipartFile file, String applicantId)
            throws IOException {
        return evaluateFiles(file == null ? List.of() : List.of(file), applicantId);
    }

    private DocumentEvaluationResponse evaluateImages(List<byte[]> imageBytes,
                                                      List<String> imageNames,
                                                      String applicantId,
                                                      String optionalText) {
        String docText = (optionalText != null && !optionalText.isBlank())
                ? optionalText
                : "Visual document(s) attached. Classify based on the visual content of the page(s).";

        String primaryName = imageNames.isEmpty() ? null : String.join(",", imageNames);
        Map<String, Object> state = buildState(docText, primaryName, applicantId);
        Map<String, Object> questions = buildQuestionsMap();

        log.debug("Image path – calling Ollama System One with {} image(s) on model '{}'",
                imageBytes.size(), model);

        Map<String, Object> raw = ollamaSystemOneClient.systemOne(state, questions, imageBytes);
        String contentType = ImageFormatDetector.detect(imageBytes.getFirst());
        return parseRawResponse(raw, primaryName, contentType != null ? contentType : "image/*");
    }

    private Map<String, Object> buildState(String documentText, String filename, String applicantId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("document_text", documentText);
        if (filename != null) {
            state.put("filename", filename);
        }
        if (applicantId != null) {
            state.put("applicant_id", applicantId);
        }
        state.put("context",
                "This document was submitted as supporting evidence in a personal or business loan application.");
        return state;
    }

    private Noul bankStatementNoul() {
        return Noul.of(
                "Is the provided document a bank account statement " +
                "(also known as a bank statement, checking/savings account statement, " +
                "or transaction history from a financial institution)? " +
                "Look for typical indicators: account holder name, account number, " +
                "statement period, opening/closing balances, list of credits and debits, " +
                "bank name/logo, branch details, or IBAN/routing numbers."
        );
    }

    private Choice documentTypeChoice() {
        return Choice.builder()
                .instructions("What is the most accurate classification of this document?")
                .option("bank_account_statement",
                        "Official bank statement showing transactions, balances and account details for a period")
                .option("payslip", "Salary or wage slip issued by an employer")
                .option("identity_document",
                        "Passport, national ID card, driving licence or similar identity proof")
                .option("utility_bill", "Electricity, gas, water, internet or phone bill")
                .option("tax_document", "Tax return, tax assessment or related tax form")
                .option("other", "Any other type of document that does not fit the above categories")
                .build();
    }

    private Map<String, Object> buildQuestionsMap() {
        Map<String, Object> noulQ = new LinkedHashMap<>();
        noulQ.put("type", "noul");
        noulQ.put("instructions",
                "Is the provided document a bank account statement " +
                "(also known as a bank statement, checking/savings account statement, " +
                "or transaction history from a financial institution)? " +
                "Look for typical indicators: account holder name, account number, " +
                "statement period, opening/closing balances, list of credits and debits, " +
                "bank name/logo, branch details, or IBAN/routing numbers.");

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("bank_account_statement",
                "Official bank statement showing transactions, balances and account details for a period");
        criteria.put("payslip", "Salary or wage slip issued by an employer");
        criteria.put("identity_document",
                "Passport, national ID card, driving licence or similar identity proof");
        criteria.put("utility_bill", "Electricity, gas, water, internet or phone bill");
        criteria.put("tax_document", "Tax return, tax assessment or related tax form");
        criteria.put("other", "Any other type of document that does not fit the above categories");

        Map<String, Object> choiceQ = new LinkedHashMap<>();
        choiceQ.put("type", "choice");
        choiceQ.put("instructions", "What is the most accurate classification of this document?");
        choiceQ.put("criteria", criteria);

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("is_bank_account_statement", noulQ);
        questions.put("document_type", choiceQ);
        return questions;
    }

    @SuppressWarnings("unchecked")
    private DocumentEvaluationResponse parseRawResponse(Map<String, Object> raw,
                                                        String filename,
                                                        String contentType) {
        String responseModel = String.valueOf(raw.getOrDefault("model", model));
        Map<String, Object> answers = (Map<String, Object>) raw.get("answers");
        if (answers == null) {
            throw new IllegalStateException("System One response missing 'answers': " + raw);
        }

        Map<String, Object> noulAnswer = (Map<String, Object>) answers.get("is_bank_account_statement");
        double noulValue = ((Number) noulAnswer.get("noul")).doubleValue();

        Map<String, Object> choiceAnswer = (Map<String, Object>) answers.get("document_type");
        String chosenType = String.valueOf(choiceAnswer.get("choice"));
        double typeConfidence = choiceAnswer.get("confidence") instanceof Number n
                ? n.doubleValue()
                : 0.0;

        return toResponse(noulValue, chosenType, typeConfidence, responseModel, filename, contentType);
    }

    private DocumentEvaluationResponse toResponse(double noulValue,
                                                  String chosenType,
                                                  double typeConfidence,
                                                  String responseModel,
                                                  String filename,
                                                  String contentType) {
        boolean isBankStatementDecision = noulValue >= decisionThreshold;
        String explanation = String.format(
                "Model '%s' assigned probability %.4f that the document is a bank account statement " +
                "(threshold=%.2f). Secondary classification: '%s' (confidence=%.4f).",
                responseModel, noulValue, decisionThreshold, chosenType, typeConfidence
        );

        log.info("Decision: isBankAccountStatement={}, probability={}, type={}, file={}",
                isBankStatementDecision, noulValue, chosenType, filename);

        return new DocumentEvaluationResponse(
                isBankStatementDecision,
                noulValue,
                chosenType,
                responseModel,
                explanation,
                filename,
                contentType);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        return (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
    }

    private static boolean isTextLike(String contentType, String filename) {
        if (contentType != null && contentType.toLowerCase().startsWith("text/")) {
            return true;
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            return lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".md");
        }
        return false;
    }
}
