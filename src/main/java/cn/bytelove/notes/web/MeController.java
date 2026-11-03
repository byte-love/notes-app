package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ApiTokenService;
import cn.bytelove.notes.service.BookmarkService;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.RatingService;
import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.UserBadge;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MeController {

    private final AppUserRepository users;
    private final ContentItemRepository items;
    private final ContentStarService stars;
    private final ViewerService viewers;
    private final TrashService trash;
    private final BookmarkService bookmarks;
    private final RatingService ratings;
    private final ApiTokenService tokens;

    public MeController(AppUserRepository users, ContentItemRepository items,
                        ContentStarService stars, ViewerService viewers, TrashService trash,
                        BookmarkService bookmarks, RatingService ratings, ApiTokenService tokens) {
        this.users = users;
        this.items = items;
        this.stars = stars;
        this.viewers = viewers;
        this.trash = trash;
        this.bookmarks = bookmarks;
        this.ratings = ratings;
        this.tokens = tokens;
    }

    @GetMapping("/me")
    public String me(Model model, Authentication auth, HttpServletRequest request) {
        Viewer viewer = viewers.current(auth);
        AppUser user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("displayName", user.getDisplayName());
        model.addAttribute("username", user.getUsername());
        model.addAttribute("joined", user.getCreatedAt());
        model.addAttribute("initial", UserBadge.initial(user.getDisplayName()));
        model.addAttribute("avatarHue", UserBadge.hue(user.getUsername()));

        model.addAttribute("noteCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.NOTE));
        model.addAttribute("baguCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.BAGU));
        model.addAttribute("algoCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.ALGO));
        model.addAttribute("projectCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.PROJECT));
        model.addAttribute("memoCount", stars.count(viewer.userId()));
        model.addAttribute("trashCount", trash.list(viewer.userId()).size());
        model.addAttribute("bookmarkCount", bookmarks.count(viewer.userId()));
        model.addAttribute("ratingSummary", ratings.summary(viewer.userId()));

        // AI 接入：只展示 token 的前几位，明文在生成那一刻由 flash 带过来一次
        tokens.describe(viewer.userId()).ifPresent(t -> {
            model.addAttribute("tokenHint", t[0]);
            model.addAttribute("tokenCreatedAt", t[1]);
        });
        model.addAttribute("mcpUrl", request.getContextPath() + "/mcp");
        model.addAttribute("apiBase", request.getContextPath() + "/api/ai");

        model.addAttribute("tabActive", "me");
        return "me";
    }

    /** 生成（或重新生成）个人 token；旧的立即作废。 */
    @PostMapping("/me/token")
    public String issueToken(Authentication auth, RedirectAttributes redirect) {
        Viewer viewer = viewers.current(auth);
        redirect.addFlashAttribute("newToken", tokens.issue(viewer.userId()));
        return "redirect:/me";
    }

    @PostMapping("/me/token/delete")
    public String revokeToken(Authentication auth) {
        tokens.revoke(viewers.current(auth).userId());
        return "redirect:/me";
    }
}
