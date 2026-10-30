package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import cn.bytelove.notes.web.form.ContentForm;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    private final ContentItemRepository items;
    private final NoteTreeService tree;
    private final ViewerService viewers;
    private final ProjectMetaService meta;
    private final TrashService trash;

    public AdminController(ContentItemRepository items, NoteTreeService tree, ViewerService viewers,
                           ProjectMetaService meta, TrashService trash) {
        this.items = items;
        this.tree = tree;
        this.viewers = viewers;
        this.meta = meta;
        this.trash = trash;
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
        model.addAttribute("rows", rows);
        // 行内链接自己拼 URL（模板里 @{} 每行两个，313 行要多花几十毫秒）；
        // #request 在 Thymeleaf 3.1 里默认不给了，所以从控制器传 contextPath
        model.addAttribute("ctx", request.getContextPath());
        model.addAttribute("filterKind", filterKind);
        model.addAttribute("filterRange", range);
        model.addAttribute("filterQ", q == null ? null : q.trim());
        return "admin/list";
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
        model.addAttribute("form", new ContentForm());
        model.addAttribute("editing", false);
        model.addAttribute("parents", parentOptions(null, viewers.current(auth).userId()));
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
        return "redirect:/admin";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, Authentication auth) {
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
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("editing", true);
        model.addAttribute("parents", parentOptions(id, item.getOwnerId()));
        return "admin/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @Valid @ModelAttribute("form") ContentForm form,
                       BindingResult binding, Model model, Authentication auth) {
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
        if (binding.hasErrors()) {
            model.addAttribute("editing", true);
            model.addAttribute("itemId", id);
            model.addAttribute("parents", parentOptions(id, item.getOwnerId()));
            return "admin/form";
        }

        item.setKind(form.getKind());
        item.setSlug(form.getSlug());
        item.setTitle(form.getTitle());
        item.setSummary(form.getSummary());
        item.setBody(form.getBody());
        item.setVisibility(form.getVisibility());
        item.setParentId(form.getParentId());
        item.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        ContentItem saved = items.save(item);
        saveProjectDoc(saved, form);
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
}
