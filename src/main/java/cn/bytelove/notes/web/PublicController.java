package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.BookmarkService;
import cn.bytelove.notes.service.CommentFilter;
import cn.bytelove.notes.service.CommentRateLimiter;
import cn.bytelove.notes.service.CommentService;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.HighlightService;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.ReadingPositionService;
import cn.bytelove.notes.service.RatingService;
import cn.bytelove.notes.service.NoteTreeService;
import cn.bytelove.notes.service.SubGroupService;
import cn.bytelove.notes.service.TagService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Controller
public class PublicController {

    /** 一条评论 / 注释的正文上限。注释里常整段贴代码，500 字不够用。 */
    /** 注释可以写长解释：2000 字。 */
    private static final int MAX_ANNOTATION = 2000;

    /** 评论是短句：前端输入框就限了 500，服务端按同一个数截断，别让绕过输入框的请求写进长评论。 */
    private static final int MAX_COMMENT = 500;

    /** 高亮的引文只存锚点用，截 500 字就够定位了。 */
    private static final int MAX_HIGHLIGHT = 500;

    private final ContentItemRepository items;
    private final VisibilityService visibility;
    private final ViewerService viewers;
    private final ContentStarService stars;
    private final RatingService ratings;
    private final TagService tags;
    private final ProjectMetaService meta;
    private final ReadingPositionService positions;
    private final BookmarkService bookmarks;
    private final MarkdownRenderer markdown;
    private final NoteTreeService tree;
    private final SubGroupService subGroups;
    private final CommentService comments;
    private final CommentFilter commentFilter;
    private final CommentRateLimiter commentLimiter;
    private final HighlightService highlights;

    public PublicController(ContentItemRepository items, VisibilityService visibility, ViewerService viewers,
                            ContentStarService stars, RatingService ratings, TagService tags, ProjectMetaService meta, ReadingPositionService positions, BookmarkService bookmarks,
                            MarkdownRenderer markdown, NoteTreeService tree, SubGroupService subGroups,
                            CommentService comments, CommentFilter commentFilter,
                            CommentRateLimiter commentLimiter, HighlightService highlights) {
        this.items = items;
        this.visibility = visibility;
        this.viewers = viewers;
        this.stars = stars;
        this.ratings = ratings;
        this.tags = tags;
        this.meta = meta;
        this.positions = positions;
        this.bookmarks = bookmarks;
        this.markdown = markdown;
        this.tree = tree;
        this.subGroups = subGroups;
        this.comments = comments;
        this.commentFilter = commentFilter;
        this.commentLimiter = commentLimiter;
        this.highlights = highlights;
    }

    @GetMapping("/")
    public String home(Model model, Authentication auth) {
        return listing(ContentKind.NOTE, "闪念", model, auth, null, false, null, null);
    }

    @GetMapping("/notes")
    public String notes(Model model, Authentication auth,
                        @RequestParam(name = "from", required = false) Integer from,
                        @RequestParam(name = "fragment", defaultValue = "false") boolean fragment,
                        @RequestParam(name = "rate", required = false) String rate,
                        @RequestParam(name = "tag", required = false) String tag) {
        return listing(ContentKind.NOTE, "闪念", model, auth, from, fragment, rate, tag);
    }

    @GetMapping("/bagu")
    public String bagu(Model model, Authentication auth,
                       @RequestParam(name = "from", required = false) Integer from,
                       @RequestParam(name = "fragment", defaultValue = "false") boolean fragment,
                       @RequestParam(name = "rate", required = false) String rate,
                       @RequestParam(name = "tag", required = false) String tag) {
        return listing(ContentKind.BAGU, "八股", model, auth, from, fragment, rate, tag);
    }

    @GetMapping("/algo")
    public String algo(Model model, Authentication auth,
                       @RequestParam(name = "from", required = false) Integer from,
                       @RequestParam(name = "fragment", defaultValue = "false") boolean fragment,
                       @RequestParam(name = "rate", required = false) String rate,
                       @RequestParam(name = "tag", required = false) String tag) {
        return listing(ContentKind.ALGO, "算法", model, auth, from, fragment, rate, tag);
    }

    @GetMapping("/projects")
    public String projects(Model model, Authentication auth,
                           @RequestParam(name = "from", required = false) Integer from,
                           @RequestParam(name = "fragment", defaultValue = "false") boolean fragment,
                           @RequestParam(name = "rate", required = false) String rate,
                           @RequestParam(name = "tag", required = false) String tag) {
        return listing(ContentKind.PROJECT, "项目", model, auth, from, fragment, rate, tag);
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

    private String listing(ContentKind kind, String pageTitle, Model model, Authentication auth,
                           Integer from, boolean fragment, String rate, String tag) {
        Viewer viewer = viewers.current(auth);
        Set<Long> lockedIds = new LinkedHashSet<>();
        List<ContentItem> all = filtered(poolForListing(kind, viewer), viewer, lockedIds);
        Set<Long> starIds = stars.starredIds(viewer.userId());

        // 目录节点只出现在左侧树里，不混进右侧正文列表；标成「当目录用」的也一样
        Set<Long> parentIds = new LinkedHashSet<>();
        for (ContentItem i : all) {
            if (i.getParentId() != null) {
                parentIds.add(i.getParentId());
            }
        }
        List<ContentItem> leaves = new ArrayList<>();
        for (ContentItem i : all) {
            if (!parentIds.contains(i.getId()) && !i.isFolder()) {
                leaves.add(i);
            }
        }

        // 星级 / 收藏筛选只挑卡片，左侧目录照旧整棵给：筛完还得能顺着目录跳
        Map<String, Integer> counts = rateCounts(leaves, starIds);
        String rateFilter = pickRate(rate);
        leaves = byRate(leaves, rateFilter, starIds);

        // 标签筛选：同样只挑卡片；标签上的条数按「当前星级筛选后」的数，
        // 这样「只看三星」时还能看出三星里哪些主题多
        Map<String, Integer> tagCounts = tags.counts(idsOf(leaves));
        String tagFilter = (tag == null || tag.isBlank()) ? null : tag.trim();
        if (tagFilter != null) {
            Set<Long> withTag = tags.itemIdsWithTag(tagFilter);
            List<ContentItem> kept = new ArrayList<>();
            for (ContentItem i : leaves) {
                if (withTag.contains(i.getId())) {
                    kept.add(i);
                }
            }
            leaves = kept;
        }

        // 补充笔记不单独占一张卡：作为附件挂到它的「本体」卡片里（本体不可见时才退回当普通卡片）
        Map<Long, List<ContentItem>> supplementsOf = new LinkedHashMap<>();
        List<ContentItem> plainLeaves = new ArrayList<>();
        for (ContentItem i : leaves) {
            Long suppOf = i.getSupplementOfId();
            if (suppOf != null && !suppOf.equals(i.getId())) {
                supplementsOf.computeIfAbsent(suppOf, k -> new ArrayList<>()).add(i);
            } else {
                plainLeaves.add(i);
            }
        }
        leaves = plainLeaves;
        Map<Long, List<AttachedNote>> cardAttachments = new LinkedHashMap<>();
        supplementsOf.forEach((parentId, list) -> {
            list.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                    .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
            List<AttachedNote> notes = new ArrayList<>(list.size());
            for (ContentItem s : list) {
                notes.add(new AttachedNote(s.getTitle(), s.getSlug(), sizeOf(s.getBody())));
            }
            cardAttachments.put(parentId, notes);
        });
        model.addAttribute("cardAttachments", cardAttachments);

        // 分片：先给够 BATCH 条（按整组切，分类标题不会被劈开），剩下的滚到底再取
        List<ItemGroup> grouped = groups(all, leaves);
        int start = (from == null || from < 0) ? 0 : Math.min(from, grouped.size());
        int end = start;
        int count = 0;
        while (end < grouped.size()) {
            int size = grouped.get(end).items().size();
            // 一组比一批还大时也只能整组给，但别再多塞下一组进来
            if (end > start && (count >= BATCH_ITEMS || count + size > BATCH_ITEMS * 2)) {
                break;
            }
            count += size;
            end++;
        }
        model.addAttribute("groups", grouped.subList(start, end));
        model.addAttribute("nextFrom", end < grouped.size() ? end : -1);

        Long lastReadId = positions.find(viewer.userId(), kind);
        ContentItem lastRead = null;
        for (ContentItem i : all) {
            if (i.getId().equals(lastReadId)) {
                lastRead = i;
                break;
            }
        }

        model.addAttribute("items", leaves);
        model.addAttribute("tree", visibleTree(all, lockedIds, null));
        model.addAttribute("starIds", starIds);
        model.addAttribute("rateFilter", rateFilter);
        model.addAttribute("rateCounts", counts);
        model.addAttribute("tagFilter", tagFilter);
        model.addAttribute("tagCounts", tagCounts);
        model.addAttribute("itemTags", tags.of(idsOf(leaves)));
        model.addAttribute("canRate", viewer.userId() != null);
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
        model.addAttribute("treeBase", contextPath() + "/api/tree/" + kind.name().toLowerCase(java.util.Locale.ROOT));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));
        // 分片请求只回内容那一段，不套整页
        return fragment ? "fragments/list :: groupSections" : "list";
    }

    private String detail(ContentKind kind, String slug, Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = resolve(kind, slug, viewer);

        // 只记自己内容的阅读位置：别人的推荐内容记下来也没法在自己列表里标出来
        if (viewer.isSelf(item)) {
            positions.remember(viewer.userId(), kind, item.getId());
        }
        // 上次滚到哪儿：下次打开同一条时前端按这个比例滚回去
        model.addAttribute("resumeProgress", positions.progressOf(viewer.userId(), kind, item.getId()));
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
            model.addAttribute("html", markdown.toHtml(markdown.dropTitleEcho(item.getBody(), item.getTitle())));
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
        model.addAttribute("tree", visibleTree(siblings, lockedIds, item.getId()));
        model.addAttribute("starIds", stars.starredIds(viewer.userId()));
        model.addAttribute("lockedIds", lockedIds);
        model.addAttribute("lockedCount", lockedIds.size());
        model.addAttribute("basePath", contextPath() + basePath(kind));
        model.addAttribute("treeBase", contextPath() + "/api/tree/" + kind.name().toLowerCase(java.util.Locale.ROOT));
        model.addAttribute("tabActive", kind.name().toLowerCase(java.util.Locale.ROOT));

        model.addAttribute("item", item);
        // 正文第一行要是把标题又抄了一遍，渲染时不显示那一行（AI 存笔记时常这么写）
        String body = markdown.dropTitleEcho(item.getBody(), item.getTitle());
        model.addAttribute("html", markdown.toHtml(body));
        model.addAttribute("toc", markdown.toc(body));
        model.addAttribute("itemTags", tags.of(List.of(item.getId())).getOrDefault(item.getId(), List.of()));
        // 正文末尾的附件卡：挂到本文下面的补充笔记，点开就是那篇
        model.addAttribute("attachedNotes", attachedNotesOf(siblings, item, lockedIds));
        // 反过来，本文如果是补充笔记，标题下面给一条回「本体」的链接
        model.addAttribute("parentNote", parentNoteOf(siblings, item, lockedIds));
        // 九成八股正文里没有 ## 标题，右栏会空着；没有目录时改用「同分类的其他条目」填上
        model.addAttribute("rail", railOf(siblings, item, lockedIds));
        // 同一分类（同一个父级）里的下一篇：正文末尾给一个直达按钮，到尾了给个明确的终点
        model.addAttribute("nextNote", nextNoteOf(siblings, item, lockedIds));
        model.addAttribute("starred", viewer.userId() != null && stars.isStarred(viewer.userId(), item.getId()));
        // 只有自己的内容能标星级：星级是内容上的字段，不是「谁标的」，改别人的条目等于改别人的东西
        model.addAttribute("canRate", viewer.userId() != null && viewer.userId().equals(item.getOwnerId()));
        // 编辑页「返回」要回到这一页；这里给的是不带 contextPath 的路径（redirect: 和 @{} 各管各的）
        model.addAttribute("editBack", basePath(kind) + "/" + item.getSlug());

        // 右栏换成评论区：整篇评论 + 锚在某句话上的评论（楼中楼在 service 里组好）
        // 复制出来的副本要跟原版共用同一串评论，所以按「最初的来源条目」做 key
        long commentKey = commentKeyOf(item);
        List<CommentService.Thread> threads =
                comments.threads(commentKey, viewer.userId(), CommentService.COMMENT);
        model.addAttribute("threads", threads);
        model.addAttribute("commentCount", comments.count(commentKey, CommentService.COMMENT));
        // 注释是另一条线：正文里画短虚下划线，点开弹窗看，和右栏评论同一张表、不同 entry_kind
        List<CommentService.Thread> annotations =
                comments.threads(commentKey, viewer.userId(), CommentService.ANNOTATION);
        model.addAttribute("annotations", annotations);
        model.addAttribute("annotationCount", comments.count(commentKey, CommentService.ANNOTATION));
        model.addAttribute("canComment", viewer.userId() != null);
        model.addAttribute("commentSlug", item.getSlug());
        // 重点高亮：只取自己标的那些，别人标的看不到
        model.addAttribute("highlights", highlights.list(item.getId(),
                viewer.userId() == null ? 0L : viewer.userId()));
        return "detail";
    }

    /**
     * 同一分类（同一个父级）里的下一篇：按 sortOrder、id 的顺序取排在当前这条后面的第一条。
     * 补充笔记不参与（它跟在附录里，不是同级的正文）；锁着的条目（登录后可见）也不指过去，
     * 免得游客点进去是 403。没有下一篇就返回 null，页面显示「已经是这个分类的最后一篇」。
     */
    private AttachedNote nextNoteOf(List<ContentItem> siblings, ContentItem current, Set<Long> lockedIds) {
        List<ContentItem> peers = new ArrayList<>();
        for (ContentItem s : siblings) {
            if (Objects.equals(s.getParentId(), current.getParentId())
                    && s.getSupplementOfId() == null
                    && !lockedIds.contains(s.getId())) {
                peers.add(s);
            }
        }
        peers.sort(Comparator.comparingInt(ContentItem::getSortOrder).thenComparing(ContentItem::getId));
        for (ContentItem s : peers) {
            if (s.getId().equals(current.getId())) {
                continue;
            }
            boolean after = s.getSortOrder() > current.getSortOrder()
                    || (s.getSortOrder() == current.getSortOrder() && s.getId() > current.getId());
            if (after) {
                return new AttachedNote(s.getTitle(), s.getSlug(), null);
            }
        }
        return null;
    }

    /**
     * 挂到当前正文下面的补充笔记（supplement_of_id = 当前条目），正文末尾用「附件卡」列出来。
     * 卡片上显示的体积按正文 UTF-8 字节数折算，只用来给个量级参考。
     */
    private List<AttachedNote> attachedNotesOf(List<ContentItem> siblings, ContentItem current, Set<Long> lockedIds) {
        List<ContentItem> kids = new ArrayList<>();
        for (ContentItem s : siblings) {
            if (current.getId().equals(s.getSupplementOfId()) && !lockedIds.contains(s.getId())) {
                kids.add(s);
            }
        }
        if (kids.isEmpty()) {
            return List.of();
        }
        kids.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        List<AttachedNote> out = new ArrayList<>(kids.size());
        for (ContentItem k : kids) {
            out.add(new AttachedNote(k.getTitle(), k.getSlug(), sizeOf(k.getBody())));
        }
        return out;
    }

    /** 当前这条是补充笔记时，找出它的「本体」，在标题下面给一条回链。 */
    private AttachedNote parentNoteOf(List<ContentItem> siblings, ContentItem current, Set<Long> lockedIds) {
        if (current.getSupplementOfId() == null) {
            return null;
        }
        for (ContentItem s : siblings) {
            if (s.getId().equals(current.getSupplementOfId()) && !lockedIds.contains(s.getId())) {
                return new AttachedNote(s.getTitle(), s.getSlug(), sizeOf(s.getBody()));
            }
        }
        return null;
    }

    private static String sizeOf(String body) {
        if (body == null || body.isEmpty()) {
            return "0 KB";
        }
        double kb = body.getBytes(StandardCharsets.UTF_8).length / 1024.0;
        String num = kb >= 10 ? String.valueOf(Math.round(kb)) : String.format(java.util.Locale.ROOT, "%.1f", kb);
        return num + " KB";
    }

    /** 右栏的替代目录：同一父节点下的其他条目，至少 2 条才有意义。 */    private List<ContentItem> railOf(List<ContentItem> siblings, ContentItem current, Set<Long> lockedIds) {
        List<ContentItem> same = new ArrayList<>();
        for (ContentItem s : siblings) {
            if (s.getId().equals(current.getId()) || lockedIds.contains(s.getId())) {
                continue;
            }
            if (Objects.equals(s.getParentId(), current.getParentId())) {
                same.add(s);
            }
        }
        if (same.size() < 2) {
            return List.of();
        }
        same.sort(Comparator.comparingInt(ContentItem::getSortOrder)
                .thenComparing(ContentItem::getTitle, Comparator.nullsLast(String::compareTo)));
        return same;
    }

    /** 滚动进度：离开页面时前端发一次，下次打开同一条按它滚回去。 */
    @PostMapping("/position/{slug}")
    @ResponseBody
    public String savePosition(@PathVariable String slug,
                               @RequestParam(defaultValue = "0") double progress,
                               Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            return "";
        }
        ContentItem item = resolveAnyKind(slug, viewer);
        if (viewer.isSelf(item)) {
            positions.rememberProgress(viewer.userId(), item.getKind(), item.getId(), progress);
        }
        return "";
    }

    /**
     * 发表评论 / 注释：可以对整篇、选中的一段文字、或者整段/整个代码块；parentId 有值就是楼中楼回复。
     * entryKind=ANNOTATION 时存成注释（正文里画短虚下划线、点开是弹窗），这种必须带引文。
     */
    @PostMapping("/comment/{slug}")
    public String addComment(@PathVariable String slug,
                             @RequestParam(required = false) String entryKind,
                             @RequestParam(required = false) String quote,
                             @RequestParam(required = false) String anchorKind,
                             @RequestParam(required = false) Integer blockIndex,
                             @RequestParam(required = false) String prefix,
                             @RequestParam(required = false) String suffix,
                             @RequestParam(required = false) Long parentId,
                             @RequestParam String body,
                             Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            return "redirect:" + contextPath() + "/login";
        }
        ContentItem item = resolveAnyKind(slug, viewer);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug() + "#comments";
        }
        boolean annotation = CommentService.ANNOTATION.equalsIgnoreCase(entryKind == null ? "" : entryKind.trim());
        // 评论和注释按不同的上限截断：长的解释走注释，评论只收短句
        int limit = annotation ? MAX_ANNOTATION : MAX_COMMENT;
        if (text.length() > limit) {
            text = text.substring(0, limit);
        }
        String anchor = (quote == null || quote.isBlank()) ? null : quote.trim();
        // 注释没有引文就没有落点，收下也画不出下划线
        if (annotation && (anchor == null || anchor.isEmpty())) {
            return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug();
        }
        // 10 秒内只收一条：连点、刷屏都卡在这里，不落库
        if (!commentLimiter.tryAcquire(viewer.userId())) {
            return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug() + "#comments";
        }
        String clean = commentFilter.mask(text);
        long id = comments.add(commentKeyOf(item), viewer.userId(),
                annotation ? CommentService.ANNOTATION : CommentService.COMMENT,
                anchorKind, anchor, trim40(prefix), trim40(suffix), blockIndex, parentId, clean);
        return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug() + "#c" + id;
    }

    /** 锚点前后文只留四十个字，够消歧了，也别把整篇塞进库。 */
    private static String trim40(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= 40 ? t : t.substring(t.length() - 40);
    }

    /** 引文后面的前四十个字。 */
    private static String head40(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= 40 ? t : t.substring(0, 40);
    }

    /** 删评论：自己的、或者管理员；软删，楼里其他人的回复保留。 */
    @PostMapping("/comment/{slug}/{id}/delete")
    public String deleteComment(@PathVariable String slug, @PathVariable Long id, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            return "redirect:" + contextPath() + "/login";
        }
        ContentItem item = resolveAnyKind(slug, viewer);
        comments.remove(id, viewer.userId(), viewer.admin());
        return "redirect:" + basePath(item.getKind()) + "/" + item.getSlug() + "#comments";
    }

    /**
     * 标一处重点高亮：选中文字 → 挑个颜色 → 存下来。
     * 返回新记录的 id，前端当场画上，不刷新页面。
     */
    @PostMapping("/highlight/{slug}")
    @ResponseBody
    public String addHighlight(@PathVariable String slug,
                               @RequestParam(required = false) String quote,
                               @RequestParam(required = false) String anchorKind,
                               @RequestParam(required = false) Integer blockIndex,
                               @RequestParam(required = false) String prefix,
                               @RequestParam(required = false) String suffix,
                               @RequestParam(required = false) String color,
                               Authentication auth) {
        Viewer viewer = viewers.current(auth);
        String text = quote == null ? "" : quote.trim();
        if (viewer.userId() == null || text.isEmpty()) {
            return "";
        }
        if (text.length() > MAX_HIGHLIGHT) {
            text = text.substring(0, MAX_HIGHLIGHT);
        }
        ContentItem item = resolveAnyKind(slug, viewer);
        long id = highlights.add(item.getId(), viewer.userId(), anchorKind, text,
                trim40(prefix), head40(suffix), blockIndex, color);
        return String.valueOf(id);
    }

    /** 取消一处高亮：只能取消自己标的。 */
    @PostMapping("/highlight/{slug}/{id}/delete")
    @ResponseBody
    public String removeHighlight(@PathVariable String slug, @PathVariable Long id, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            return "";
        }
        highlights.remove(id, viewer.userId());
        return "";
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
            // 标题 id 是按标题文字生成的，可能是中文；原样塞进 Location 会被 Tomcat 丢掉整个头
            target = target + "#" + URLEncoder.encode(anchor, StandardCharsets.UTF_8).replace("+", "%20");
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
        model.addAttribute("basePathOf", basePathOf());
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

    /**
     * 星级：点第 n 颗设成 n 星，点当前那一颗清回 0 星（增、改、删都是这一个口子）。
     * 页面上的星星用 fetch 提交，回 204 就够；从别处直接 POST 过来则跳回详情页。
     */
    @PostMapping("/rate/{slug}")
    public void rate(@PathVariable String slug,
                     @RequestParam(name = "rating", defaultValue = "0") int rating,
                     Authentication auth, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Viewer viewer = viewers.current(auth);
        ContentItem item = resolveAnyKind(slug, viewer);
        if (viewer.userId() == null || !viewer.userId().equals(item.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        ratings.set(item.getId(), rating);
        if ("fetch".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        response.sendRedirect(request.getContextPath() + basePath(item.getKind()) + "/" + item.getSlug());
    }

    /** 列表里这批条目的 id，标签是一次查一批，不要每张卡片查一次。 */
    private static List<Long> idsOf(List<ContentItem> items) {
        List<Long> ids = new ArrayList<>(items.size());
        for (ContentItem i : items) {
            ids.add(i.getId());
        }
        return ids;
    }

    /** 星级标签的筛选项：star = 只看收藏，3/2/1 = 这一档及以上，0 = 只看没标的；别的值当没选。 */
    private static String pickRate(String rate) {
        if (rate == null) {
            return null;
        }
        return switch (rate) {
            case "star", "3", "2", "1", "0" -> rate;
            default -> null;
        };
    }

    /** 每个标签上显示的条数，按筛选前的全部卡片数：筛完还要知道别的档有多少。 */
    private static Map<String, Integer> rateCounts(List<ContentItem> leaves, Set<Long> starIds) {
        int starred = 0;
        int r3 = 0;
        int r2 = 0;
        int r1 = 0;
        int none = 0;
        for (ContentItem i : leaves) {
            if (starIds.contains(i.getId())) {
                starred++;
            }
            if (i.getRating() >= 3) {
                r3++;
            }
            if (i.getRating() >= 2) {
                r2++;
            }
            if (i.getRating() >= 1) {
                r1++;
            }
            if (i.getRating() == 0) {
                none++;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", leaves.size());
        counts.put("star", starred);
        counts.put("3", r3);
        counts.put("2", r2);
        counts.put("1", r1);
        counts.put("0", none);
        return counts;
    }

    private static List<ContentItem> byRate(List<ContentItem> leaves, String filter, Set<Long> starIds) {
        if (filter == null) {
            return leaves;
        }
        List<ContentItem> kept = new ArrayList<>();
        for (ContentItem i : leaves) {
            boolean hit = switch (filter) {
                case "star" -> starIds.contains(i.getId());
                case "3" -> i.getRating() >= 3;
                case "2" -> i.getRating() >= 2;
                case "1" -> i.getRating() >= 1;
                case "0" -> i.getRating() == 0;
                default -> true;
            };
            if (hit) {
                kept.add(i);
            }
        }
        return kept;
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
        model.addAttribute("basePathOf", basePathOf());
        model.addAttribute("tabActive", "memo");
        return "memo";
    }

    /** 模板里的链接前缀：必须带 contextPath（/app），否则点开就是 nginx 的 404。 */
    private java.util.function.Function<ContentKind, String> basePathOf() {
        String ctx = contextPath();
        return kind -> ctx + basePath(kind);
    }

    /**
     * 一个分类下的卡片；整页没有分类时就是一组，标题为空、不显示小标题。
     * subGroups 非空时页面多一层小标题（条目太多的小节才拆），subGroups 为空就平铺。
     * preview 是这一组前两条的标题，收起时用它在卡片里撑住右边的空当。
     */
    public record ItemGroup(String title, String slug, List<ContentItem> items,
                            List<SubGroupService.SubGroup> subGroups, String preview) {
        public boolean hasSub() {
            return subGroups != null && !subGroups.isEmpty();
        }
    }

    /** 正文末尾那张附件卡：标题、slug、体积（KB）。 */
    public record AttachedNote(String title, String slug, String size) {
    }

    /** 树超过这么多节点就改成「顶层 + 当前路径」，其余展开时再取。 */
    private static final int TREE_LAZY_OVER = 60;

    /** 列表页一次先给多少条，剩下的滚到底再取。 */
    private static final int BATCH_ITEMS = 40;

    /** 小分区照旧整棵渲染；几百条内容的分区只给顶层和当前这条的路径。 */
    private List<NoteTreeService.Node> visibleTree(List<ContentItem> all, Set<Long> lockedIds, Long activeId) {
        List<NoteTreeService.Node> built = tree.build(all, lockedIds);
        return countNodes(built) > TREE_LAZY_OVER ? tree.pruned(built, activeId) : built;
    }

    private static int countNodes(List<NoteTreeService.Node> nodes) {
        int n = 0;
        for (NoteTreeService.Node node : nodes) {
            n += 1 + countNodes(node.children());
        }
        return n;
    }

    /** 展开某个分类时补它下面的条目（列表页的树只给了顶层）。走 /api 前缀，避免和 /{kind}/{slug} 抢路由。 */
    @GetMapping("/api/tree/{kind}")
    public String treeChildren(@PathVariable String kind,
                               @RequestParam(name = "parent") String parentSlug,
                               Model model, Authentication auth) {
        ContentKind k;
        try {
            k = ContentKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Viewer viewer = viewers.current(auth);
        Set<Long> lockedIds = new LinkedHashSet<>();
        List<ContentItem> all = filtered(poolForListing(k, viewer), viewer, lockedIds);

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

        ContentItem root = null;
        for (ContentItem i : all) {
            if (parentSlug.equals(i.getSlug())) {
                root = i;
                break;
            }
        }
        if (root == null) {
            model.addAttribute("nodes", List.of());
        } else {
            List<ContentItem> kids = new ArrayList<>();
            for (ContentItem i : leaves) {
                if (root.getId().equals(i.getParentId())) {
                    kids.add(i);
                }
            }
            model.addAttribute("nodes", tree.build(kids, lockedIds));
        }
        model.addAttribute("activeId", null);
        model.addAttribute("basePath", contextPath() + basePath(k));
        model.addAttribute("treeBase", contextPath() + "/api/tree/" + kind.toLowerCase(java.util.Locale.ROOT));
        model.addAttribute("starIds", stars.starredIds(viewer.userId()));
        model.addAttribute("bookmarkIds", bookmarks.ids(viewer.userId()));
        return "fragments/tree :: nodes";
    }

    /** 按父节点把列表拆成小节，小节顺序跟左侧树一致（sortOrder + 标题）。 */
    private List<ItemGroup> groups(List<ContentItem> all, List<ContentItem> leaves) {
        Map<Long, ContentItem> byId = new LinkedHashMap<>();
        for (ContentItem item : all) {
            byId.put(item.getId(), item);
        }

        Map<Long, List<ContentItem>> buckets = new LinkedHashMap<>();
        for (ContentItem leaf : leaves) {
            Long pid = leaf.getParentId();
            Long key = (pid != null && byId.containsKey(pid)) ? pid : 0L;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(leaf);
        }
        // 目录即使还空着也占一个分组，新建完立刻能在列表上看到它
        for (ContentItem item : all) {
            if (item.isFolder()) {
                buckets.computeIfAbsent(item.getId(), k -> new ArrayList<>());
            }
        }

        List<ItemGroup> result = new ArrayList<>();
        buckets.keySet().stream()
                .filter(id -> id != 0L)
                .sorted(Comparator.comparingInt((Long id) -> byId.get(id).getSortOrder())
                        .thenComparing(id -> byId.get(id).getTitle(), Comparator.nullsLast(String::compareTo)))
                .forEach(id -> {
                    ContentItem parent = byId.get(id);
                    List<ContentItem> list = buckets.get(id);
                    result.add(new ItemGroup(parent.getTitle(), parent.getSlug(), list,
                            subGroups.split(parent.getSlug(), list), previewOf(list)));
                });
        if (buckets.containsKey(0L)) {
            result.add(new ItemGroup(null, null, buckets.get(0L), List.of(), ""));
        }
        return result;
    }

    /** 组内前两条标题，拼成一行给收起时的卡片用；标题太长由 CSS 截断。 */
    private static String previewOf(List<ContentItem> list) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ContentItem item : list) {
            if (n++ > 0) {
                sb.append(" · ");
            }
            sb.append(item.getTitle());
            if (n == 2) {
                break;
            }
        }
        return sb.toString();
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
        // 内容存在但看不了：分清楚「没登录」和「登录了但没权限」，文案不一样
        if (!candidates.isEmpty()) {
            throw forbidden(viewer.authenticated() ? REASON_PERMISSION : REASON_LOGIN);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }


    /** 副本沿 copied_from_id 往上找到最初那条，评论按它归并，所有人看到的是同一串。 */
    private long commentKeyOf(ContentItem item) {
        long key = item.getId();
        Long from = item.getCopiedFromId();
        int guard = 0;
        while (from != null && guard++ < 8) {
            key = from;
            ContentItem src = items.findById(from).orElse(null);
            from = src == null ? null : src.getCopiedFromId();
        }
        return key;
    }

    /** 403 的两种原因：没登录 / 登录了但这条不归你看。原因挂在 request 上，错误页据此换文案。 */
    public static final String REASON_LOGIN = "login";
    public static final String REASON_PERMISSION = "permission";

    private static ResponseStatusException forbidden(String reason) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute("errorReason", reason, RequestAttributes.SCOPE_REQUEST);
        }
        return new ResponseStatusException(HttpStatus.FORBIDDEN);
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
                return "闪念";
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
