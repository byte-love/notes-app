package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.VisibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Controller
public class PublicController {

    private final ContentItemRepository items;
    private final VisibilityService visibility;
    private final MarkdownRenderer markdown;
    private final NoteTreeService tree;

    public PublicController(ContentItemRepository items, VisibilityService visibility,
                            MarkdownRenderer markdown, NoteTreeService tree) {
        this.items = items;
        this.visibility = visibility;
        this.markdown = markdown;
        this.tree = tree;
    }

    @GetMapping("/")
    public String home(Model model, Authentication auth) {
        return notes(model, auth);
    }

    @GetMapping("/notes")
    public String notes(Model model, Authentication auth) {
        List<ContentItem> notes = load(model, ContentKind.NOTE, auth);
        model.addAttribute("items", notes);
        model.addAttribute("kind", ContentKind.NOTE);
        model.addAttribute("activeId", null);
        model.addAttribute("blank", true);
        return "list";
    }

    @GetMapping("/notes/{slug}")
    public String note(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.NOTE, slug, model, auth);
    }

    @GetMapping("/projects")
    public String projects(Model model, Authentication auth) {
        List<ContentItem> list = load(model, ContentKind.PROJECT, auth);
        model.addAttribute("items", list);
        model.addAttribute("kind", ContentKind.PROJECT);
        return "list";
    }

    @GetMapping("/projects/{slug}")
    public String project(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.PROJECT, slug, model, auth);
    }

    private String detail(ContentKind kind, String slug, Model model, Authentication auth) {
        ContentItem item = items.findBySlug(slug)
                .filter(i -> i.getKind() == kind)
                .filter(i -> visibility.canView(i.getVisibility(), auth))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<ContentItem> siblings = load(model, kind, auth);
        if (kind == ContentKind.NOTE) {
            model.addAttribute("crumbs", tree.breadcrumb(siblings, item));
            model.addAttribute("activeId", item.getId());
        }

        model.addAttribute("item", item);
        model.addAttribute("html", markdown.toHtml(item.getBody()));
        model.addAttribute("toc", markdown.toc(item.getBody()));
        return "detail";
    }

    /**
     * 取「能看的」+「只给标题占位的」，一起装配目录树。返回值按更新时间倒序。
     */
    private List<ContentItem> load(Model model, ContentKind kind, Authentication auth) {
        List<ContentItem> visible = items.findByKindAndVisibilityInOrderByUpdatedAtDesc(kind, visibility.visibleTo(auth));
        List<Visibility> lockedVisibilities = visibility.lockedTo(auth);
        List<ContentItem> locked = lockedVisibilities.isEmpty()
                ? List.of()
                : items.findByKindAndVisibilityInOrderByUpdatedAtDesc(kind, lockedVisibilities);

        Set<Long> lockedIds = new LinkedHashSet<>();
        for (ContentItem item : locked) {
            lockedIds.add(item.getId());
        }

        List<ContentItem> all = new ArrayList<>(visible);
        all.addAll(locked);

        model.addAttribute("tree", tree.build(all, lockedIds));
        model.addAttribute("lockedIds", lockedIds);
        model.addAttribute("lockedCount", locked.size());
        return all;
    }
}
