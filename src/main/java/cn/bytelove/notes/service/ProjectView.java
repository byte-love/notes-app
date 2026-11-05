package cn.bytelove.notes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * project_meta.doc 的读法。原始 JSON 是中英双语、action/result 是数组，
 * 应用里只说中文，所以这里统一取 zh，缺了再回落 en。
 * <p>
 * 另外把正文里的 `代码` 渲染成 {@code <code>}：项目页那条目是一行行短句，
 * 里面夹的类名 / 命令 / 参数很多，页面上一堆反引号很难看。转换时**除 code 之外一律 HTML 转义**，
 * 模板里才能安全地用 {@code th:utext}。
 */
public record ProjectView(
        String slug,
        String title,
        String subtitle,
        String role,
        String period,
        List<String> stack,
        String problem,
        String problemHtml,
        List<Item> actions,
        List<Result> results,
        String repo,
        String diagram,
        List<Shot> shots) {

    /** 一条「我做了什么」：原文 + 渲染好的 HTML + 可选注解（注解同样有 HTML 版）。 */
    public record Item(String text, String textHtml, String note, String noteHtml) {

        public boolean hasNote() {
            return note != null && !note.isBlank();
        }
    }

    /**
     * 一条结果。里面的 {@code →} 会包成 {@code <span class="arr">}，页面上把「改之前 → 改之后」
     * 那个箭头单独上色——结果文案几乎都靠这个箭头表达前后对比，不处理就只是普通一行字。
     */
    public record Result(String text, String textHtml) {
    }

    public record Shot(String src, String caption) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Optional<ProjectView> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(from(MAPPER.readTree(json)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static ProjectView from(JsonNode root) {
        List<String> actionTexts = strings(root, "action");
        List<String> actionNotes = notesZh(root);
        List<Item> actions = new ArrayList<>();
        for (int i = 0; i < actionTexts.size(); i++) {
            String text = actionTexts.get(i);
            String note = i < actionNotes.size() ? actionNotes.get(i) : null;
            actions.add(new Item(text, inline(text), note, inline(note)));
        }

        List<Result> results = new ArrayList<>();
        for (String r : strings(root, "result")) {
            results.add(new Result(r, inline(r).replace("→", "<span class=\"arr\">→</span>")));
        }

        List<Shot> shots = new ArrayList<>();
        JsonNode shotNodes = root.path("shots");
        if (shotNodes.isArray()) {
            for (JsonNode s : shotNodes) {
                shots.add(new Shot(s.path("src").asText(null), text(s, "caption")));
            }
        }

        String problem = text(root, "problem");
        return new ProjectView(
                root.path("slug").asText(null),
                text(root, "title"),
                text(root, "subtitle"),
                text(root, "role"),
                plain(root, "period"),
                strings(root, "stack"),
                problem,
                inline(problem),
                actions,
                results,
                plain(root, "repo"),
                plain(root, "diagram"),
                shots);
    }

    /** notes 是 {"zh": {"0": "…", "1": "…"}} 这种按下标给注解，缺的留空。 */
    private static List<String> notesZh(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode notes = root.path("notes").path("zh");
        if (!notes.isObject()) {
            return out;
        }
        int max = -1;
        var names = notes.fieldNames();
        while (names.hasNext()) {
            try {
                max = Math.max(max, Integer.parseInt(names.next()));
            } catch (NumberFormatException ignored) {
                // 键不是下标的（以后可能加别的注解）忽略掉
            }
        }
        for (int i = 0; i <= max; i++) {
            JsonNode n = notes.path(String.valueOf(i));
            out.add(n.isTextual() && !n.asText().isBlank() ? n.asText() : null);
        }
        return out;
    }

    /**
     * 把 `代码` 变成 {@code <code>}，其余字符（含引号、尖括号）一律转义。
     * 反引号不成对时按普通字符输出，不会把后面的内容吞掉。
     */
    static String inline(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length() + 24);
        int i = 0;
        while (i < raw.length()) {
            int open = raw.indexOf('`', i);
            int close = open < 0 ? -1 : raw.indexOf('`', open + 1);
            if (open < 0 || close < 0) {
                escape(raw, i, raw.length(), out);
                break;
            }
            escape(raw, i, open, out);
            out.append("<code>");
            escape(raw, open + 1, close, out);
            out.append("</code>");
            i = close + 1;
        }
        return out.toString();
    }

    private static void escape(String s, int from, int to, StringBuilder out) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
    }

    /** 双语字段取中文，没有就取英文。 */
    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return blank(n.asText()) ? null : n.asText();
        }
        for (String lang : new String[]{"zh", "en"}) {
            JsonNode v = n.get(lang);
            if (v != null && v.isTextual() && !blank(v.asText())) {
                return v.asText();
            }
        }
        return null;
    }

    /** 单语言字段（周期、仓库地址、图片路径）。 */
    private static String plain(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && n.isTextual() && !blank(n.asText()) ? n.asText() : null;
    }

    /** 字符串数组，可能是裸数组，也可能是 {zh:[],en:[]}。 */
    private static List<String> strings(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (n.isArray()) {
            return toList(n);
        }
        for (String lang : new String[]{"zh", "en"}) {
            JsonNode v = n.get(lang);
            if (v != null && v.isArray() && !v.isEmpty()) {
                return toList(v);
            }
        }
        return List.of();
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isTextual() && !blank(item.asText())) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
