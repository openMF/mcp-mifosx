/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>Prepares uploaded documents for the OpenFn vision job.
 *
 * <p>Ollama vision models only accept images, and OpenFn jobs cannot render PDFs,
 * so the gateway renders the first PDF page to PNG (Apache PDFBox, as in
 * agentic-banking-temporal's CurpDocumentAgent) and sends it base64-encoded in the
 * webhook payload.
 */
package org.community.mifos.agentic.loan.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DocumentImages {

    private static final Logger log = LoggerFactory.getLogger(DocumentImages.class);

    private DocumentImages() {}

    /**
     * One entry per path: {@code path}, {@code docType} and either {@code imageBase64}
     * (raw base64 PNG/JPEG, no data-URL prefix) or {@code error}.
     */
    public static List<Map<String, Object>> prepare(List<String> paths, float renderDpi) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (paths == null) {
            return out;
        }
        for (String path : paths) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("path", path);
            doc.put("docType", inferDocType(path));
            try {
                byte[] bytes = Files.readAllBytes(Path.of(path));
                String lower = path.toLowerCase(Locale.ROOT);
                byte[] image;
                if (lower.endsWith(".pdf")) {
                    image = renderFirstPageToPng(bytes, renderDpi);
                } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                    image = bytes;
                } else {
                    throw new IllegalArgumentException("unsupported document type (PDF, PNG or JPEG expected)");
                }
                doc.put("imageBase64", Base64.getEncoder().encodeToString(image));
            } catch (Exception e) {
                log.warn("Could not prepare document {}: {}", path, e.getMessage());
                doc.put("error", e.getMessage());
            }
            out.add(doc);
        }
        return out;
    }

    static String inferDocType(String path) {
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("curp") ? "CURP" : "GENERIC";
    }

    static byte[] renderFirstPageToPng(byte[] pdfBytes, float dpi) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() == 0) {
                throw new IllegalArgumentException("PDF has no pages");
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        }
    }
}
