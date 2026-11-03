package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.TagService;
import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import cn.bytelove.notes.web.form.ContentForm;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内容管理。登录就能进，但默认只看到、只能改自己的；
 * 管理员额外能看到所有人的内容。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    /** 列表行：值都在控制器里算好，模板只做输出。 */
    public record AdminRow(long id, String kind, String slug, String title, String visibility,
                           String updated) {
    }

    private static final DateTimeFormatter UPDATED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 一次渲染多少行；剩下的滚动加载 */
    private static final int ADMIN_BATCH = 60;

    /** 正文编辑器插图的上限和允许的扩展名（不收 svg：里面能带脚本）。 */
    private static final long MAX_UPLOAD = 8L * 1024 * 1024;
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** 上传目录：静态资源映射把整个 /srv/data/uploads 挂出来了，所以这里存进去就能用 /media/... 访问。 */
    @Value("${notes.media-dir:/srv/data/uploads/media}")
    private String mediaDir;

    private final ContentItemRepository items;
    private final NoteTreeService tree;
    private final ViewerService viewers;
    private final ProjectMetaService meta;
    private final TrashService trash;
    private final TagService tags;
    private final MarkdownRenderer markdown;

    public AdminController(ContentItemRepository items, NoteTreeService tree, ViewerService viewers,
                           ProjectMetaService meta, TrashService trash, TagService tags,
                           MarkdownRenderer markdown) {
        this.items = items;
        this.tree = tree;
        this.viewers = viewers;
        this.meta = meta;
        this.trash = trash;
        this.tags = tags;
        this.markdown = markdown;
    }

    @ModelAttribute("kinds")
    public ContentKind[] kinds() {
        return ContentKind.values();
    }

    /** 管理页不属于任何一个内容分区，不要让它顶亮「笔记」。 */
    @ModelAttribute("tabActive")
    public String tabActive() {
        return "admin";
    }

    @ModelAttribute("visibilities")
    public Visibility[] visibilities() {
        return Visibility.values();
    }

    @GetMapping
    public String list(@RequestParam(required = false) String kind,
                       @RequestParam(required = false) Integer range,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) Integer from,
                       @RequestParam(defaultValue = "false") boolean fragment,
                       Model model, Authentication auth, HttpServletRequest request) {
        Viewer viewer = viewers.current(auth);

        ContentKind filterKind = parseKind(kind);
        Instant since = range == null ? null : Instant.now().minus(Duration.ofDays(range));
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";

        Long ownerId = viewer.admin() ? null : viewer.userId();
        Specification<ContentItem> spec = (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            if (ownerId != null) {
                parts.add(cb.equal(root.get("ownerId"), ownerId));
            }
            if (filterKind != null) {
                parts.add(cb.equal(root.get("kind"), filterKind));
            }
            if (since != null) {
                parts.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), since));
            }
            if (like != null) {
                parts.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("slug")), like)));
            }
            // 条件为空时 cb.and() 返回恒真，等价于不过滤
            return cb.and(parts.toArray(new Predicate[0]));
        };

        List<ContentItem> found = items.findAll(spec,
                Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("title")));

        // 每行要用到的值在这里一次性算好：模板里 #temporals.format 每行都会新建一次
        // formatter，300 多行加起来是几十毫秒；枚举的 label、URL 也一样
        List<AdminRow> rows = new ArrayList<>(found.size());
        for (ContentItem i : found) {
            rows.add(new AdminRow(i.getId(), i.getKind().getLabel(), i.getSlug(), i.getTitle(),
                    i.getVisibility().getLabel(), UPDATED_AT.format(i.getUpdatedAt())));
        }

        model.addAttribute("items", found);
        // 一次只渲染 60 行，滚到底再取下一批（和列表页同一套做法）。
        // 313 行一次渲染光 Thymeleaf 输出就要 60ms 左右，首屏没必要等
        int start = Math.max(0, from == null ? 0 : from);
        if (start > rows.size()) {
            start = rows.size();
        }
        int end = Math.min(rows.size(), start + ADMIN_BATCH);
        model.addAttribute("rows", rows.subList(start, end));
        model.addAttribute("nextFrom", end < rows.size() ? end : null);
        model.addAttribute("baseUrl", request.getContextPath() + "/admin" + querySuffix(filterKind, range, q));
        // 行内链接自己拼 URL（模板里 @{} 每行两个，313 行要多花几十毫秒）；
        // #request 在 Thymeleaf 3.1 里默认不给了，所以从控制器传 contextPath
        model.addAttribute("ctx", request.getContextPath());
        model.addAttribute("filterKind", filterKind);
        model.addAttribute("filterRange", range);
        model.addAttribute("filterQ", q == null ? null : q.trim());
        return fragment ? "fragments/admin-rows :: rows" : "admin/list";
    }

    /** 追加下一批时要带上的筛选条件，拼成 ?kind=..&range=..&q=.. */
    private static String querySuffix(ContentKind kind, Integer range, String q) {
        List<String> parts = new ArrayList<>();
        if (kind != null) {
            parts.add("kind=" + kind.name());
        }
        if (range != null) {
            parts.add("range=" + range);
        }
        if (q != null && !q.isBlank()) {
            parts.add("q=" + URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static ContentKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ContentKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @GetMapping("/new")
    public String createForm(Model model, Authentication auth) {
        Long ownerId = viewers.current(auth).userId();
        model.addAttribute("form", new ContentForm());
        model.addAttribute("editing", false);
        model.addAttribute("parents", parentOptions(null, ownerId));
        return "admin/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") ContentForm form, BindingResult binding,
                         Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (items.existsByOwnerIdAndSlug(viewer.userId(), form.getSlug())) {
            binding.rejectValue("slug", "duplicate", "slug 已存在");
        }
        if (!meta.isValid(form.getProjectDoc())) {
            binding.rejectValue("projectDoc", "invalid", "项目字段不是合法的 JSON");
        }
        if (binding.hasErrors()) {
            model.addAttribute("editing", false);
            model.addAttribute("parents", parentOptions(null, viewer.userId()));
            return "admin/form";
        }
        ContentItem item = new ContentItem(viewer.userId(), form.getKind(), form.getSlug(), form.getTitle(),
                form.getSummary(), form.getBody(), form.getVisibility());
        item.setParentId(form.getParentId());
        item.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        // 管理员的公开内容就是站点的推荐栏；普通用户的内容先只留给自己
        item.setRecommended(viewer.admin());
        ContentItem saved = items.save(item);
        saveProjectDoc(saved, form);
        applySupplement(saved, form);
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());
        return "redirect:/admin";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, Authentication auth,
                           @RequestParam(name = "back", required = false) String back,
                           HttpServletRequest request) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = owned(id, viewer);
        ContentForm form = new ContentForm();
        form.setKind(item.getKind());
        form.setSlug(item.getSlug());
        form.setTitle(item.getTitle());
        form.setSummary(item.getSummary());
        form.setBody(item.getBody());
        form.setVisibility(item.getVisibility());
        form.setParentId(item.getParentId());
        form.setSortOrder(item.getSortOrder());
        form.setProjectDoc(meta.findDoc(item.getId()).orElse(null));
        ContentItem attached = attachedNote(item);
        if (attached != null) {
            form.setSupplementTitle(attached.getTitle());
            form.setSupplementBody(attached.getBody());
        }
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("editing", true);
        model.addAttribute("parents", parentOptions(id, item.getOwnerId()));
        // 从正文页点进来时，返回和保存后都回那一页
        String backPath = safeBack(back, basePathOf(item) + "/" + item.getSlug());
        model.addAttribute("back", backPath);
        model.addAttribute("backUrl", request.getContextPath() + backPath);
        return "admin/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @Valid @ModelAttribute("form") ContentForm form,
                       BindingResult binding, Model model, Authentication auth,
                       @RequestParam(name = "back", required = false) String back,
                       HttpServletRequest request) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = owned(id, viewer);

        if (!item.getSlug().equals(form.getSlug())
                && items.existsByOwnerIdAndSlug(item.getOwnerId(), form.getSlug())) {
            binding.rejectValue("slug", "duplicate", "slug 已存在");
        }
        if (form.getParentId() != null) {
            if (form.getParentId().equals(id)) {
                binding.rejectValue("parentId", "self", "不能把自己设为父节点");
            } else if (tree.descendantIds(allFor(item.getOwnerId()), id).contains(form.getParentId())) {
                binding.rejectValue("parentId", "cycle", "不能把子孙节点设为父节点");
            }
        }
        if (!meta.isValid(form.getProjectDoc())) {
            binding.rejectValue("projectDoc", "invalid", "项目字段不是合法的 JSON");
        }
        String backPath = safeBack(back, basePathOf(item) + "/" + item.getSlug());
        if (binding.hasErrors()) {
            model.addAttribute("editing", true);
            model.addAttribute("itemId", id);
            model.addAttribute("parents", parentOptions(id, item.getOwnerId()));
            model.addAttribute("back", backPath);
            model.addAttribute("backUrl", request.getContextPath() + backPath);
            return "admin/form";
        }

        // 换了类型就跟原来的本体不是一类了：卡片只在同类型之间渲染，留着这条关系等于断链
        boolean kindChanged = form.getKind() != item.getKind();
        item.setKind(form.getKind());
        item.setSlug(form.getSlug());
        item.setTitle(form.getTitle());
        item.setSummary(form.getSummary());
        item.setBody(form.getBody());
        item.setVisibility(form.getVisibility());
        item.setParentId(form.getParentId());
        item.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        if (kindChanged) {
            item.setSupplementOfId(null);
        }
        ContentItem saved = items.save(item);
        saveProjectDoc(saved, form);
        applySupplement(saved, form);
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());
        // 保存后直接回文章页看效果，不用再从列表里找一遍
        return "redirect:" + safeBack(back, basePathOf(saved) + "/" + saved.getSlug());
    }

    /**
     * 正文编辑器插图：图存进上传目录，回一个能直接写进 Markdown 的地址。
     * CSRF 走 X-CSRF-TOKEN 头（multipart 的 body 在过滤器那一层还读不到参数）。
     */
    @PostMapping("/upload")
    @ResponseBody
    public Map<String, String> upload(@RequestParam("file") MultipartFile file,
                                      HttpServletRequest request) throws IOException {
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
        String name = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + ext;
        file.transferTo(dir.resolve(name).toFile());
        return Map.of("url", request.getContextPath() + "/media/notes/" + name);
    }

    /** 编辑器右栏的实时预览：跟正文页同一个渲染器，看到的排版就是发出去的样子。 */
    @PostMapping("/preview")
    @ResponseBody
    public String preview(@RequestParam(name = "body", required = false) String body) {
        return markdown.toHtml(body == null ? "" : body);
    }

    /** 回跳目标只允许站内路径，防止有人拿 back 参数把页面跳到别处。 */
    private static String safeBack(String back, String fallback) {
        if (back == null || back.isBlank()) {
            return fallback;
        }
        if (!back.startsWith("/") || back.startsWith("//") || back.contains("://")) {
            return fallback;
        }
        return back;
    }

    /** 各种内容类型的正文页前缀（不带 contextPath，redirect: 会自己加）。 */
    private static String basePathOf(ContentItem item) {
        switch (item.getKind()) {
            case BAGU:
                return "/bagu";
            case ALGO:
                return "/algo";
            case PROJECT:
                return "/projects";
            default:
                return "/notes";
        }
    }

    /** 词表改过之后，把当前用户的内容整体重打一遍标签。 */
    @PostMapping("/retag")
    public String retagAll(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        for (ContentItem item : items.findByOwnerIdOrderByKindAscSortOrderAsc(viewer.userId())) {
            tags.retag(item.getId(), item.getTitle(), item.getBody());
        }
        return "redirect:/admin";
    }

    /** 项目类型的结构化字段跟着内容一起存；其它类型清掉残留。 */
    private void saveProjectDoc(ContentItem item, ContentForm form) {
        String doc = form.getProjectDoc();
        if (item.getKind() == ContentKind.PROJECT && doc != null && !doc.isBlank()) {
            meta.save(item.getId(), doc, item.getSlug());
        } else {
            meta.delete(item.getId());
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, Authentication auth) {
        ContentItem item = owned(id, viewers.current(auth));
        // 进回收站，不是真删；30 天后由 TrashService 定时清理
        trash.softDelete(item.getId(), item.getOwnerId());
        return "redirect:/admin";
    }

    /** 只能碰自己的；管理员不受限。 */
    private ContentItem owned(Long id, Viewer viewer) {
        ContentItem item = items.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!viewer.admin() && !viewer.isSelf(item)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return item;
    }

    private List<ContentItem> allFor(Long ownerId) {
        List<ContentItem> all = new ArrayList<>();
        if (ownerId == null) {
            return all;
        }
        for (ContentKind kind : ContentKind.values()) {
            all.addAll(items.findByOwnerIdAndKindOrderBySortOrderAsc(ownerId, kind));
        }
        return all;
    }

    /** 可以作为父节点的候选：同一个人的内容，排除自己和自己的所有子孙。 */
    private List<ContentItem> parentOptions(Long selfId, Long ownerId) {
        List<ContentItem> all = allFor(ownerId);
        if (selfId != null) {
            List<Long> blocked = tree.descendantIds(all, selfId);
            all.removeIf(i -> i.getId().equals(selfId) || blocked.contains(i.getId()));
        }
        all.sort(Comparator.comparing(ContentItem::getKind)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        return all;
    }

    /** 这篇下面挂着的那篇补充笔记（有多篇时取排最前面的那条）。 */
    private ContentItem attachedNote(ContentItem item) {
        ContentItem found = null;
        for (ContentItem i : allFor(item.getOwnerId())) {
            if (item.getId().equals(i.getSupplementOfId())
                    && (found == null || i.getSortOrder() < found.getSortOrder())) {
                found = i;
            }
        }
        return found;
    }

    /**
     * 「补充笔记」：编辑页里当场写一段（通常是 AI 整理好的），保存时建成挂在本文下面的一篇，
     * 正文末尾就会多一张卡片。已经挂着的那篇就地更新；这次留空就把原来那篇挪进回收站。
     */
    private void applySupplement(ContentItem item, ContentForm form) {
        String body = form.getSupplementBody() == null ? "" : form.getSupplementBody().trim();
        ContentItem attached = attachedNote(item);
        if (body.isEmpty()) {
            if (attached != null) {
                attached.setSupplementOfId(null);
                items.save(attached);
                trash.softDelete(attached.getId(), attached.getOwnerId());
            }
            return;
        }
        String title = supplementTitleOf(form.getSupplementTitle(), body, item);
        if (attached == null) {
            // 类型、可见性、排序都跟着本体走：卡片只在同类型之间渲染，权限也不该比本体更宽
            ContentItem note = new ContentItem(item.getOwnerId(), item.getKind(),
                    uniqueSupplementSlug(item.getOwnerId(), item.getSlug() + "-detail"),
                    title, null, body, item.getVisibility());
            note.setParentId(item.getParentId());
            note.setSortOrder(item.getSortOrder());
            note.setRecommended(item.isRecommended());
            note.setSupplementOfId(item.getId());
            ContentItem saved = items.save(note);
            tags.retag(saved.getId(), saved.getTitle(), saved.getBody());
            return;
        }
        attached.setKind(item.getKind());
        attached.setTitle(title);
        attached.setBody(body);
        attached.setVisibility(item.getVisibility());
        attached.setRecommended(item.isRecommended());
        ContentItem saved = items.save(attached);
        tags.retag(saved.getId(), saved.getTitle(), saved.getBody());
    }

    /** 标题留空就用正文第一行（去掉 Markdown 的井号、星号），第一行也没字就叫「补充笔记」。 */
    private static String supplementTitleOf(String raw, String body, ContentItem parent) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        for (String line : body.split("\\R")) {
            String text = line.replaceFirst("^\\s*#{1,6}\\s*", "")
                    .replaceAll("[*_`>#\\-]+", " ").trim();
            if (!text.isEmpty()) {
                return text.length() > 40 ? text.substring(0, 40) : text;
            }
        }
        return parent.getTitle() + " · 补充";
    }

    /** 补充笔记的地址：本体 slug 后面接 -detail，重名了往后挂数字。 */
    private String uniqueSupplementSlug(Long ownerId, String base) {
        String slug = base.length() > 60 ? base.substring(0, 60) : base;
        if (!items.existsByOwnerIdAndSlug(ownerId, slug)) {
            return slug;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = slug + "-" + i;
            if (!items.existsByOwnerIdAndSlug(ownerId, candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "同名的补充笔记太多了");
    }
}
