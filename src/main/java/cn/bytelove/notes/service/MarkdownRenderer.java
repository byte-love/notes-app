package cn.bytelove.notes.service;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * commonmark 默认把原始 HTML 转义掉，所以不再额外做 XSS 过滤。
 * 标题 id 由渲染和目录提取共用同一套「按文档顺序编号」规则，两边必然一致。
 * 术语浮窗只在渲染阶段包一层 span，不影响目录取文字。
 */
@Service
public class MarkdownRenderer {

    public record TocItem(int level, String id, String text) {
    }

    /** 渲染成 <span class="gl" data-tip="…">term</span> 的节点。 */
    public static class TermNode extends Node {
        private final String text;
        private final String tip;

        TermNode(String text, String tip) {
            this.text = text;
            this.tip = tip;
        }

        @Override
        public void accept(org.commonmark.node.Visitor visitor) {
            // 自定义节点由 TermNodeRenderer 负责输出，不参与 Visitor 遍历
        }

        String getText() {
            return text;
        }

        String getTip() {
            return tip;
        }
    }

    private static class TermNodeRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;

        TermNodeRenderer(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(TermNode.class);
        }

        @Override
        public void render(Node node) {
            TermNode term = (TermNode) node;
            HtmlWriter writer = context.getWriter();
            writer.line();
            writer.tag("span", Map.of("class", "gl", "data-tip", term.getTip()), false);
            writer.text(term.getText());
            writer.tag("/span");
        }
    }

    private final Parser parser;
    private final GlossaryService glossary;
    private final List<org.commonmark.Extension> extensions;

    public MarkdownRenderer(GlossaryService glossary) {
        this.glossary = glossary;
        this.extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create()
        );
        this.parser = Parser.builder().extensions(extensions).build();
    }

    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = parser.parse(stripFrontMatter(markdown));
        wrapGlossary(document);

        AtomicInteger counter = new AtomicInteger();
        AttributeProvider headingIds = (node, tagName, attributes) -> {
            if (node instanceof Heading) {
                attributes.put("id", "h" + counter.incrementAndGet());
            }
            if ("img".equals(tagName)) {
                attributes.put("loading", "lazy");
                attributes.put("decoding", "async");
            }
        };
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .nodeRendererFactory(TermNodeRenderer::new)
                .attributeProviderFactory(context -> headingIds)
                .build();
        return renderer.render(document);
    }

    /** 只收 h2/h3，h1 是文章标题本身，放进目录没有意义。 */
    public List<TocItem> toc(String markdown) {
        List<TocItem> items = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return items;
        }
        Node document = parser.parse(stripFrontMatter(markdown));
        AtomicInteger counter = new AtomicInteger();
        collect(document, counter, items);
        return items;
    }

    private void collect(Node node, AtomicInteger counter, List<TocItem> out) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                int index = counter.incrementAndGet();
                int level = heading.getLevel();
                if (level == 2 || level == 3) {
                    out.add(new TocItem(level, "h" + index, textOf(heading)));
                }
            }
            collect(child, counter, out);
        }
    }

    /**
     * 笔记从飞书导出时带了 YAML front matter（name/description/tags/metadata），
     * 那是文件元数据，不是正文；直接渲染会变成一堆横线和 key: value。
     */
    private static final java.util.regex.Pattern FRONT_MATTER =
            java.util.regex.Pattern.compile("\\A\\uFEFF?---\\s*\\r?\\n.*?\\r?\\n---\\s*(\\r?\\n|\\z)",
                    java.util.regex.Pattern.DOTALL);

    private String stripFrontMatter(String markdown) {
        String stripped = FRONT_MATTER.matcher(markdown).replaceFirst("");
        return stripped.isBlank() ? markdown : stripped;
    }

    private void wrapGlossary(Node parent) {
        if (glossary.size() == 0) {
            return;
        }
        Set<String> used = glossary.newUsedSet();
        replaceIn(parent, used);
    }

    private void replaceIn(Node parent, Set<String> used) {
        Node child = parent.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            if (child instanceof Text textNode) {
                List<Node> parts = split(textNode.getLiteral(), used);
                if (parts != null) {
                    for (Node part : parts) {
                        textNode.insertBefore(part);
                    }
                    textNode.unlink();
                }
            } else if (!(child instanceof Code
                    || child instanceof FencedCodeBlock
                    || child instanceof IndentedCodeBlock
                    || child instanceof Heading
                    || child instanceof TermNode)) {
                replaceIn(child, used);
            }
            child = next;
        }
    }

    private List<Node> split(String literal, Set<String> used) {
        List<Node> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        boolean touched = false;

        while (i < literal.length()) {
            GlossaryService.Match hit = glossary.matchAt(literal, i, used);
            if (hit != null) {
                if (buffer.length() > 0) {
                    parts.add(new Text(buffer.toString()));
                    buffer.setLength(0);
                }
                parts.add(new TermNode(hit.displayText(), glossary.definition(hit.canonical())));
                used.add(hit.canonical());
                i += hit.displayText().length();
                touched = true;
            } else {
                buffer.append(literal.charAt(i));
                i++;
            }
        }

        if (!touched) {
            return null;
        }
        if (buffer.length() > 0) {
            parts.add(new Text(buffer.toString()));
        }
        return parts;
    }

    private String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        appendText(node, sb);
        return sb.toString().trim();
    }

    private void appendText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (child instanceof Code c) {
                sb.append(c.getLiteral());
            } else if (child instanceof TermNode t) {
                sb.append(t.getText());
            } else {
                appendText(child, sb);
            }
        }
    }
}
