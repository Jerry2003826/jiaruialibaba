package com.example.agentdemo.workflow.report;

import com.example.agentdemo.common.BusinessException;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReportDocumentRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();
    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.builder().build();
    private static final TextContentRenderer TEXT_RENDERER = TextContentRenderer.builder().build();
    private static final int A4_WIDTH_TWIPS = 11906;
    private static final int A4_HEIGHT_TWIPS = 16838;
    private static final int LETTER_WIDTH_TWIPS = 12240;
    private static final int LETTER_HEIGHT_TWIPS = 15840;

    public ReportRenderBundle render(ReportRenderRequest request, List<ReportFormat> formats) {
        try {
            Document document = (Document) PARSER.parse(request.markdown());
            removeRawHtml(document);
            String body = sanitizeHtml(HTML_RENDERER.render(document));
            String html = completeHtml(request, body, false);
            String preview = completeHtml(request, body, true);
            String markdown = canonicalMarkdown(request, document);
            String text = canonicalText(request, document);

            Map<ReportFormat, byte[]> files = new LinkedHashMap<>();
            for (ReportFormat format : formats) {
                files.put(format, switch (format) {
                    case PDF -> pdf(preview);
                    case DOCX -> docx(request, document);
                    case HTML -> html.getBytes(StandardCharsets.UTF_8);
                    case MARKDOWN -> markdown.getBytes(StandardCharsets.UTF_8);
                    case TXT -> text.getBytes(StandardCharsets.UTF_8);
                });
            }
            return new ReportRenderBundle(files, preview.getBytes(StandardCharsets.UTF_8));
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BusinessException("REPORT_EXPORT_FAILED", "Failed to render report", ex);
        }
    }

    private void removeRawHtml(Node root) {
        List<Node> unsafe = new ArrayList<>();
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(HtmlBlock node) {
                unsafe.add(node);
            }

            @Override
            public void visit(HtmlInline node) {
                unsafe.add(node);
            }
        });
        unsafe.forEach(Node::unlink);
    }

    private String sanitizeHtml(String html) {
        Safelist safelist = Safelist.relaxed()
                .removeTags("img")
                .removeAttributes(":all", "style", "onerror", "onclick", "onload")
                .addProtocols("a", "href", "http", "https", "mailto");
        return Jsoup.clean(html, "", safelist,
                new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
    }

    private String completeHtml(ReportRenderRequest request, String body, boolean printPreview) {
        String title = escape(request.title());
        String metadata = metadataHtml(request);
        String toc = request.includeToc() ? tocHtml((Document) PARSER.parse(request.markdown())) : "";
        String pageSize = "Letter".equalsIgnoreCase(request.paperSize()) ? "Letter" : "A4";
        String orientation = "landscape".equalsIgnoreCase(request.orientation()) ? " landscape" : " portrait";
        String pageCounter = request.includePageNumbers()
                ? "@bottom-center { content: counter(page) ' / ' counter(pages); font-size: 10px; color: #667085; }"
                : "";
        String css = themeCss(request.theme()) + "@page { size: " + pageSize + orientation
                + "; margin: 20mm 18mm; " + pageCounter + " }";
        String rootClass = printPreview ? "report-page report-print-root" : "report-page";
        return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + title + "</title><style>" + css
                + "</style></head><body><main class=\"" + rootClass + "\"><header><h1>" + title + "</h1>"
                + metadata + "</header>" + toc + "<article>" + body + "</article></main></body></html>";
    }

    private String metadataHtml(ReportRenderRequest request) {
        List<String> values = new ArrayList<>();
        if (StringUtils.hasText(request.author())) values.add("作者：" + escape(request.author()));
        if (StringUtils.hasText(request.organization())) values.add("机构：" + escape(request.organization()));
        return values.isEmpty() ? "" : "<p class=\"report-meta\">" + String.join(" · ", values) + "</p>";
    }

    private String tocHtml(Document document) {
        List<String> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String text = collectText(heading);
                if (StringUtils.hasText(text)) headings.add(text);
                visitChildren(heading);
            }
        });
        if (headings.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<nav class=\"report-toc\"><strong>目录</strong><ol>");
        headings.forEach(heading -> html.append("<li>").append(escape(heading)).append("</li>"));
        return html.append("</ol></nav>").toString();
    }

    private String collectText(Node root) {
        StringBuilder text = new StringBuilder();
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(Text node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(Code node) {
                text.append(node.getLiteral());
            }
        });
        return text.toString();
    }

    private String canonicalMarkdown(ReportRenderRequest request, Document document) {
        StringBuilder output = new StringBuilder();
        if (StringUtils.hasText(request.title())) output.append("# ").append(request.title()).append("\n\n");
        if (StringUtils.hasText(request.author())) output.append("作者：").append(request.author()).append("\n\n");
        if (StringUtils.hasText(request.organization())) output.append("机构：").append(request.organization()).append("\n\n");
        return output.append(MARKDOWN_RENDERER.render(document)).toString();
    }

    private String canonicalText(ReportRenderRequest request, Document document) {
        StringBuilder output = new StringBuilder();
        if (StringUtils.hasText(request.title())) output.append(request.title()).append("\n\n");
        if (StringUtils.hasText(request.author())) output.append("作者：").append(request.author()).append('\n');
        if (StringUtils.hasText(request.organization())) output.append("机构：").append(request.organization()).append('\n');
        return output.append('\n').append(TEXT_RENDERER.render(document)).toString();
    }

    private byte[] pdf(String html) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl((uri, type) -> false,
                    ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            URL bundledFont = getClass().getResource("/fonts/NotoSansSC-Regular.ttf");
            if (bundledFont != null) {
                builder.useFont(() -> getClass().getResourceAsStream("/fonts/NotoSansSC-Regular.ttf"),
                        "ReportSans", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            }
            else {
                File font = platformReportFont();
                if (font != null) {
                    builder.useFont(font, "ReportSans", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
                }
            }
            builder.withHtmlContent(asXhtml(html), null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        }
    }

    private String asXhtml(String html) {
        org.jsoup.nodes.Document document = Jsoup.parse(html);
        document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml).charset(StandardCharsets.UTF_8);
        return document.html();
    }

    private File platformReportFont() {
        for (String path : List.of(
                "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")) {
            File candidate = new File(path);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private byte[] docx(ReportRenderRequest request, Document document) throws IOException {
        try (XWPFDocument output = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            configurePage(output, request);
            output.getProperties().getCoreProperties().setTitle(request.title());
            output.getProperties().getCoreProperties().setCreator(request.author());
            if (StringUtils.hasText(request.title())) addParagraph(output, request.title(), "Title", true, 24);
            if (StringUtils.hasText(request.author())) addParagraph(output, "作者：" + request.author(), null, false, 10);
            if (StringUtils.hasText(request.organization())) addParagraph(output, "机构：" + request.organization(), null, false, 10);
            if (request.includeToc()) addDocxToc(output, document);
            renderDocxNodes(output, document);
            if (request.includePageNumbers()) addPageNumberFooter(output);
            output.write(bytes);
            return bytes.toByteArray();
        }
    }

    private void configurePage(XWPFDocument document, ReportRenderRequest request) {
        var section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr() : document.getDocument().getBody().addNewSectPr();
        var size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        int width = "Letter".equalsIgnoreCase(request.paperSize()) ? LETTER_WIDTH_TWIPS : A4_WIDTH_TWIPS;
        int height = "Letter".equalsIgnoreCase(request.paperSize()) ? LETTER_HEIGHT_TWIPS : A4_HEIGHT_TWIPS;
        if ("landscape".equalsIgnoreCase(request.orientation())) {
            size.setW(java.math.BigInteger.valueOf(height));
            size.setH(java.math.BigInteger.valueOf(width));
            size.setOrient(org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation.LANDSCAPE);
        }
        else {
            size.setW(java.math.BigInteger.valueOf(width));
            size.setH(java.math.BigInteger.valueOf(height));
            size.setOrient(org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation.PORTRAIT);
        }
    }

    private void renderDocxNodes(XWPFDocument output, Node document) {
        Node node = document.getFirstChild();
        while (node != null) {
            if (node instanceof Heading heading) {
                int size = Math.max(12, 24 - (heading.getLevel() * 2));
                addParagraph(output, collectText(heading), "Heading" + heading.getLevel(), true, size);
            }
            else if (node instanceof Paragraph paragraph) {
                addParagraph(output, collectText(paragraph), null, false, 11);
            }
            else if (node instanceof BulletList list) renderDocxList(output, list, false);
            else if (node instanceof OrderedList list) renderDocxList(output, list, true);
            else if (node instanceof TableBlock table) renderDocxTable(output, table);
            else if (node instanceof FencedCodeBlock code) addCodeParagraph(output, code.getLiteral());
            else if (node instanceof IndentedCodeBlock code) addCodeParagraph(output, code.getLiteral());
            else if (node instanceof BlockQuote quote) addParagraph(output, "“" + collectText(quote) + "”", null, false, 11);
            else {
                String text = TEXT_RENDERER.render(node).trim();
                if (StringUtils.hasText(text)) addParagraph(output, text, null, false, 11);
            }
            node = node.getNext();
        }
    }

    private void addDocxToc(XWPFDocument output, Document document) {
        List<String> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                String text = collectText(heading);
                if (StringUtils.hasText(text)) headings.add(text);
                visitChildren(heading);
            }
        });
        if (headings.isEmpty()) return;
        addParagraph(output, "目录", "Heading1", true, 16);
        for (int index = 0; index < headings.size(); index++) {
            addParagraph(output, (index + 1) + ". " + headings.get(index), null, false, 10);
        }
    }

    private void renderDocxList(XWPFDocument output, Node list, boolean ordered) {
        int index = list instanceof OrderedList orderedList ? orderedList.getStartNumber() : 1;
        Node child = list.getFirstChild();
        while (child != null) {
            if (child instanceof ListItem) {
                String prefix = ordered ? index++ + ". " : "• ";
                addParagraph(output, prefix + collectText(child), null, false, 11);
            }
            child = child.getNext();
        }
    }

    private void renderDocxTable(XWPFDocument output, TableBlock block) {
        List<List<TableCell>> rows = new ArrayList<>();
        collectTableRows(block, rows);
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = output.createTable(rows.size(), columns);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<TableCell> cells = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                var target = table.getRow(rowIndex).getCell(columnIndex);
                target.removeParagraph(0);
                XWPFParagraph paragraph = target.addParagraph();
                XWPFRun run = paragraph.createRun();
                TableCell source = columnIndex < cells.size() ? cells.get(columnIndex) : null;
                run.setText(source == null ? "" : collectText(source));
                run.setBold(source != null && source.isHeader());
                run.setFontFamily("Noto Sans CJK SC");
                run.setFontSize(10);
            }
        }
    }

    private void collectTableRows(Node node, List<List<TableCell>> rows) {
        if (node instanceof TableRow) {
            List<TableCell> cells = new ArrayList<>();
            Node cell = node.getFirstChild();
            while (cell != null) {
                if (cell instanceof TableCell tableCell) cells.add(tableCell);
                cell = cell.getNext();
            }
            rows.add(cells);
            return;
        }
        Node child = node.getFirstChild();
        while (child != null) {
            collectTableRows(child, rows);
            child = child.getNext();
        }
    }

    private void addCodeParagraph(XWPFDocument output, String text) {
        XWPFParagraph paragraph = output.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text == null ? "" : text);
        run.setFontFamily("Menlo");
        run.setFontSize(9);
    }

    private void addPageNumberFooter(XWPFDocument document) {
        XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph paragraph = footer.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.getCTR().addNewFldChar().setFldCharType(
                org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.BEGIN);
        run.getCTR().addNewInstrText().setStringValue(" PAGE ");
        run.getCTR().addNewFldChar().setFldCharType(
                org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType.END);
    }

    private void addParagraph(XWPFDocument document, String text, String style, boolean bold, int size) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) paragraph.setStyle(style);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setFontFamily("Noto Sans CJK SC");
        run.setFontSize(size);
    }

    private String themeCss(String theme) {
        String[] palette = switch (String.valueOf(theme).toLowerCase()) {
            case "minimal" -> new String[] { "#111827", "#6b7280", "#111827", "#d1d5db" };
            case "academic" -> new String[] { "#211f1c", "#635f58", "#7a1f2b", "#b8b0a6" };
            default -> new String[] { "#172033", "#667085", "#2459b8", "#d7deea" };
        };
        String ink = palette[0];
        String muted = palette[1];
        String accent = palette[2];
        String line = palette[3];
        return "*{box-sizing:border-box}body{margin:0;background:#fff;color:" + ink + ";"
                + "font-family:ReportSans,'Noto Sans CJK SC','Microsoft YaHei',sans-serif;line-height:1.65}"
                + ".report-page{max-width:190mm;margin:0 auto;padding:18mm}header{border-bottom:2px solid " + accent + ";"
                + "padding-bottom:12px;margin-bottom:24px}h1{font-size:28px;margin:0 0 8px}h2,h3,h4{color:" + accent + ";"
                + "page-break-after:avoid}.report-meta{color:" + muted + ";margin:0}.report-toc{border:1px solid " + line + ";"
                + "padding:16px 20px;margin:20px 0}.report-toc ol{margin-bottom:0}table{width:100%;border-collapse:collapse}"
                + "th,td{border:1px solid " + line + ";padding:7px 9px;text-align:left}"
                + "pre,code{font-family:ReportSans,'Noto Sans CJK SC',monospace}"
                + "pre{background:#f6f8fb;padding:12px;white-space:pre-wrap}@media print{.report-page{max-width:none;padding:0}}";
    }

    private String escape(String value) {
        return org.jsoup.nodes.Entities.escape(value == null ? "" : value);
    }
}
