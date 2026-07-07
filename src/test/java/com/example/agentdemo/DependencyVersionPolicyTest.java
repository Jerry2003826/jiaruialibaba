package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyVersionPolicyTest {

    @Test
    void tikaVersionStaysOnSafeStableLine() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile());

        String tikaVersion = pom.getElementsByTagName("tika.version").item(0).getTextContent();

        assertThat(compareVersion(tikaVersion, "3.3.1"))
                .as("Apache Tika must stay on the stable 3.x line after CVE-2025-66516")
                .isGreaterThanOrEqualTo(0);
    }

    private static int compareVersion(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int width = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < width; i++) {
            int leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
