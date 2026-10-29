package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.BookmarkService;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.ReadingPositionService;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Controller
public class PublicController {

    private final ContentItemRepository items;
    private final VisibilityService visibility;
    private final ViewerService viewers;
    private final ContentStarService stars;
    private final ProjectMetaService meta;
    private final ReadingPositionService positions;
    private final BookmarkService bookmarks;
    private final MarkdownRenderer markdown;
    private final NoteTreeService tree;

    public PublicController(ContentItemRepository items, VisibilityService visibility, ViewerService viewers,
                            ContentStarService stars, ProjectMetaService meta, ReadingPositionService positions, BookmarkService bookmarks,
                            MarkdownRenderer markdown, NoteTreeService tree) {
        this.items = items;
        this.visibility = visibility;
        this.viewers = viewers;
        this.stars = stars;
        this.meta = meta;
        this.positions = positions;
        this.bookmarks = bookmarks;
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

    @GetMapping("/projects")
    public String projects(Model model, Authentication auth) {
        return listing(ContentKind.PROJECT, "项目", model, auth);
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

    @GetMapping("/projects/{slug}")
    public String project(@PathVariable String slug, Model model, Authentication auth) {
        return detail(ContentKind.PROJECT, slug, model, auth);
    }

    private String listing(ContentKind kind, String pageTitle, Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        Set<Long> lockedIds = new LinkedHashSet<>();
        List<ContentItem> all = filtered(poolForListing(kind, viewer), viewer, lockedIds);

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

        Long lastReadId = positions.find(viewer.userId(), kind);
        ContentItem lastRead = null;
        for (ContentItem i : all) {
            if (i.getId().equals(lastReadId)) {
                lastRead = i;
                break;
            }
        }

        model.addAttribute("items", leaves);
        model.addAttribute("tree", tree.build(all, lockedIds));
        model.addAttribute("starIds", stars.starredIds(viewer.userId()));
        model.addAttribute("bookmarkIds", bookmarks.ids(viewer.userId()));
        model.addAttribute("lastReadId", lastReadId);
        model.addAttribute("lastRead", lastRead);
        model.addAttribute("lockedIds", lockedIds);
        model.addAttribute("lockedCount", lockedIds.size());
        model.addAttribute("kind", kind);
        model.addAttribute("activeId", null);
        model.addAttribute("blank", true);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("basePath", contextPath() + basePath(kind));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));
        return "list";
    }

    private String detail(ContentKind kind, String slug, Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = resolve(kind, slug, viewer);

        // 只记自己内容的阅读位置：别人的推荐内容记下来也没法在自己列表里标出来
        if (viewer.isSelf(item)) {
            positions.remember(viewer.userId(), kind, item.getId());
        }
        model.addAttribute("bookmarked", bookmarks.anchorsOf(viewer.userId(), item.getId()).contains(""));
        model.addAttribute("bookmarkIds", bookmarks.ids(viewer.userId()));
        model.addAttribute("bookmarkAnchors", bookmarks.anchorsOf(viewer.userId(), item.getId()));
        model.addAttribute("bookmarkAnchorCsv", String.join(",", bookmarks.anchorsOf(viewer.userId(), item.getId())));

        // 项目走单独的模板：它渲染的是作品集卡片那套结构化字段，没有目录树
        if (kind == ContentKind.PROJECT) {
            model.addAttribute("basePath", contextPath() + basePath(kind));
            model.addAttribute("tabActive", "project");
            model.addAttribute("item", item);
            model.addAttribute("project", meta.view(item.getId()).orElse(null));
            model.addAttribute("html", markdown.toHtml(item.getBody()));
            model.addAttribute("starred", viewer.userId() != null && stars.isStarred(viewer.userId(), item.getId()));
            return "project";
        }

        Set<Long> lockedIds = new LinkedHashSet<>();
        // 侧栏树跟着这条内容的作者走：看别人的推荐内容时，列的是他自己的目录
        List<ContentItem> siblings = filtered(
                items.findByOwnerIdAndKindOrderByUpdatedAtDesc(item.getOwnerId(), kind), viewer, lockedIds);

        if (kind != ContentKind.PROJECT) {
            model.addAttribute("crumbs", tree.breadcrumb(siblings, item));
            model.addAttribute("activeId", item.getId());
        }
        model.addAttribute("tree", tree.build(siblings, lockedIds));
        model.addAttribute("starIds", stars.starredIds(viewer.userId()));
        model.addAttribute("lockedIds", lockedIds);
        model.addAttribute("lockedCount", lockedIds.size());
        model.addAttribute("basePath", contextPath() + basePath(kind));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));

        model.addAttribute("item", item);
        model.addAttribute("html", markdown.toHtml(item.getBody()));
        model.addAttribute("toc", markdown.toc(item.getBody()));
        model.addAttribute("starred", viewer.userId() != null && stars.isStarred(viewer.userId(), item.getId()));
        return "detail";
    }

    /** 手动书签：自己插的旗，想标几处标几处；anchor 为空是整篇，非空是某个标题块。 */
    @PostMapping("/bookmark/{slug}")
    public String toggleBookmark(@PathVariable String slug,
                                 @RequestParam(required = false) String anchor,
                                 Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = resolveAnyKind(slug, viewer);
        bookmarks.toggle(viewer.userId(), item.getId(), anchor);
        String target = basePath(item.getKind()) + "/" + item.getSlug();
        if (anchor != null && !anchor.isBlank()) {
            target = target + "#" + anchor;
        }
        return "redirect:" + target;
    }

    /** 我的书签：列出所有标过的位置，直接跳到正文里的那一块。 */
    @GetMapping("/bookmarks")
    public String bookmarks(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        List<ContentItem> all = new ArrayList<>();
        List<String> anchors = new ArrayList<>();
        for (BookmarkService.Spot spot : bookmarks.spots(viewer.userId())) {
            ContentItem item = items.findById(spot.itemId()).orElse(null);
            if (item == null || !visibility.canRead(item, viewer)) {
                continue;
            }
            all.add(item);
            anchors.add(spot.anchor());
        }
        model.addAttribute("items", all);
        model.addAttribute("anchors", anchors);
        model.addAttribute("basePathOf", (java.util.function.Function<ContentKind, String>) PublicController::basePath);
        model.addAttribute("tabActive", "memo");
        return "bookmarks";
    }

    /** 收藏夹：加入 / 移出。标记落在用户自己名下。 */
    @PostMapping("/star/{slug}")
    public String toggleStar(@PathVariable String slug, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = resolveAnyKind(slug, viewer);
        stars.toggle(viewer.userId(), item.getId());
        // 这里不能自己拼 contextPath：redirect: 的目标 Spring 会自动加上前缀，
        // 手动再加一次会变成 /app/app/bagu/xxx 直接 404
        return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug();
    }

    /** 备忘录列表：把标记过的条目按类型分组列出来。 */
    @GetMapping("/memo")
    public String memo(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        List<Long> ids = stars.starredItemIds(viewer.userId());
        List<ContentItem> starred = ids.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(items.findAllById(ids));
        starred.removeIf(i -> !visibility.canRead(i, viewer));
        starred.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));

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
     * 列表页的内容池：登录后是自己的空间，游客看的是推荐栏里公开的那部分。
     */
    private List<ContentItem> poolForListing(ContentKind kind, Viewer viewer) {
        if (viewer.userId() != null) {
            return items.findByOwnerIdAndKindOrderByUpdatedAtDesc(viewer.userId(), kind);
        }
        return items.findByKindAndRecommendedTrueOrderByUpdatedAtDesc(kind);
    }

    /** 能看的留下，只能看标题的收进 lockedIds。 */
    private List<ContentItem> filtered(List<ContentItem> pool, Viewer viewer, Set<Long> lockedIds) {
        List<ContentItem> kept = new ArrayList<>();
        for (ContentItem item : pool) {
            if (visibility.canRead(item, viewer)) {
                kept.add(item);
            } else if (visibility.isLocked(item, viewer)) {
                kept.add(item);
                lockedIds.add(item.getId());
            }
        }
        return kept;
    }

    /** 先找自己的，找不到再在推荐栏里按 slug 找一条能看的。 */
    private ContentItem resolve(ContentKind kind, String slug, Viewer viewer) {
        if (viewer.userId() != null) {
            Optional<ContentItem> mine = items.findByOwnerIdAndSlug(viewer.userId(), slug)
                    .filter(i -> i.getKind() == kind);
            if (mine.isPresent()) {
                return mine.get();
            }
        }
        List<ContentItem> candidates = items.findBySlugAndRecommendedTrueOrderByIdAsc(slug).stream()
                .filter(i -> i.getKind() == kind)
                .toList();
        Optional<ContentItem> readable = candidates.stream()
                .filter(i -> visibility.canRead(i, viewer))
                .findFirst();
        if (readable.isPresent()) {
            return readable.get();
        }
        // 内容存在、只是没登录看不了：引导去登录，而不是丢一个 404
        boolean existsButLocked = candidates.stream().anyMatch(i -> visibility.isLocked(i, viewer));
        throw new ResponseStatusException(existsButLocked ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND);
    }

    /** 同上，但不管类型（/star/{slug} 的路径里没有 kind）。 */
    private ContentItem resolveAnyKind(String slug, Viewer viewer) {
        if (viewer.userId() != null) {
            Optional<ContentItem> mine = items.findByOwnerIdAndSlug(viewer.userId(), slug);
            if (mine.isPresent()) {
                return mine.get();
            }
        }
        return items.findBySlugAndRecommendedTrueOrderByIdAsc(slug).stream()
                .filter(i -> visibility.canRead(i, viewer))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
}
