package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.web.form.ContentForm;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ContentItemRepository items;
    private final NoteTreeService tree;

    public AdminController(ContentItemRepository items, NoteTreeService tree) {
        this.items = items;
        this.tree = tree;
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
    public String list(Model model) {
        List<ContentItem> all = new ArrayList<>(items.findAll());
        all.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        model.addAttribute("items", all);
        return "admin/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new ContentForm());
        model.addAttribute("editing", false);
        model.addAttribute("parents", parentOptions(null));
        return "admin/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("form") ContentForm form, BindingResult binding, Model model) {
        if (items.existsBySlug(form.getSlug())) {
            binding.rejectValue("slug", "duplicate", "slug 已存在");
        }
        if (binding.hasErrors()) {
            model.addAttribute("editing", false);
            model.addAttribute("parents", parentOptions(null));
            return "admin/form";
        }
        ContentItem item = new ContentItem(form.getKind(), form.getSlug(), form.getTitle(),
                form.getSummary(), form.getBody(), form.getVisibility());
        item.setParentId(form.getParentId());
        item.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());
        items.save(item);
        return "redirect:/admin";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ContentItem item = items.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ContentForm form = new ContentForm();
        form.setKind(item.getKind());
        form.setSlug(item.getSlug());
        form.setTitle(item.getTitle());
        form.setSummary(item.getSummary());
        form.setBody(item.getBody());
        form.setVisibility(item.getVisibility());
        form.setParentId(item.getParentId());
        form.setSortOrder(item.getSortOrder());
        model.addAttribute("form", form);
        model.addAttribute("itemId", id);
        model.addAttribute("editing", true);
        model.addAttribute("parents", parentOptions(id));
        return "admin/form";
    }

    @PostMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @Valid @ModelAttribute("form") ContentForm form,
                       BindingResult binding, Model model) {
        ContentItem item = items.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!item.getSlug().equals(form.getSlug()) && items.existsBySlug(form.getSlug())) {
            binding.rejectValue("slug", "duplicate", "slug 已存在");
        }
        if (form.getParentId() != null) {
            if (form.getParentId().equals(id)) {
                binding.rejectValue("parentId", "self", "不能把自己设为父节点");
            } else if (tree.descendantIds(items.findAll(), id).contains(form.getParentId())) {
                binding.rejectValue("parentId", "cycle", "不能把子孙节点设为父节点");
            }
        }
        if (binding.hasErrors()) {
            model.addAttribute("editing", true);
            model.addAttribute("itemId", id);
            model.addAttribute("parents", parentOptions(id));
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
        items.save(item);
        return "redirect:/admin";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        items.deleteById(id);
        return "redirect:/admin";
    }

    /** 可以作为父节点的候选：同类型、排除自己和自己的所有子孙。 */
    private List<ContentItem> parentOptions(Long selfId) {
        List<ContentItem> all = new ArrayList<>(items.findAll());
        if (selfId != null) {
            List<Long> blocked = tree.descendantIds(all, selfId);
            all.removeIf(i -> i.getId().equals(selfId) || blocked.contains(i.getId()));
        }
        all.sort(Comparator.comparing(ContentItem::getKind)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        return all;
    }
}
