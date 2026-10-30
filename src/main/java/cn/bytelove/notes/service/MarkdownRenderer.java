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
import java.util.LinkedHashMap;
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
        Node document = parser.parse(fenceBareCode(stripFrontMatter(markdown)));
        wrapGlossary(document);

        Map<Heading, String> ids = assignHeadingIds(document);
        AttributeProvider headingIds = (node, tagName, attributes) -> {
            if (node instanceof Heading heading && ids.containsKey(heading)) {
                attributes.put("id", ids.get(heading));
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

    /**
     * 笔记里手写/粘贴的代码常常没加围栏：有的是一行 SQL 裸贴在正文里，有的是整段 Java
     * 被拆成「一行一个段落」还带了列表符号（以前的导入流程留下的）。
     * 这里在渲染前把它们收进代码块——判据放得很窄，避免把中文正文误判成代码：
     * 一行代码 = 去掉列表符号后不含中文、长度 ≤200、含 ; { } ( ) = 之一，
     * 或者以 // # -- 开头（注释里可以带中文）。连续 ≥3 行才算一段代码。
     */
    private static final java.util.regex.Pattern BARE_SQL = java.util.regex.Pattern.compile(
            "^(select|update|insert|delete|create|alter|drop|with|truncate)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CJK = java.util.regex.Pattern.compile(
            "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    private static final java.util.regex.Pattern BULLET =
            java.util.regex.Pattern.compile("^\\s*[-*+]\\s+");

    static String fenceBareCode(String markdown) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean inFence = false;
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                out.append(lines[i]).append('\n');
                i++;
                continue;
            }
            if (inFence || !codeish(trimmed)) {
                out.append(lines[i]).append('\n');
                i++;
                continue;
            }

            // 从这一行开始攒「连续代码行」，中间的空行允许跳过
            List<String> run = new ArrayList<>();
            int j = i;
            while (j < lines.length) {
                String next = lines[j].trim();
                if (next.isEmpty()) {
                    j++;
                    continue;
                }
                if (next.startsWith("```") || !codeish(next)) {
                    break;
                }
                run.add(stripBullet(lines[j]));
                j++;
            }

            if (run.size() >= 3) {
                String joined = String.join("\n", run);
                out.append("```").append(guessLang(joined)).append('\n')
                        .append(joined).append("\n```\n");
                i = j;
            } else if (run.size() == 1 && isBareSql(run.get(0))) {
                out.append("```\n").append(run.get(0)).append("\n```\n");
                i = j;
            } else {
                out.append(lines[i]).append('\n');
                i++;
            }
        }
        return out.toString();
    }

    private static String stripBullet(String line) {
        return BULLET.matcher(line).replaceFirst("");
    }

    private static boolean codeish(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }
        String s = stripBullet(trimmed).trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.startsWith("//") || s.startsWith("#") || s.startsWith("--")
                || s.startsWith("/*") || s.startsWith("@")) {
            return true;
        }
        // 结尾的中文句号不算内容（贴进来的 SQL 常被这么结尾）
        s = s.replaceAll("[\\s；;。.]+$", "");
        if (s.isEmpty() || s.length() > 200 || CJK.matcher(s).find()) {
            return false;
        }
        return s.indexOf(';') >= 0 || s.indexOf('{') >= 0 || s.indexOf('}') >= 0
                || s.indexOf('(') >= 0 || s.indexOf(')') >= 0 || s.indexOf('=') >= 0;
    }

    private static boolean isBareSql(String line) {
        String body = line.replaceAll("[\\s；;。.]+$", "");
        if (body.isEmpty() || CJK.matcher(body).find() || !BARE_SQL.matcher(body).find()
                || !body.contains(" ")) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(" where ") || lower.contains(" set ") || lower.contains(" from ")
                || lower.contains(" values ") || lower.contains(" join ");
    }

    private static String guessLang(String joined) {
        String l = joined.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("class ") || l.contains("public ") || l.contains("void ")
                || l.contains("system.out") || l.contains("new thread")) {
            return "java";
        }
        if (l.contains("redis.call") || l.contains("local ")) {
            return "lua";
        }
        if (l.contains("select ") || l.contains("update ") || l.contains("insert ")
                || l.contains("delete from")) {
            return "sql";
        }
        return "";
    }

    /** 只收 h2/h3，h1 是文章标题本身，放进目录没有意义。 */
    public List<TocItem> toc(String markdown) {
        List<TocItem> items = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return items;
        }
        Node document = parser.parse(stripFrontMatter(markdown));
        Map<Heading, String> ids = assignHeadingIds(document);
        collect(document, ids, items);
        return items;
    }

    /**
     * 标题 id 按标题文字生成（中文原样保留），重复的追加序号。
     * 以前用的是 h1/h2/h3 这种序位号，中间插一个标题就会把后面全部顶走，
     * 书签会静默指到别的段落上。
     */
    private Map<Heading, String> assignHeadingIds(Node document) {
        Map<Heading, String> ids = new LinkedHashMap<>();
        Map<String, Integer> used = new LinkedHashMap<>();
        walkHeadings(document, heading -> {
            String base = slugOf(textOf(heading));
            int n = used.merge(base, 1, Integer::sum);
            ids.put(heading, n == 1 ? base : base + "-" + n);
        });
        return ids;
    }

    private void walkHeadings(Node node, java.util.function.Consumer<Heading> sink) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                sink.accept(heading);
            }
            walkHeadings(child, sink);
        }
    }

    /** 去掉空白和标点，保留中英文数字；空的就给个兜底名。 */
    static String slugOf(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        // 只留中英文和数字，其余（空格、中英文标点、括号）全部折成一个连字符
        s = s.replaceAll("[^\\p{IsHan}a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            return "section";
        }
        return s.length() > 50 ? s.substring(0, 50).replaceAll("-$", "") : s;
    }

    private void collect(Node node, Map<Heading, String> ids, List<TocItem> out) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                int level = heading.getLevel();
                if (level == 2 || level == 3) {
                    String id = ids.getOrDefault(heading, "section");
                    out.add(new TocItem(level, id, textOf(heading)));
                }
            }
            collect(child, ids, out);
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