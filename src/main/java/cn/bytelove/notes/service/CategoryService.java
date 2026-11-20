package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.NoteCategory;
import cn.bytelove.notes.repo.NoteCategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 「我的笔记」的分类：每个用户一组页签，默认五个跟着原来五个分区建好。
 * <p>
 * 分类只决定「放在哪个页签」，不影响条目本身的 kind（模板、详情路由、配色仍按 kind 走），
 * 所以删分类不会丢内容：条目只是回到「未分类」。
 */
@Service
public class CategoryService {

    /**
     * 默认分类只是「迁移时给老账号补的那五个」，用来记住 kind 和页签的对应关系
     * （新建内容落到哪个分类、老地址 /notes 指向哪里）。新账号一个都不建，由用户自己决定要几个页签。
     */
    public static final String UNCATEGORIZED = "uncategorized";

    private static final int MAX_NAME = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NoteCategoryRepository categories;
    private final JdbcTemplate jdbc;

    public CategoryService(NoteCategoryRepository categories, JdbcTemplate jdbc) {
        this.categories = categories;
        this.jdbc = jdbc;
    }

    public List<NoteCategory> of(Long ownerId) {
        return ownerId == null ? List.of() : categories.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId);
    }

    public Optional<NoteCategory> find(Long ownerId, String slug) {
        if (ownerId == null || slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return categories.findByOwnerIdAndSlug(ownerId, slug.trim());
    }

    /** 某个 kind 的默认分类：新建条目没指定分类时落在这里；没有就留空（进「未分类」）。 */
    public Optional<NoteCategory> defaultOf(Long ownerId, ContentKind kind) {
        if (ownerId == null || kind == null) {
            return Optional.empty();
        }
        return categories.findFirstByOwnerIdAndKindAndBuiltinTrue(ownerId, kind);
    }

    /** 「我的笔记」该往哪走：第一个页签；一个分类都没有时退回老列表页。 */
    public String homePath(Long ownerId) {
        if (ownerId == null) {
            return "/notes";
        }
        return categories.findFirstByOwnerIdOrderBySortOrderAscIdAsc(ownerId)
                .map(c -> "/c/" + c.getSlug())
                .orElse("/notes");
    }

    /** 每个分类里有多少条（未分类记在 key = null 上）。 */
    public Map<Long, Long> counts(Long ownerId) {
        Map<Long, Long> out = new HashMap<>();
        jdbc.query("select category_id, count(*) as n from content_item"
                        + " where owner_id = ? and deleted_at is null group by category_id",
                rs -> {
                    long id = rs.getLong("category_id");
                    Long key = rs.wasNull() ? null : id;
                    out.put(key, rs.getLong("n"));
                }, ownerId);
        return out;
    }

    /**
     * 列表页的页签。登录用户看自己的分类，末尾在还有没归类的条目时补一个「未分类」。
     */
    public List<Tab> tabs(Long ownerId, String activeSlug) {
        List<Tab> out = new ArrayList<>();
        if (ownerId == null) {
            return out;
        }
        Map<Long, Long> counts = counts(ownerId);
        for (NoteCategory c : of(ownerId)) {
            boolean active = activeSlug != null && activeSlug.equals(c.getSlug());
            out.add(new Tab(c.getName(), "/c/" + c.getSlug(), active,
                    counts.getOrDefault(c.getId(), 0L), c.isBuiltin()));
        }
        long rest = counts.getOrDefault(null, 0L);
        if (rest > 0) {
            out.add(new Tab("未分类", "/c/" + UNCATEGORIZED,
                    UNCATEGORIZED.equals(activeSlug), rest, false));
        }
        return out;
    }

    public record Tab(String name, String href, boolean active, long count, boolean builtin) {
    }

    @Transactional
    public NoteCategory create(Long ownerId, String rawName) {
        String name = cleanName(rawName);
        List<NoteCategory> all = categories.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId);
        int sort = 0;
        for (NoteCategory c : all) {
            sort = Math.max(sort, c.getSortOrder() + 1);
        }
        NoteCategory c = new NoteCategory(ownerId, freeSlug(ownerId, name), name, ContentKind.NOTE, sort, false);
        return categories.save(c);
    }

    @Transactional
    public NoteCategory rename(Long ownerId, Long id, String rawName) {
        NoteCategory c = owned(ownerId, id);
        c.setName(cleanName(rawName));
        return categories.save(c);
    }

    /** 上移 / 下移一位：和相邻那条换 sort_order，整组重新编号，避免出现相同的号。 */
    @Transactional
    public void move(Long ownerId, Long id, boolean up) {
        List<NoteCategory> all = categories.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId);
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个分类");
        }
        int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= all.size()) {
            return;
        }
        NoteCategory a = all.get(index);
        NoteCategory b = all.get(target);
        int sort = a.getSortOrder();
        a.setSortOrder(b.getSortOrder());
        b.setSortOrder(sort);
        categories.saveAll(List.of(a, b));
        renumber(ownerId);
    }

    private void renumber(Long ownerId) {
        List<NoteCategory> all = categories.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId);
        int i = 0;
        for (NoteCategory c : all) {
            c.setSortOrder(i++);
        }
        categories.saveAll(all);
    }

    /**
     * 删分类：什么分类都能删（包括迁移时补的那五个），删完不再自动补回来；
     * 里面的条目落到「未分类」，不跟着一起没。
     */
    @Transactional
    public int delete(Long ownerId, Long id) {
        NoteCategory c = owned(ownerId, id);
        int moved = jdbc.update("update content_item set category_id = null where owner_id = ? and category_id = ?",
                ownerId, id);
        categories.delete(c);
        renumber(ownerId);
        return moved;
    }

    /**
     * 新建条目时把分类挂上，别让新内容一出生就掉进「未分类」。
     * 优先落这个 kind 的默认分类；用户把默认分类删了（新账号本来就没有），
     * 就落到他这一类的第一个页签；一个同类分类都没有才留空。
     */
    public void attachDefault(ContentItem item) {
        if (item == null || item.getCategoryId() != null || item.getOwnerId() == null) {
            return;
        }
        defaultOf(item.getOwnerId(), item.getKind())
                .or(() -> categories.findFirstByOwnerIdAndKindOrderBySortOrderAscIdAsc(item.getOwnerId(), item.getKind()))
                .ifPresent(c -> item.setCategoryId(c.getId()));
    }

    /** 按 slug 找分类并返回 id；找不到就报 404（AI 和页面的写接口共用）。 */
    public Long idOf(Long ownerId, String slug) {
        return find(ownerId, slug)
                .map(NoteCategory::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "没有这个分类：" + (slug == null ? "" : slug)));
    }

    private NoteCategory owned(Long ownerId, Long id) {
        return categories.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个分类"));
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类名不能为空");
        }
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /**
     * 分类的 slug：英文名用现成的单词，中文名给一个短随机串。
     * 只求稳定、能进 URL，不追求好看；建好以后不再改（改了老链接会断）。
     */
    private String freeSlug(Long ownerId, String name) {
        String base = asciiSlug(name);
        if (base != null && !categories.existsByOwnerIdAndSlug(ownerId, base)) {
            return base;
        }
        for (int i = 0; i < 16; i++) {
            String slug = (base == null ? "cat-" : base + "-")
                    + HexFormat.of().formatHex(randomBytes(3));
            if (!categories.existsByOwnerIdAndSlug(ownerId, slug)) {
                return slug;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成分类地址失败，请重试");
    }

    private static String asciiSlug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty() || s.length() > 40) {
            return null;
        }
        return UNCATEGORIZED.equals(s) ? null : s;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** AI 工具返回的分类视图。 */
    public List<Map<String, Object>> view(Long ownerId) {
        Map<Long, Long> counts = counts(ownerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (NoteCategory c : of(ownerId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", c.getSlug());
            row.put("name", c.getName());
            row.put("kind", c.getKind().name().toLowerCase(Locale.ROOT));
            row.put("count", counts.getOrDefault(c.getId(), 0L));
            row.put("builtin", c.isBuiltin());
            out.add(row);
        }
        long rest = counts.getOrDefault(null, 0L);
        if (rest > 0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", UNCATEGORIZED);
            row.put("name", "未分类");
            row.put("kind", null);
            row.put("count", rest);
            row.put("builtin", false);
            out.add(row);
        }
        return out;
    }
}
