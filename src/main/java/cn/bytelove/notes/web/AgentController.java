package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.TagService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站内助手：浏览器把自己的 DeepSeek key 放在 X-Agent-Key 里发过来，
 * 服务端只用于这一次上游请求，不落库、不写日志。工具只操作当前登录用户自己的条目。
 * <p>
 * 写入不直接发生：create/update 只生成 proposal 存在内存里，等前端点「写入」再落库。
 */
@Controller
public class AgentController {

    private static final String MODEL = "deepseek-chat";
    /** 系统提示词决定它什么时候查、什么时候给建议；写操作永远只生成 proposal。 */
    private static final String SYSTEM = """
            你是笔记站的站内助手，只服务当前登录用户。
            需要找资料时调用 search_notes / read_note；要新建或修改笔记时调用 create_note / update_note。
            这两个工具只生成待用户确认的建议，不要声称已经写入，也不要在回复里重复整篇正文。
            回答用中文，简短直接。
            """;
    private static final int MAX_ROUNDS = 6;
    private static final int MAX_BODY = 200_000;
    private static final int MAX_TITLE = 200;
    private static final int READ_CLIP = 20_000;
    private static final long PROPOSAL_TTL = 30 * 60 * 1000L;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${notes.agent.upstream:https://api.deepseek.com/chat/completions}")
    private String upstream;

    private final ContentItemRepository items;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ViewerService viewers;
    private final TagService tags;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();

    public AgentController(ContentItemRepository items, JdbcTemplate jdbc, ObjectMapper mapper,
                           ViewerService viewers, TagService tags) {
        this.items = items;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.viewers = viewers;
        this.tags = tags;
    }

    public record Msg(String role, String content) {
    }

    public record ChatIn(List<Msg> messages, String mode, String kind, String contextSlug) {
    }

    public record ApplyIn(String id) {
    }

    private record Proposal(String id, long ownerId, String action, String slug, String title,
                            String body, String kind, String visibility, String summary,
                            String oldBody, long expiresAt) {
    }

    @PostMapping("/api/agent/chat")
    @ResponseBody
    public Map<String, Object> chat(@RequestHeader(name = "X-Agent-Key", required = false) String key,
                                    Authentication auth,
                                    @RequestBody ChatIn in) throws Exception {
        long userId = requireUser(auth);
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未填写 API Key");
        }
        if (in == null || in.messages() == null || in.messages().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 不能为空");
        }

        List<Map<String, Object>> apiMessages = new ArrayList<>();
        int from = Math.max(0, in.messages().size() - 20);
        for (Msg m : in.messages().subList(from, in.messages().size())) {
            String role = m.role() == null ? "" : m.role().trim();
            if (!Set.of("user", "assistant").contains(role)) {
                continue;
            }
            String content = m.content() == null ? "" : m.content();
            if (content.length() > 20_000) {
                content = content.substring(0, 20_000);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("content", content);
            apiMessages.add(row);
        }
        if (apiMessages.isEmpty() || !"user".equals(apiMessages.get(apiMessages.size() - 1).get("role"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最后一条必须是 user");
        }
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("role", "system");
        sys.put("content", SYSTEM + "\n" + instruction(userId, in));
        apiMessages.add(0, sys);

        long now = System.currentTimeMillis();
        proposals.entrySet().removeIf(e -> e.getValue().expiresAt() < now);

        List<Map<String, Object>> made = new ArrayList<>();
        List<String> toolsUsed = new ArrayList<>();
        String reply = "";
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", MODEL);
            body.put("messages", apiMessages);
            body.put("tools", toolDefs());
            body.put("tool_choice", "auto");
            JsonNode resp = call(key, body);

            JsonNode message = resp.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            JsonNode calls = message.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                reply = content;
                break;
            }
            reply = content;
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", content);
            assistant.put("tool_calls", mapper.convertValue(calls, List.class));
            apiMessages.add(assistant);

            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                String name = call.path("function").path("name").asText("");
                JsonNode args = parseArgs(call.path("function").path("arguments").asText("{}"));
                Map<String, Object> result = runTool(name, args, userId, made,
                        "create".equals(in.mode()) ? in.kind() : null);
                if (!toolsUsed.contains(name)) {
                    toolsUsed.add(name);
                }
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", mapper.writeValueAsString(result));
                apiMessages.add(toolMsg);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("proposals", made);
        out.put("tools", toolsUsed);
        return out;
    }

    @PostMapping("/api/agent/apply")
    @ResponseBody
    @Transactional
    public Map<String, Object> apply(Authentication auth, @RequestBody ApplyIn in) {
        long userId = requireUser(auth);
        long now = System.currentTimeMillis();
        proposals.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        Proposal p = in == null || in.id() == null ? null : proposals.get(in.id());
        if (p == null || p.ownerId() != userId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这条建议不存在或已过期");
        }
        proposals.remove(p.id());

        ContentItem saved;
        if ("create".equals(p.action())) {
            ContentKind kind = kindOf(p.kind());
            String slug = p.slug();
            if (items.existsByOwnerIdAndSlug(userId, slug)) {
                slug = freeSlug(userId);
            }
            ContentItem item = new ContentItem(userId, kind, slug,
                    cleanTitle(p.title()), summaryOf(p.summary(), p.body()), bodyOf(p.body()),
                    visibilityOf(p.visibility()));
            item.setSortOrder(nextSortOrder(userId, kind));
            item.setRecommended(item.getVisibility() == Visibility.PUBLIC);
            saved = items.save(item);
        } else {
            ContentItem item = mine(userId, p.slug());
            snapshot(item);
            if (p.title() != null && !p.title().isBlank()) {
                item.setTitle(cleanTitle(p.title()));
            }
            if (p.body() != null) {
                item.setBody(bodyOf(p.body()));
            }
            if (p.summary() != null) {
                item.setSummary(summaryOf(p.summary(), item.getBody()));
            }
            saved = items.save(item);
        }
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("slug", saved.getSlug());
        out.put("url", urlOf(saved));
        return out;
    }

    /** 上游错误原样带状态回来，方便用户判断是 key 还是额度；key 只留首尾。 */
    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> onError(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("status", e.getStatusCode().value());
        body.put("error", e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    /**
     * 把面板上选的功能和当前正文页锁定的笔记拼进系统提示词：
     * 笔记正文只在这里带一次，后面的追问不用前端重复发。
     */
    private String instruction(long userId, ChatIn in) {
        StringBuilder sb = new StringBuilder();
        String mode = in.mode() == null ? "ask" : in.mode();
        String kind = in.kind() == null ? "" : in.kind().trim();
        String slug = in.contextSlug() == null ? "" : in.contextSlug().trim();
        switch (mode) {
            case "create" -> sb.append("用户点了「记一条」，要新建一条笔记，模块是")
                    .append(kindOf(kind).getLabel()).append("（kind=")
                    .append(kindOf(kind).name().toLowerCase(Locale.ROOT)).append("）。")
                    .append("调用 create_note 生成建议，标题和正文写完整。\n");
            case "edit" -> sb.append("用户点了「改这篇」，要修改当前笔记；只改用户提到的部分，调用 update_note。\n");
            case "search" -> sb.append("用户点了「搜笔记」，先调用 search_notes 找到相关条目，再回答。\n");
            default -> {
                if (slug.isEmpty()) {
                    sb.append("用户没有打开具体笔记，按普通提问回答，需要资料就调工具。\n");
                } else {
                    sb.append("用户点了「答疑」，优先基于下面这篇笔记回答；正文不够再调工具。\n");
                }
            }
        }
        if (!slug.isEmpty()) {
            items.findByOwnerIdAndSlug(userId, slug).ifPresent(item -> {
                String body = item.getBody() == null ? "" : item.getBody();
                if (body.length() > 8000) {
                    body = body.substring(0, 8000) + "\n（正文已截断）";
                }
                sb.append("用户当前正在看《").append(item.getTitle()).append("》（slug=")
                        .append(item.getSlug()).append("，kind=")
                        .append(item.getKind().name().toLowerCase(Locale.ROOT)).append("）。正文：\n")
                        .append(body).append('\n');
            });
        }
        return sb.toString();
    }

    // ---------- 工具 ----------

    private Map<String, Object> runTool(String name, JsonNode args, long userId,
                                        List<Map<String, Object>> made, String forcedKind) {
        return switch (name) {
            case "search_notes" -> search(args, userId);
            case "read_note" -> read(args, userId);
            case "create_note" -> create(args, userId, made, forcedKind);
            case "update_note" -> update(args, userId, made);
            default -> Map.of("error", "未知工具：" + name);
        };
    }

    private Map<String, Object> search(JsonNode args, long userId) {
        String q = args.path("query").asText("").trim();
        if (q.isEmpty()) {
            return Map.of("error", "query 不能为空");
        }
        String kind = args.path("kind").asText("").trim().toUpperCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder(
                "select slug, title, kind, updated_at from content_item"
                        + " where owner_id = ? and deleted_at is null"
                        + " and (title ilike ? or body ilike ?)");
        List<Object> params = new ArrayList<>(List.of(userId, "%" + q + "%", "%" + q + "%"));
        if (!kind.isEmpty() && Set.of("NOTE", "PROJECT", "BAGU", "INTERVIEW", "ALGO").contains(kind)) {
            sql.append(" and kind = ?");
            params.add(kind);
        }
        sql.append(" order by updated_at desc nulls last limit 8");
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", rs.getString("slug"));
            row.put("title", rs.getString("title"));
            row.put("kind", rs.getString("kind").toLowerCase(Locale.ROOT));
            row.put("updatedAt", String.valueOf(rs.getObject("updated_at")));
            return row;
        }, params.toArray());
        return Map.of("results", rows);
    }

    private Map<String, Object> read(JsonNode args, long userId) {
        String slug = args.path("slug").asText("").trim();
        if (slug.isEmpty()) {
            return Map.of("error", "slug 不能为空");
        }
        return items.findByOwnerIdAndSlug(userId, slug).map(item -> {
            String body = item.getBody() == null ? "" : item.getBody();
            boolean cut = body.length() > READ_CLIP;
            if (cut) {
                body = body.substring(0, READ_CLIP) + "\n\n（正文超过 20000 字符，已截断）";
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("slug", item.getSlug());
            out.put("title", item.getTitle());
            out.put("kind", item.getKind().name().toLowerCase(Locale.ROOT));
            out.put("visibility", item.getVisibility().name());
            out.put("body", body);
            return out;
        }).orElse(Map.of("error", "没有这篇笔记"));
    }

    private Map<String, Object> create(JsonNode args, long userId, List<Map<String, Object>> made,
                                       String forcedKind) {
        String title;
        String body;
        try {
            title = cleanTitle(args.path("title").asText(""));
            body = bodyOf(args.path("body").asText(""));
        } catch (ResponseStatusException e) {
            return Map.of("error", e.getReason() == null ? "参数不合法" : e.getReason());
        }
        String kind = forcedKind != null && !forcedKind.isBlank()
                ? kindOf(forcedKind).name()
                : kindOf(args.path("kind").asText("")).name();
        String visibility = visibilityOf(args.path("visibility").asText("")).name();
        String summary = args.path("summary").asText("");
        Proposal p = new Proposal(java.util.UUID.randomUUID().toString(), userId, "create",
                freeSlug(userId), title, body, kind, visibility, summary, null,
                System.currentTimeMillis() + PROPOSAL_TTL);
        proposals.put(p.id(), p);
        made.add(view(p));
        return Map.of("ok", true, "proposal_id", p.id(), "action", "create",
                "message", "已生成待确认的新建建议，等用户在页面上点写入");
    }

    private Map<String, Object> update(JsonNode args, long userId, List<Map<String, Object>> made) {
        String slug = args.path("slug").asText("").trim();
        if (slug.isEmpty()) {
            return Map.of("error", "slug 不能为空");
        }
        ContentItem item = items.findByOwnerIdAndSlug(userId, slug).orElse(null);
        if (item == null) {
            return Map.of("error", "没有这篇笔记：" + slug);
        }
        String title = args.hasNonNull("title") ? args.path("title").asText("") : null;
        String body = args.hasNonNull("body") ? args.path("body").asText("") : null;
        String summary = args.hasNonNull("summary") ? args.path("summary").asText("") : null;
        if (title != null && title.isBlank()) {
            title = null;
        }
        if (body != null && body.isBlank()) {
            body = null;
        }
        if (summary != null && summary.isBlank()) {
            summary = null;
        }
        if (title == null && body == null && summary == null) {
            return Map.of("error", "title / body / summary 至少要改一项");
        }
        String oldBody = item.getBody() == null ? "" : item.getBody();
        if (oldBody.length() > READ_CLIP) {
            oldBody = oldBody.substring(0, READ_CLIP);
        }
        Proposal p = new Proposal(java.util.UUID.randomUUID().toString(), userId, "update",
                item.getSlug(), title, body, item.getKind().name(),
                item.getVisibility().name(), summary, oldBody,
                System.currentTimeMillis() + PROPOSAL_TTL);
        proposals.put(p.id(), p);
        made.add(view(p));
        return Map.of("ok", true, "proposal_id", p.id(), "action", "update",
                "message", "已生成待确认的修改建议，等用户在页面上点写入");
    }

    private static Map<String, Object> view(Proposal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("action", p.action());
        out.put("slug", p.slug());
        out.put("title", p.title());
        out.put("body", p.body());
        out.put("kind", p.kind() == null ? null : p.kind().toLowerCase(Locale.ROOT));
        out.put("visibility", p.visibility());
        out.put("summary", p.summary());
        out.put("oldBody", p.oldBody());
        return out;
    }

    // ---------- 上游 ----------

    private JsonNode call(String key, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(upstream))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上游连接失败：" + e.getMessage());
        }
        if (resp.statusCode() != 200) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "上游返回 " + resp.statusCode() + "（key " + mask(key) + "）");
        }
        return mapper.readTree(resp.body());
    }

    private JsonNode parseArgs(String raw) {
        try {
            JsonNode node = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private List<Map<String, Object>> toolDefs() {
        List<Map<String, Object>> defs = new ArrayList<>();
        defs.add(fn("search_notes", "在用户自己的笔记里按标题或正文关键词搜索",
                Map.of("query", Map.of("type", "string"),
                        "kind", Map.of("type", "string", "enum", List.of("note", "project", "bagu", "interview", "algo"))),
                List.of("query")));
        defs.add(fn("read_note", "读取用户自己的一篇笔记正文",
                Map.of("slug", Map.of("type", "string")), List.of("slug")));
        defs.add(fn("create_note", "生成一篇新笔记的待确认建议，不直接写入",
                Map.of("title", Map.of("type", "string"),
                        "body", Map.of("type", "string"),
                        "kind", Map.of("type", "string", "enum", List.of("note", "project", "bagu", "interview", "algo")),
                        "visibility", Map.of("type", "string", "enum", List.of("PUBLIC", "MEMBER", "PRIVATE")),
                        "summary", Map.of("type", "string")),
                List.of("title", "body")));
        defs.add(fn("update_note", "生成一篇已有笔记的修改建议，不直接写入",
                Map.of("slug", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "body", Map.of("type", "string"),
                        "summary", Map.of("type", "string")),
                List.of("slug")));
        return defs;
    }

    private static Map<String, Object> fn(String name, String desc,
                                          Map<String, Object> props, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", required);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", params);
        return Map.of("type", "function", "function", f);
    }

    // ---------- 小工具 ----------

    private long requireUser(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer == null || viewer.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return viewer.userId();
    }

    private ContentItem mine(long userId, String slug) {
        return items.findByOwnerIdAndSlug(userId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这篇笔记"));
    }

    private String freeSlug(long userId) {
        for (int n = 0; n < 8; n++) {
            String slug = "ai-" + STAMP.format(Instant.now()) + "-"
                    + HexFormat.of().formatHex(randomBytes(4));
            if (!items.existsByOwnerIdAndSlug(userId, slug)) {
                return slug;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 slug 失败");
    }

    private int nextSortOrder(long userId, ContentKind kind) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(sort_order), 0) from content_item"
                        + " where owner_id = ? and kind = ? and deleted_at is null",
                Integer.class, userId, kind.name());
        return (max == null ? 0 : max) + 1;
    }

    private void snapshot(ContentItem item) {
        jdbc.update("insert into content_revision"
                        + " (item_id, title, summary, body, visibility, parent_id, source)"
                        + " values (?, ?, ?, ?, ?, ?, 'AGENT')",
                item.getId(), item.getTitle(), item.getSummary(), item.getBody(),
                item.getVisibility().name(), item.getParentId());
    }

    private String urlOf(ContentItem item) {
        String base = switch (item.getKind()) {
            case BAGU -> "/bagu";
            case INTERVIEW -> "/interview";
            case ALGO -> "/algo";
            case PROJECT -> "/projects";
            default -> "/notes";
        };
        return "/app" + base + "/" + item.getSlug();
    }

    private static String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 不能为空");
        }
        String t = title.trim();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    private static String bodyOf(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body 不能为空");
        }
        String b = body.strip();
        if (b.length() > MAX_BODY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文超过 20 万字符");
        }
        return b;
    }

    private static String summaryOf(String summary, String body) {
        if (summary != null && !summary.isBlank()) {
            String s = summary.trim();
            return s.length() > 500 ? s.substring(0, 500) : s;
        }
        String text = (body == null ? "" : body).replaceAll("[#>*`\\[\\]()!_|-]+", " ")
                .replaceAll("\\s+", " ").trim();
        return text.length() > 90 ? text.substring(0, 90) : text;
    }

    private static ContentKind kindOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return ContentKind.NOTE;
        }
        try {
            return ContentKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ContentKind.NOTE;
        }
    }

    private static Visibility visibilityOf(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return Visibility.PRIVATE;
        }
        try {
            return Visibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "visibility 只能是 PUBLIC / MEMBER / PRIVATE");
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }
}
