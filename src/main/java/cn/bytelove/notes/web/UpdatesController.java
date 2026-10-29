package cn.bytelove.notes.web;

import cn.bytelove.notes.service.UpdateFeedService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Controller
public class UpdatesController {

    private final UpdateFeedService feed;
    private final ViewerService viewers;

    public UpdatesController(UpdateFeedService feed, ViewerService viewers) {
        this.feed = feed;
        this.viewers = viewers;
    }

    @GetMapping("/updates")
    public String updates(@RequestParam(required = false) String kind, Model model, Authentication auth) {
        String filter = (kind == null || kind.isBlank()) ? null : kind.trim().toUpperCase(Locale.ROOT);

        model.addAttribute("days", feed.timeline(filter));
        model.addAttribute("filterKind", filter);
        model.addAttribute("kinds", new String[]{"NOTE", "BAGU", "ALGO", "PROJECT"});
        model.addAttribute("tabActive", "updates");
        return "updates";
    }

    /** 写一条今天的维护记录。 */
    @PostMapping("/updates/log")
    public String addLog(@RequestParam String body, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        feed.addLog(viewer.userId(), body);
        return "redirect:/updates";
    }
}
