package cn.bytelove.notes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * project_meta.doc 的读法。原始 JSON 是中英双语、action/result 是数组，
 * 应用里只说中文，所以这里统一取 zh，缺了再回落 en。
 */
public record ProjectView(
        String slug,
        String title,
        String subtitle,
        String role,
        String period,
        List<String> stack,
        String problem,
        List<Item> actions,
        List<String> results,
        String repo,
        String diagram,
        List<Shot> shots) {

    public record Item(String text, String note) {
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
        List<Item> actions = new ArrayList<>();
        List<String> actionTexts = strings(root, "action");
        JsonNode notesZh = root.path("notes").path("zh");
        for (int i = 0; i < actionTexts.size(); i++) {
            JsonNode note = notesZh.path(String.valueOf(i));
            actions.add(new Item(actionTexts.get(i), note.isTextual() ? note.asText() : null));
        }

        List<Shot> shots = new ArrayList<>();
        JsonNode shotNodes = root.path("shots");
        if (shotNodes.isArray()) {
            for (JsonNode s : shotNodes) {
                shots.add(new Shot(s.path("src").asText(null), text(s, "caption")));
            }
        }

        return new ProjectView(
                root.path("slug").asText(null),
                text(root, "title"),
                text(root, "subtitle"),
                text(root, "role"),
                plain(root, "period"),
                strings(root, "stack"),
                text(root, "problem"),
                actions,
                strings(root, "result"),
                plain(root, "repo"),
                plain(root, "diagram"),
                shots);
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
