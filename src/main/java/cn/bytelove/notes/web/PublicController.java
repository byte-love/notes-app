package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.VisibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return listing(ContentKind.NOTE, "笔记", model, auth);
    }

    @GetMapping("/bagu")
    public String bagu(Model model, Authentication auth) {
        return listing(ContentKind.BAGU, "八股", model, auth);
    }

    @GetMapping("/algo")
    public String algo(Model model, Authentication auth) {
        return listing(ContentKind.ALGO, "算法", model, auth);
    }

    @GetMapping("/bagu/{slug}")
    public String baguItem(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.BAGU, slug, model, auth);
    }

    @GetMapping("/algo/{slug}")
    public String algoItem(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.ALGO, slug, model, auth);
    }

    @GetMapping("/notes/{slug}")
    public String note(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.NOTE, slug, model, auth);
    }

    @GetMapping("/projects")
    public String projects(Model model, Authentication auth) {
        return listing(ContentKind.PROJECT, "项目", model, auth);
    }

    private String listing(ContentKind kind, String pageTitle, Model model, Authentication auth) {
        List<ContentItem> all = load(model, kind, auth);
        // 目录节点只出现在左侧树里，不混进右侧正文列表
        Set<Long> parentIds = new LinkedHashSet<>();
        for (ContentItem i : all) {
            if (i.getParentId() != null) {
                parentIds.add(i.getParentId());
            }
        }
        List<ContentItem> leaves = new ArrayList<>();
        for (ContentItem i : all) {
            if (!parentIds.contains(i.getId())) {
                leaves.add(i);
            }
        }
        model.addAttribute("items", leaves);
        model.addAttribute("kind", kind);
        model.addAttribute("activeId", null);
        model.addAttribute("blank", true);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("basePath", contextPath() + basePath(kind));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));
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
        if (kind != ContentKind.PROJECT) {
            model.addAttribute("crumbs", tree.breadcrumb(siblings, item));
            model.addAttribute("activeId", item.getId());
        }
        model.addAttribute("basePath", contextPath() + basePath(kind));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));

        model.addAttribute("item", item);
        model.addAttribute("html", markdown.toHtml(item.getBody()));
        model.addAttribute("toc", markdown.toc(item.getBody()));
        return "detail";
    }

    /** 备忘录：加入 / 移出。登录用户都能标记。 */
    @PostMapping("/star/{slug}")
    public String toggleStar(@PathVariable String slug) {
        ContentItem item = items.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        item.setStarred(!item.isStarred());
        items.save(item);
        // 这里不能自己拼 contextPath：redirect: 的目标 Spring 会自动加上前缀，
        // 手动再加一次会变成 /app/app/bagu/xxx 直接 404
        return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug();
    }

    /** 备忘录列表：把标记过的条目按类型分组列出来。 */
    @GetMapping("/memo")
    public String memo(Model model) {
        List<ContentItem> starred = items.findByStarredTrueOrderByKindAscSortOrderAsc();
        Map<String, List<ContentItem>> grouped = new LinkedHashMap<>();
        // 固定顺序：笔记 -> 八股 -> 算法 -> 项目，避免按枚举名字母序排成「算法/八股/笔记/项目」
        for (ContentKind k : new ContentKind[]{ContentKind.NOTE, ContentKind.BAGU,
                ContentKind.ALGO, ContentKind.PROJECT}) {
            for (ContentItem item : starred) {
                if (item.getKind() == k) {
                    grouped.computeIfAbsent(kindLabel(k), key -> new ArrayList<>()).add(item);
                }
            }
        }
        model.addAttribute("grouped", grouped);
        model.addAttribute("total", starred.size());
        model.addAttribute("basePathOf", (java.util.function.Function<ContentKind, String>) PublicController::basePath);
        model.addAttribute("tabActive", "memo");
        return "memo";
    }

    /**
     * 应用挂在 /app 下（server.servlet.context-path），模板里的链接是字符串拼的，
     * 不像 @{} 那样自动补前缀，所以要显式带上。
     */
    private static String contextPath() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getContextPath();
        }
        return "";
    }

    private static String kindLabel(ContentKind kind) {
        switch (kind) {
            case BAGU:
                return "八股";
            case ALGO:
                return "算法";
            case PROJECT:
                return "项目";
            default:
                return "笔记";
        }
    }

    /** 各 kind 的详情/树链接前缀，模板里用它拼 URL。 */
    private static String basePath(ContentKind kind) {
        switch (kind) {
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
