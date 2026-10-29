package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MeController {

    private final AppUserRepository users;
    private final ContentItemRepository items;
    private final ContentStarService stars;
    private final ViewerService viewers;

    public MeController(AppUserRepository users, ContentItemRepository items,
                        ContentStarService stars, ViewerService viewers) {
        this.users = users;
        this.items = items;
        this.stars = stars;
        this.viewers = viewers;
    }

    @GetMapping("/me")
    public String me(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        AppUser user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("displayName", user.getDisplayName());
        model.addAttribute("username", user.getUsername());
        model.addAttribute("joined", user.getCreatedAt());
        model.addAttribute("initial", initial(user.getDisplayName()));
        model.addAttribute("avatarHue", hue(user.getUsername()));

        model.addAttribute("noteCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.NOTE));
        model.addAttribute("baguCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.BAGU));
        model.addAttribute("algoCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.ALGO));
        model.addAttribute("projectCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.PROJECT));
        model.addAttribute("memoCount", stars.count(viewer.userId()));

        model.addAttribute("tabActive", "me");
        return "me";
    }

    /** 头像用名字首字，不搞上传，也就没有文件要存。 */
    private static String initial(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    /** 同一个人每次都得到同一个色相，不同人尽量分开。 */
    private static int hue(String seed) {
        if (seed == null || seed.isEmpty()) {
            return 210;
        }
        int h = 0;
        for (int i = 0; i < seed.length(); i++) {
            h = (h * 31 + seed.charAt(i)) % 360;
        }
        return Math.floorMod(h, 360);
    }
}
