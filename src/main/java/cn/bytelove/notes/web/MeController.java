package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.UserBadge;
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
    private final TrashService trash;

    public MeController(AppUserRepository users, ContentItemRepository items,
                        ContentStarService stars, ViewerService viewers, TrashService trash) {
        this.users = users;
        this.items = items;
        this.stars = stars;
        this.viewers = viewers;
        this.trash = trash;
    }

    @GetMapping("/me")
    public String me(Model model, Authentication auth) {
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

        model.addAttribute("tabActive", "me");
        return "me";
    }
}
