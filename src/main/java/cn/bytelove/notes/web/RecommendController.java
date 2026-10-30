package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.domain.Visibility;
import cn.bytelove.notes.service.RecommendService;
import cn.bytelove.notes.service.RecommendService.Card;
import cn.bytelove.notes.service.ViewerService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 推荐栏：公开的推荐内容当模板，点一下就复制一份到自己空间。
 */
@Controller
public class RecommendController {

    private final RecommendService recommend;
    private final ViewerService viewers;

    public RecommendController(RecommendService recommend, ViewerService viewers) {
        this.recommend = recommend;
        this.viewers = viewers;
    }

    public record Section(ContentKind kind, String label, List<Card> cards) {
    }

    @GetMapping("/explore")
    public String explore(@RequestParam(required = false) String kind,
                          @RequestParam(required = false) Integer copied,
                          Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentKind only = parseKind(kind);

        List<Section> sections = new ArrayList<>();
        boolean any = false;
        for (ContentKind k : new ContentKind[]{ContentKind.NOTE, ContentKind.BAGU,
                ContentKind.ALGO, ContentKind.PROJECT}) {
            if (only != null && only != k) {
                continue;
            }
            List<Card> cards = recommend.cards(k, viewer);
            if (!cards.isEmpty()) {
                any = true;
            }
            sections.add(new Section(k, k.getLabel(), cards));
        }

        model.addAttribute("sections", sections);
        model.addAttribute("hasAny", any);
        model.addAttribute("filterKind", only);
        model.addAttribute("kinds", List.of(ContentKind.NOTE, ContentKind.BAGU,
                ContentKind.ALGO, ContentKind.PROJECT));
        model.addAttribute("copied", copied);
        model.addAttribute("joinedIds", recommend.joined(viewer.userId()));
        model.addAttribute("tabActive", "explore");
        return "explore";
    }

    @GetMapping("/explore/{kind}/{slug}")
    public String group(@PathVariable String kind, @PathVariable String slug,
                        Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentKind k = parseKind(kind);
        if (k == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<ContentItem> list = recommend.group(k, slug);
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 游客看不全的时候，锁住的那部分只列标题、不给链接
        Set<Long> lockedIds = new LinkedHashSet<>();
        if (!viewer.authenticated()) {
            for (ContentItem i : list) {
                if (i.getVisibility() == Visibility.MEMBER) {
                    lockedIds.add(i.getId());
                }
            }
        }

        // 根节点是路径里那一条，不是排序后的第一条
        ContentItem root = null;
        for (ContentItem i : list) {
            if (i.getSlug().equals(slug)) {
                root = i;
                break;
            }
        }
        List<ContentItem> ordered = new ArrayList<>();
        if (root == null) {
            root = list.get(0);
            ordered.addAll(list);
        } else {
            ordered.add(root);
            for (ContentItem i : list) {
                if (!i.getId().equals(root.getId())) {
                    ordered.add(i);
                }
            }
        }

        model.addAttribute("items", ordered);
        model.addAttribute("lockedIds", lockedIds);
        model.addAttribute("root", root);
        model.addAttribute("kindLabel", k.getLabel());
        model.addAttribute("basePath", contextPath() + basePath(k));
        model.addAttribute("tabActive", "explore");
        return "explore-group";
    }

    @PostMapping("/explore/copy/{id}")
    public String copy(@PathVariable Long id, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        int n = recommend.copyTo(viewer.userId(), id);
        return "redirect:/explore?copied=" + n;
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

    private static String basePath(ContentKind kind) {
        return switch (kind) {
            case BAGU -> "/bagu";
            case ALGO -> "/algo";
            case PROJECT -> "/projects";
            default -> "/notes";
        };
    }

    /** 应用挂在 /app 下，模板里的链接是字符串拼的，要显式带上前缀。 */
    private static String contextPath() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest().getContextPath();
        }
        return "";
    }
}
