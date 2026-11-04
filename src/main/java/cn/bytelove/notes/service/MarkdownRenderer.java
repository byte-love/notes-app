package cn.bytelove.notes.service;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
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
import java.util.Locale;
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

    /** 渲染成 <span class="gl" data-tip="…" data-term="…" data-tid="…">term</span> 的节点。 */
    public static class TermNode extends Node {
        private final String text;
        private final GlossaryService.Entry entry;

        TermNode(String text, GlossaryService.Entry entry) {
            this.text = text;
            this.entry = entry;
        }

        @Override
        public void accept(org.commonmark.node.Visitor visitor) {
            // 自定义节点由 TermNodeRenderer 负责输出，不参与 Visitor 遍历
        }

        String getText() {
            return text;
        }

        GlossaryService.Entry getEntry() {
            return entry;
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
            // data-tid 是这条解释在库里的 id：页面上点一下就能改 / 删 / 新增
            writer.tag("span", Map.of(
                    "class", "gl",
                    "data-tip", term.getEntry().tip(),
                    "data-term", term.getEntry().term(),
                    "data-sense", term.getEntry().sense() == null ? "" : term.getEntry().sense(),
                    "data-tid", String.valueOf(term.getEntry().id())), false);
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
        Node document = parser.parse(fenceBareCode(stripFrontMatter(markdown)));        wrapGlossary(document);

        Map<Heading, String> ids = assignHeadingIds(document);
        AttributeProvider attrProvider = (node, tagName, attributes) -> {
            if (node instanceof Heading heading && ids.containsKey(heading)) {
                attributes.put("id", ids.get(heading));
            }
            if (node instanceof BlockQuote quote) {
                String tone = calloutTone(quote);
                if (tone != null) {
                    attributes.put("data-tone", tone);
                }
            }
            if ("img".equals(tagName)) {
                attributes.put("loading", "lazy");
                attributes.put("decoding", "async");
            }
        };
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .nodeRendererFactory(TermNodeRenderer::new)
                .attributeProviderFactory(context -> attrProvider)
                .build();
        return stripPresentational(renderer.render(document));
    }

    /**
     * 正文第一行又把标题抄了一遍的话，去掉那一行。
     * <p>
     * AI 存笔记时经常这么干：页面顶上已经渲染了标题，正文第一行再来一次「4. 内存泄漏与内存溢出」，
     * 看着像重复。只在<b>第一行的文字和标题一致</b>时才删（比较前先去掉 #、列表记号、编号和标点），
     * 其它情况原样返回，不动用户正文；也不改库里的内容，只是渲染时不显示这一行。
     */
    public String dropTitleEcho(String markdown, String title) {
        if (markdown == null || markdown.isBlank() || title == null) {
            return markdown;
        }
        String want = comparable(title);
        if (want.isEmpty()) {
            return markdown;
        }
        String[] lines = markdown.split("\n", -1);
        int at = 0;
        while (at < lines.length && lines[at].isBlank()) {
            at++;
        }
        if (at >= lines.length) {
            return markdown;
        }
        String bare = lines[at].strip()
                .replaceFirst("^#{1,6}\\s*", "")
                .replaceFirst("^(?:[-*+]\\s+|\\d+[.)、]\\s*)", "")
                .replaceAll("[*_`]", "");
        if (!comparable(bare).equals(want)) {
            return markdown;
        }
        StringBuilder out = new StringBuilder(markdown.length());
        for (int i = 0; i < lines.length; i++) {
            if (i == at) {
                continue;
            }
            out.append(lines[i]).append('\n');
        }
        return out.toString().stripLeading();
    }

    /** 比较用的形态：去掉空白、中英文标点、Markdown 记号，忽略大小写。 */
    private static String comparable(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\p{Punct}，。、：；！？（）【】〔〕「」『』《》〈〉—…·]+", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 从别处（AI 对话、网页）整段粘进来的内容常带 raw HTML 和行内样式：
     * `style="background:#fff;width:50%"` 这种会把我们的排版顶歪（白底块、表格溢出）。
     * commonmark 默认放行 raw HTML，所以在出口把纯展示用的属性抹掉，只留结构。
     */
    private static String stripPresentational(String html) {
        String out = html.replaceAll("\\s+(style|width|height|bgcolor|background|border|cellpadding|cellspacing|align|valign|face|color|size)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        // 空的 <div> / <span> 包裹层（粘贴时常见）留出多余纵向空隙，一并去掉
        out = out.replaceAll("<(div|span)\\s*>\\s*</\\1>", "");
        return out;
    }

    /**
     * 笔记里手写/粘贴的代码常常没加围栏：有的是一行 SQL 裸贴在正文里，有的是整段 Java
     * 被拆成「一行一个段落」还带了列表符号（以前的导入流程留下的）。
     * 这里在渲染前把它们收进代码块——判据放得很窄，避免把中文正文误判成代码：
     * 一行代码 = 去掉列表符号后不含中文、长度 ≤200、含 ; { } ( ) = 之一，
     * 或者以 // # -- 开头（注释里可以带中文）。连续 ≥3 行才算一段代码。
     *
     * 几个不放宽就会出错的坑（2026-11-02 全站体检时补的）：
     * <ul>
     *   <li>Markdown 自己的图片 / 链接 / 表格行不是代码——以前 doc-redis 里连着几张截图
     *       就被收进代码块，页面上显示的是一堆 {@code ![](...)} 字面量</li>
     *   <li>JSON 的键值行要单独认：{@code "id": 202,          // 活动ID} 这种行尾中文注释
     *       会被「含中文就不是代码」这条挡掉，整段 JSON 就漏在正文里</li>
     *   <li>行尾注释（{@code code; // 说明}）同理，判中文之前先把它摘掉</li>
     * </ul>
     */
    private static final java.util.regex.Pattern BARE_SQL = java.util.regex.Pattern.compile(
            "^(select|update|insert|delete|create|alter|drop|with|truncate)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CJK = java.util.regex.Pattern.compile(
            "[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    private static final java.util.regex.Pattern BULLET =
            java.util.regex.Pattern.compile("^\\s*[-*+]\\s+");

    /** Markdown 的图片、链接、表格行：都是排版语法，不是代码。 */
    private static final java.util.regex.Pattern MD_IMAGE =
            java.util.regex.Pattern.compile("^!\\[[^\\]]*\\]\\(");

    private static final java.util.regex.Pattern MD_LINK =
            java.util.regex.Pattern.compile("^\\[[^\\]]*\\]\\([^)]*\\)$");

    private static final java.util.regex.Pattern MD_TABLE_ROW =
            java.util.regex.Pattern.compile("^\\|.*\\|$");

    /** JSON：一行一个键值对，或者单独一行括号。带中文注释也照样算。 */
    private static final java.util.regex.Pattern JSON_KEY = java.util.regex.Pattern.compile(
            "^\"[^\"\\\\]*\"\\s*:\\s*(\"[^\"]*\"|-?\\d|\\[|\\{|true|false|null)");

    private static final java.util.regex.Pattern JSON_BRACE =
            java.util.regex.Pattern.compile("^[\\[{\\]}],?$");

    /** 整段里出现过键值行（可能不在第一行）——用来给拼出来的代码猜 JSON。 */
    private static final java.util.regex.Pattern JSON_ANY = java.util.regex.Pattern.compile(
            "(?m)^\\s*\"[^\"\\\\]*\"\\s*:\\s*(\"[^\"]*\"|-?\\d|\\[|\\{|true|false|null)");

    /** 行尾注释：{@code java 语句; // 中文说明}、{@code sql -- 中文说明}。 */
    private static final java.util.regex.Pattern TAIL_COMMENT =
            java.util.regex.Pattern.compile("\\s+(//|--\\s).*$");

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

            // 连续 ≥3 行才收：2 行会把「1kb = 1024byte。」这种两条换算并列的正文也收进去
            if (run.size() >= 3) {
                // 这些行是从段落里拼出来的，本身没有缩进，按大括号重排一下才看得懂层次。
                // JSON 不重排：`}` 和 `]` 的层级本来就对，重排反而错位。
                boolean json = JSON_ANY.matcher(String.join("\n", run)).find();
                String joined = json ? String.join("\n", run) : String.join("\n", reindent(run));
                out.append("```").append(json ? "json" : guessLang(joined)).append('\n')
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

    /**
     * 按大括号给拼出来的代码重排缩进（4 空格一层）。
     * 以 {@code }} 开头的行先退一级再输出；lua 的 then/do 当开括号、end 当闭括号。
     */
    static List<String> reindent(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int level = 0;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) {
                out.add("");
                continue;
            }
            boolean closerStart = t.startsWith("}") || t.equals("end") || t.startsWith("end ");
            if (closerStart) {
                level = Math.max(0, level - 1);
            }
            out.add("    ".repeat(level) + t);

            int net = count(t, '{') - count(t, '}');
            if (closerStart) {
                net += 1; // 开头那个闭括号已经退过了，别重复算
            }
            if (t.endsWith(" then") || t.endsWith(" do") || t.equals("then") || t.equals("do")) {
                net += 1;
            }
            level = Math.max(0, level + net);
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
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
        // 分隔线、Markdown 标题、图片、链接、表格都不算代码行
        if (s.matches("^[-=*_]{3,}$") || s.matches("^#{1,6}\\s.*")) {
            return false;
        }
        if (MD_IMAGE.matcher(s).find() || MD_LINK.matcher(s).matches() || MD_TABLE_ROW.matcher(s).matches()) {
            return false;
        }
        // JSON 放在「含中文就不算代码」之前：键值行后面的中文注释是正常写法
        if (JSON_KEY.matcher(s).find() || JSON_BRACE.matcher(s).matches()) {
            return true;
        }
        if (s.startsWith("//") || s.startsWith("#") || s.startsWith("--")
                || s.startsWith("/*") || s.startsWith("@")) {
            return true;
        }
        // 结尾的中文标点、字符串字面量里的中文、行尾注释里的中文都不算「这段是中文散文」，
        // 所以先去掉再判断，避免把 System.out.println("最终结果：" + i); // 打印 判成正文
        String probe = s.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'", "\"\"");
        probe = TAIL_COMMENT.matcher(probe).replaceFirst("");
        probe = probe.replaceAll("[\\s；;。.]+$", "");
        if (probe.isEmpty() || probe.length() > 200 || CJK.matcher(probe).find()) {
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
                // 语境 = 这一整段（最近的块级祖先）的正文，用来给同一个术语挑义项
                List<Node> parts = split(textNode.getLiteral(), used, textOf(blockOf(textNode)));
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

    /** 往上找到最近的块（段落 / 列表项 / 引用 / 表格单元格），拿它的整段文字当语境。 */
    private static Node blockOf(Node node) {
        Node n = node;
        while (n != null && !(n instanceof org.commonmark.node.Block)) {
            n = n.getParent();
        }
        return n == null ? node : n;
    }

    private List<Node> split(String literal, Set<String> used, String context) {
        List<Node> parts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        boolean touched = false;

        while (i < literal.length()) {
            GlossaryService.Match hit = glossary.matchAt(literal, i, used, context);
            if (hit != null) {
                if (buffer.length() > 0) {
                    parts.add(new Text(buffer.toString()));
                    buffer.setLength(0);
                }
                parts.add(new TermNode(hit.displayText(), hit.entry()));
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

    /**
     * 提示块（{@code >} 引用）按<b>第一段开头</b>的提词给个色调，规则写在 app.css 里：
     * 回答 / 示例 / 注意 三种，其余留默认暖米。
     * <p>
     * 只看开头 12 个字：正文里顺带提一句「回答」不该把整块染绿；
     * 长段落（比如整段引文）开头不是提词的一律走默认色。
     */
    private String calloutTone(BlockQuote quote) {
        Node first = quote.getFirstChild();
        while (first != null && !(first instanceof Paragraph)) {
            first = first.getNext();
        }
        if (first == null) {
            return null;
        }
        String head = textOf(first);
        if (head.length() > 12) {
            head = head.substring(0, 12);
        }
        if (startsWithAny(head, "注意", "警告", "切记", "务必", "别忘", "踩坑", "风险", "小心")) {
            return "warn";
        }
        if (startsWithAny(head, "回答", "答案", "结论", "总结")) {
            return "answer";
        }
        if (startsWithAny(head, "示例", "举例", "例子", "代码", "用法", "使用方式")) {
            return "example";
        }
        return null;
    }

    private static boolean startsWithAny(String head, String... words) {
        for (String w : words) {
            if (head.contains(w)) {
                return true;
            }
        }
        return false;
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