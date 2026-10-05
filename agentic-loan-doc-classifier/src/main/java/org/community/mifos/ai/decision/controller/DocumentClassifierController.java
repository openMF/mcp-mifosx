/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.controller;

import org.community.mifos.ai.decision.dto.DocumentEvaluationRequest;
import org.community.mifos.ai.decision.dto.DocumentEvaluationResponse;
import org.community.mifos.ai.decision.service.BankStatementClassifierService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentClassifierController {

    private final BankStatementClassifierService classifierService;

    public DocumentClassifierController(BankStatementClassifierService classifierService) {
        this.classifierService = classifierService;
    }

    /**
     * Evaluate document text (JSON body).
     */
    @PostMapping(value = "/evaluate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DocumentEvaluationResponse evaluate(@Valid @RequestBody DocumentEvaluationRequest request) {
        return classifierService.evaluate(request);
    }

    /**
     * Evaluate one or more binary files (multipart).
     * <p>
     * <strong>clef-flash</strong> vision accepts base64 <strong>PNG, JPEG, WebP</strong> only
     * (<a href="https://ollama.com/library/clef-flash">model card</a>).
     * PDFs and other formats must be converted to images or text first.
     *
     * <pre>
     * # Single image
     * curl -X POST http://localhost:8080/api/v1/documents/evaluate-file \
     *   -F "file=@statement.png" -F "applicantId=APP-1"
     *
     * # Multiple pages / images (all sent in the System One images array)
     * curl -X POST http://localhost:8080/api/v1/documents/evaluate-file \
     *   -F "file=@page1.png" -F "file=@page2.jpg" -F "applicantId=APP-1"
     * </pre>
     */
    @PostMapping(value = "/evaluate-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DocumentEvaluationResponse evaluateFile(
            @RequestPart("file") MultipartFile[] files,
            @RequestParam(value = "applicantId", required = false) String applicantId)
            throws IOException {
        List<MultipartFile> list = files == null ? List.of() : Arrays.asList(files);
        return classifierService.evaluateFiles(list, applicantId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }
}
