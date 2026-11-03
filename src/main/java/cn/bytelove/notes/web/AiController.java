package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ApiTokenService;
import cn.bytelove.notes.service.TagService;
import cn.bytelove.notes.service.TrashService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 给本地 AI（MCP）用的写入接口。
 * <p>
 * 认证走 {@code Authorization: Bearer <个人 token>}，不用会话 cookie；每个请求都按 token 找到用户，
 * <b>所有增删改都限定在这个人自己的条目上</b>（按 owner_id 校验，别人的条目一律 404）。
 * <p>
 * 覆盖更新和删除都要求显式 confirm，且覆盖前会把旧版本写进 content_revision——
 * AI 判断可能出错，留底和服务端校验都不能靠它自觉。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** 一条笔记正文的上限，防止 AI 一次塞进来几兆。 */
    private static final int MAX_BODY = 200_000;
    private static final int MAX_TITLE = 200;
    /** 相似度阈值：标题二元组重合度，超过就提示「可能已有同主题」。 */
    private static final double SIMILAR = 0.5;
    private static final long MAX_UPLOAD = 8L * 1024 * 1024;
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());

    /** 每个用户 10 分钟内最多写几次，防止 AI 抽风刷库。 */
    private static final int WRITE_LIMIT = 60;
    private static final long WRITE_WINDOW = 10 * 60 * 1000L;
    private final Map<Long, List<Long>> writeLog = new HashMap<>();

    @Value("${notes.media-dir:/srv/data/uploads/media}")
    private String mediaDir;

    private final ContentItemRepository items;
    private final ApiTokenService tokens;
    private final TagService tags;
    private final TrashService trash;
    private final JdbcTemplate jdbc;

    public AiController(ContentItemRepository items, ApiTokenService tokens, TagService tags,
                        TrashService trash, JdbcTemplate jdbc) {
        this.items = items;
        this.tokens = tokens;
        this.tags = tags;
        this.trash = trash;
        this.jdbc = jdbc;
    }

    /** 新建或更新请求体；字段都可选，只有 title/body 是新建时必填。 */
    public record NoteIn(String title, String body, String summary, String slug,
                         String parent, String visibility, String kind, Boolean confirm) {
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(name = "Authorization", required = false) String auth) {
        Long userId = requireUser(auth);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("note", "增删改都只作用于你自己的条目");
        List<Map<String, Object>> counts = new ArrayList<>();
        for (ContentKind k : ContentKind.values()) {
            counts.add(Map.of("kind", k.name().toLowerCase(Locale.ROOT),
                    "count", items.countByOwnerIdAndKind(userId, k)));
        }
        out.put("counts", counts);
        return out;
    }

    /** 可以挂的目录：自己名下当过父节点的条目，带完整路径。 */
    @GetMapping("/folders")
    public List<Map<String, Object>> folders(@RequestHeader(name = "Authorization", required = false) String auth) {
        Long userId = requireUser(auth);
        List<ContentItem> mine = items.findByOwnerIdOrderByKindAscSortOrderAsc(userId);
        Set<Long> parentIds = new java.util.HashSet<>();
        for (ContentItem i : mine) {
            if (i.getParentId() != null) {
                parentIds.add(i.getParentId());
            }
        }
        Map<Long, ContentItem> byId = new HashMap<>();
        for (ContentItem i : mine) {
            byId.put(i.getId(), i);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem i : mine) {
            if (!parentIds.contains(i.getId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", i.getSlug());
            row.put("title", i.getTitle());
            row.put("path", pathOf(i, byId));
            row.put("kind", i.getKind().name().toLowerCase(Locale.ROOT));
            out.add(row);
        }
        return out;
    }

    /** 查重 / 找已有笔记：只搜自己的。 */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestHeader(name = "Authorization", required = false) String auth,
                                            @RequestParam(name = "q", required = false) String q) {
        Long userId = requireUser(auth);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem i : items.findByOwnerIdOrderByKindAscSortOrderAsc(userId)) {
            if (!needle.isEmpty()) {
                String hay = ((i.getTitle() == null ? "" : i.getTitle()) + " "
                        + (i.getSummary() == null ? "" : i.getSummary()) + " "
                        + (i.getBody() == null ? "" : i.getBody())).toLowerCase(Locale.ROOT);
                if (!hay.contains(needle)) {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", i.getSlug());
            row.put("title", i.getTitle());
            row.put("kind", i.getKind().name().toLowerCase(Locale.ROOT));
            row.put("summary", i.getSummary());
            row.put("updatedAt", i.getUpdatedAt() == null ? null : i.getUpdatedAt().toString());
            out.add(row);
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    @GetMapping("/notes/{slug}")
    public Map<String, Object> get(@RequestHeader(name = "Authorization", required = false) String auth,
                                   @PathVariable String slug, HttpServletRequest request) {
        Long userId = requireUser(auth);
        ContentItem item = mine(userId, slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug", item.getSlug());
        out.put("title", item.getTitle());
        out.put("summary", item.getSummary());
        out.put("body", item.getBody());
        out.put("kind", item.getKind().name().toLowerCase(Locale.ROOT));
        out.put("visibility", item.getVisibility().name());
        out.put("updatedAt", item.getUpdatedAt() == null ? null : item.getUpdatedAt().toString());
        out.put("url", urlOf(item, request));
        return out;
    }

    /** 新建一篇。永远不覆盖任何东西；标题像已有笔记时只在 warnings 里提示。 */
    @PostMapping("/notes")
    public Map<String, Object> create(@RequestHeader(name = "Authorization", required = false) String auth,
                                      @RequestBody NoteIn in, HttpServletRequest request) {
        Long userId = requireUser(auth);
        write(userId);
        String title = clean(in.title(), "标题");
        String body = bodyOf(in.body());
        ContentKind kind = kindOf(in.kind());
        ContentItem item = new ContentItem(userId, kind, freeSlug(userId),
                title, summaryOf(in.summary(), body), body, visibilityOf(in.visibility()));
        item.setParentId(parentIdOf(userId, in.parent()));
        item.setSortOrder(nextSortOrder(userId));
        item.setRecommended(false);
        ContentItem saved = items.save(item);
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("created", true);
        out.put("slug", saved.getSlug());
        out.put("url", urlOf(saved, request));
        out.put("tags", tags.of(List.of(saved.getId())).getOrDefault(saved.getId(), List.of()));
        out.put("similar", similar(userId, title, saved.getId()));
        return out;
    }

    /** 覆盖更新：必须 confirm=true，且先把旧版本存进 content_revision。 */
    @PutMapping("/notes/{slug}")
    public Map<String, Object> update(@RequestHeader(name = "Authorization", required = false) String auth,
                                      @PathVariable String slug, @RequestBody NoteIn in,
                                      HttpServletRequest request) {
        Long userId = requireUser(auth);
        write(userId);
        if (in == null || !Boolean.TRUE.equals(in.confirm())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "覆盖已有笔记必须带 confirm=true（请先把要覆盖的那篇标题告诉用户并得到同意）");
        }
        ContentItem item = mine(userId, slug);
        snapshot(item);
        if (in.title() != null && !in.title().isBlank()) {
            item.setTitle(clean(in.title(), "标题"));
        }
        if (in.body() != null) {
            String body = bodyOf(in.body());
            item.setBody(body);
            if (in.summary() == null) {
                item.setSummary(summaryOf(null, body));
            }
        }
        if (in.summary() != null) {
            item.setSummary(summaryOf(in.summary(), item.getBody()));
        }
        if (in.visibility() != null && !in.visibility().isBlank()) {
            item.setVisibility(visibilityOf(in.visibility()));
        }
        if (in.parent() != null) {
            item.setParentId(parentIdOf(userId, in.parent()));
        }
        ContentItem saved = items.save(item);
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("created", false);
        out.put("slug", saved.getSlug());
        out.put("url", urlOf(saved, request));
        out.put("note", "旧版本已存快照，改坏了可以在 /app/trash 之外由管理员从 content_revision 恢复");
        return out;
    }

    /** 删除：软删进回收站（30 天），同样要 confirm=true。 */
    @DeleteMapping("/notes/{slug}")
    public Map<String, Object> delete(@RequestHeader(name = "Authorization", required = false) String auth,
                                      @PathVariable String slug,
                                      @RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        Long userId = requireUser(auth);
        write(userId);
        ContentItem item = mine(userId, slug);
        if (!confirm) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "删除必须带 confirm=true（请先把要删的那篇标题告诉用户并得到同意）");
        }
        trash.softDelete(item.getId(), userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("deleted", item.getSlug());
        out.put("note", "已移入回收站，30 天内可以在站点的「回收站」里恢复");
        return out;
    }

    /** 插图：和正文编辑器同一个上传目录，返回能直接写进 Markdown 的地址。 */
    @PostMapping("/upload")
    public Map<String, String> upload(@RequestHeader(name = "Authorization", required = false) String auth,
                                      @RequestParam("file") MultipartFile file,
                                      HttpServletRequest request) throws IOException {
        requireUser(auth);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有收到文件");
        }
        if (file.getSize() > MAX_UPLOAD) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片超过 8MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只收 png / jpg / gif / webp");
        }
        Path dir = Paths.get(mediaDir, "notes");
        Files.createDirectories(dir);
        String name = HexFormat.of().formatHex(randomBytes(8)) + "." + ext;
        file.transferTo(dir.resolve(name).toFile());
        return Map.of("url", request.getContextPath() + "/media/notes/" + name);
    }

    /** 出错时回 JSON 而不是整页 HTML，AI 侧才好读。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onError(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("status", e.getStatusCode().value());
        body.put("error", e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    // ---------- 下面都是内部小工具 ----------

    private Long requireUser(String auth) {
        String token = auth == null ? null : auth.replaceFirst("(?i)^Bearer\\s+", "").trim();
        return tokens.userIdOf(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "没有带上有效的 Authorization: Bearer <token>"));
    }

    /** 只找自己名下的条目；别人的一律当不存在。 */
    private ContentItem mine(Long userId, String slug) {
        return items.findByOwnerIdAndSlug(userId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这篇笔记"));
    }

    private static String clean(String title, String what) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "不能为空");
        }
        String t = title.trim();
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t;
    }

    private static String bodyOf(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        String b = body.strip();
        if (b.length() > MAX_BODY) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "正文超过 20 万字符");
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

    /**
     * 可见性没有默认值：必须由用户先决定。
     * 传空就报错并说清楚三档是什么意思，逼着 AI 回去问一句，而不是替用户拍板。
     */
    private static Visibility visibilityOf(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "缺 visibility：请先问用户这篇给什么权限，再带上参数重试 ——"
                            + " PUBLIC 任何用户可见（会进技术广场）；"
                            + " MEMBER 登录可见（只有用户把链接发给别人、对方登录后才能看，不进技术广场）；"
                            + " PRIVATE 仅自己");
        }
        try {
            return Visibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "visibility 只能是 PUBLIC / MEMBER / PRIVATE");
        }
    }

    private Long parentIdOf(Long userId, String parentSlug) {
        if (parentSlug == null || parentSlug.isBlank()) {
            return null;
        }
        return mine(userId, parentSlug.trim()).getId();
    }

    private int nextSortOrder(Long userId) {
        int max = 0;
        for (ContentItem i : items.findByOwnerIdAndKindOrderBySortOrderAsc(userId, ContentKind.NOTE)) {
            max = Math.max(max, i.getSortOrder());
        }
        return max + 1;
    }

    /** AI 不用自己想 slug：`ai-20261103-xxxxxxxx`，保证唯一。 */
    private String freeSlug(Long userId) {
        for (int n = 0; n < 8; n++) {
            String slug = "ai-" + STAMP.format(Instant.now()) + "-" + HexFormat.of().formatHex(randomBytes(4));
            if (!items.existsByOwnerIdAndSlug(userId, slug)) {
                return slug;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 slug 失败，请重试");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private String urlOf(ContentItem item, HttpServletRequest request) {
        String base;
        switch (item.getKind()) {
            case BAGU -> base = "/bagu";
            case ALGO -> base = "/algo";
            case PROJECT -> base = "/projects";
            default -> base = "/notes";
        }
        return request.getContextPath() + base + "/" + item.getSlug();
    }

    private static String pathOf(ContentItem item, Map<Long, ContentItem> byId) {
        StringBuilder sb = new StringBuilder(item.getTitle());
        Long parent = item.getParentId();
        int guard = 0;
        while (parent != null && guard++ < 8) {
            ContentItem p = byId.get(parent);
            if (p == null) {
                break;
            }
            sb.insert(0, p.getTitle() + " / ");
            parent = p.getParentId();
        }
        return sb.toString();
    }

    /** 覆盖前留一份旧版本。 */
    private void snapshot(ContentItem item) {
        jdbc.update("insert into content_revision"
                        + " (item_id, title, summary, body, visibility, parent_id, source)"
                        + " values (?, ?, ?, ?, ?, ?, 'AI')",
                item.getId(), item.getTitle(), item.getSummary(), item.getBody(),
                item.getVisibility().name(), item.getParentId());
    }

    /** 标题相近的自有笔记：让 AI 去问用户「是不是同一篇」，而不是自己动手。 */
    private List<Map<String, Object>> similar(Long userId, String title, Long excludeId) {
        String base = normTitle(title);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentItem i : items.findByOwnerIdOrderByKindAscSortOrderAsc(userId)) {
            if (i.getId().equals(excludeId)) {
                continue;
            }
            double score = overlap(base, normTitle(i.getTitle()));
            if (score < SIMILAR) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", i.getSlug());
            row.put("title", i.getTitle());
            row.put("updatedAt", i.getUpdatedAt() == null ? null : i.getUpdatedAt().toString());
            row.put("score", Math.round(score * 100) / 100.0);
            out.add(row);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -((Double) m.get("score"))));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static String normTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        return t.replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）【】、]+", "");
    }

    /** 标题二元组 Jaccard 相似度，纯字符级，不猜语义。 */
    private static double overlap(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String s : sa) {
            if (sb.contains(s)) {
                common++;
            }
        }
        return (2.0 * common) / (sa.size() + sb.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /** 写操作的简单限流：10 分钟内最多 60 次。 */
    private void write(Long userId) {
        long now = System.currentTimeMillis();
        synchronized (writeLog) {
            List<Long> log = writeLog.computeIfAbsent(userId, k -> new ArrayList<>());
            log.removeIf(t -> now - t > WRITE_WINDOW);
            if (log.size() >= WRITE_LIMIT) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "写太频繁了，等几分钟再试");
            }
            log.add(now);
        }
    }
}
