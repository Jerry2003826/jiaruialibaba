package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.BusinessException;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts plain text and detects the MIME type of uploaded documents using Apache Tika. Supports
 * txt/md/html/pdf/docx/pptx/csv and many more; complex OCR is out of scope. Extraction failures
 * surface as a {@link BusinessException} so the caller can mark the document FAILED with a reason.
 */
@Component
public class DocumentTextExtractor {

    private static final int MAX_FILE_NAME_LENGTH = 256;

    private final KnowledgeProperties knowledgeProperties;

    public DocumentTextExtractor(KnowledgeProperties knowledgeProperties) {
        this.knowledgeProperties = knowledgeProperties;
    }

    /**
     * Detects the MIME type from the bytes and (best-effort) file name.
     *
     * @param bytes    the file bytes
     * @param fileName the original file name (may be {@code null})
     * @return the detected MIME type
     */
    public String detectMimeType(byte[] bytes, String fileName) {
        return newTika().detect(bytes, sanitizeFileName(fileName));
    }

    public boolean isAllowedMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return false;
        }
        Set<String> allowed = knowledgeProperties.getAllowedMimeTypes().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toUnmodifiableSet());
        return allowed.contains(mimeType.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * Extracts text from the given bytes.
     *
     * @param bytes    the file bytes
     * @param fileName the original file name (used as a parsing hint)
     * @return the extracted, trimmed text
     * @throws BusinessException if parsing fails or yields no text
     */
    public String extractText(byte[] bytes, String fileName) {
        try {
            String text = newTika().parseToString(new ByteArrayInputStream(bytes));
            if (!StringUtils.hasText(text)) {
                throw new BusinessException("DOCUMENT_PARSE_EMPTY",
                        "No extractable text found in file: " + safeName(fileName));
            }
            return text.strip();
        }
        catch (TikaException | IOException ex) {
            throw new BusinessException("DOCUMENT_PARSE_FAILED",
                    "Failed to parse file " + safeName(fileName) + ": " + ex.getMessage(), ex);
        }
    }

    private Tika newTika() {
        Tika tika = new Tika();
        tika.setMaxStringLength(knowledgeProperties.getMaxContentChars());
        return tika;
    }

    private String safeName(String fileName) {
        String sanitized = sanitizeFileName(fileName);
        return StringUtils.hasText(sanitized) ? sanitized : "(unnamed)";
    }

    public String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String baseName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String cleaned = baseName.replace("/", "").replace("\\", "").trim();
        if (!StringUtils.hasText(cleaned)) {
            return "uploaded-file";
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            return cleaned.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return cleaned;
    }

}
