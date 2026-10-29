package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import cn.bytelove.notes.web.form.ContentForm;
import jakarta.validation.Valid;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 内容管理。登录就能进，但默认只看到、只能改自己的；
 * 管理员额外能看到所有人的内容。
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ContentItemRepository items;
    private final NoteTreeService tree;
    private final ViewerService viewers;
    private final ProjectMetaService meta;

    public AdminController(ContentItemRepository items, NoteTreeService tree, ViewerService viewers,
                           ProjectMetaService meta) {
        this.items = items;
        this.tree = tree;
        this.viewers = viewers;
        this.meta = meta;
    }

    @ModelAttribute("kinds")
    public ContentKind[] kinds() {
        return ContentKind.values();
    }

    @ModelAttribute("visibilities")
    public Visibility[] visibilities() {
        return Visibility.values();
    }

    @GetMapping
    public String list(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        List<ContentItem> all = new ArrayList<>(viewer.admin()
                ? items.findAll()
                : items.findByOwnerIdOrderByKindAscSortOrderAsc(viewer.userId()));
        all.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        model.addAttribute("items", all);
        return "admin/list";
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
        items.deleteById(item.getId());
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
