package org.mifos.community.ai.mcp.client;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.*;

public class DocumentTextExtractor {
    private final Tika tika;
    private final Tesseract tesseract;

    public DocumentTextExtractor() {
        //
        this.tika = new Tika();
        this.tesseract = new Tesseract();

        // Configura idioma por defecto
        tesseract.setLanguage("spa"); // o "eng" si el documento está en inglés
    }

    /**
     * Extrae texto de un archivo, primero con Tika y si no hay resultado, con OCR.
     */
    public String extractText(byte[] fileBytes) throws IOException, TikaException, TesseractException {
        String text = tryTika(fileBytes);

        if (text == null || text.trim().length() < 20) {
            System.out.println("El texto parece vacío o muy corto, aplicando OCR...");
            text = tryOCR(fileBytes);
        } else {
            System.out.println("Texto extraído exitosamente con Tika.");
        }

        return text;
    }

    private String tryTika(byte[] fileBytes) throws IOException, TikaException {
        try (InputStream stream = new ByteArrayInputStream(fileBytes)) {
            return tika.parseToString(stream);
        }
    }

    private String tryOCR(byte[] fileBytes) throws IOException, TesseractException {
        // Guardar en archivo temporal
        File tempFile = File.createTempFile("ocr", ".png");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(fileBytes);
        }

        String text = tesseract.doOCR(tempFile);

        // Borrar archivo temporal
        if (!tempFile.delete()) {
            System.out.println("Advertencia: no se pudo eliminar el archivo temporal " + tempFile.getAbsolutePath());
        }

        return text;
    }
}
