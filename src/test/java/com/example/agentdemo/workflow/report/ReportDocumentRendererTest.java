package com.example.agentdemo.workflow.report;

import org.apache.tika.Tika;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDocumentRendererTest {

    private final ReportDocumentRenderer renderer = new ReportDocumentRenderer();

    @Test
    void rendersFiveReportFormatsAndPreservesChineseText() throws Exception {
        ReportRenderRequest request = new ReportRenderRequest(
                "AI 硬件研究报告", "测试作者", "示例机构",
                "# 摘要\n\n这是中文报告。\n\n- 结论一\n- 结论二\n\n"
                        + "| 指标 | 数值 |\n| --- | --- |\n| 增长 | 12% |\n\n"
                        + "```java\nSystem.out.println(\"示例代码\");\n```",
                "business", "A4", "portrait", true, true);

        ReportRenderBundle bundle = renderer.render(request,
                List.of(ReportFormat.PDF, ReportFormat.DOCX, ReportFormat.HTML,
                        ReportFormat.MARKDOWN, ReportFormat.TXT));

        assertThat(bundle.files()).containsOnlyKeys(
                ReportFormat.PDF, ReportFormat.DOCX, ReportFormat.HTML,
                ReportFormat.MARKDOWN, ReportFormat.TXT);
        assertThat(bundle.files().get(ReportFormat.PDF)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        try (var pdf = Loader.loadPDF(bundle.files().get(ReportFormat.PDF))) {
            String extracted = Normalizer.normalize(new PDFTextStripper().getText(pdf), Normalizer.Form.NFKC);
            assertThat(extracted).contains("AI 硬件研究报告", "这是中文报告", "示例代码");
        }
        assertThat(bundle.files().get(ReportFormat.DOCX)).startsWith(new byte[] { 'P', 'K' });
        assertThat(new String(bundle.files().get(ReportFormat.HTML), StandardCharsets.UTF_8))
                .contains("AI 硬件研究报告", "这是中文报告")
                .doesNotContain("<script");
        assertThat(new String(bundle.files().get(ReportFormat.MARKDOWN), StandardCharsets.UTF_8))
                .contains("# AI 硬件研究报告", "这是中文报告");
        assertThat(new String(bundle.files().get(ReportFormat.TXT), StandardCharsets.UTF_8))
                .contains("AI 硬件研究报告", "这是中文报告");
        assertThat(new Tika().parseToString(new ByteArrayInputStream(
                bundle.files().get(ReportFormat.DOCX))))
                .contains("这是中文报告", "目录", "结论一", "指标", "12%", "示例代码");
        assertThat(zipEntryText(bundle.files().get(ReportFormat.DOCX), "word/footer1.xml"))
                .contains("PAGE");
        assertThat(new String(bundle.printPreview(), StandardCharsets.UTF_8))
                .contains("AI 硬件研究报告", "report-print-root")
                .doesNotContain("http://", "https://", "<script");
    }

    @Test
    void removesUnsafeHtmlAndRemoteImagesFromMarkdown() {
        ReportRenderRequest request = new ReportRenderRequest(
                "安全报告", "", "",
                "正文<script>alert(1)</script><img src=\"https://example.com/a.png\" onerror=\"alert(2)\">",
                "minimal", "Letter", "landscape", false, false);

        String html = new String(renderer.render(request, List.of(ReportFormat.HTML))
                .files().get(ReportFormat.HTML), StandardCharsets.UTF_8);

        assertThat(html).doesNotContain("<script", "onerror", "https://example.com", "<img");
    }

    private String zipEntryText(byte[] archive, String expectedName) throws Exception {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (expectedName.equals(entry.getName())) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
