package cn.bytelove.notes.web;

import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class TrashController {

    private final TrashService trash;
    private final ViewerService viewers;

    public TrashController(TrashService trash, ViewerService viewers) {
        this.trash = trash;
        this.viewers = viewers;
    }

    @GetMapping("/trash")
    public String trash(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        // 顺手清掉过期的，不用等定时任务
        trash.purgeExpired();
        model.addAttribute("items", trash.list(viewer.userId()));
        model.addAttribute("keepDays", TrashService.KEEP_DAYS);
        model.addAttribute("tabActive", "me");
        return "trash";
    }

    @PostMapping("/trash/{id}/restore")
    public String restore(@PathVariable Long id, Authentication auth) {
        trash.restore(id, viewers.current(auth).userId());
        return "redirect:/trash";
    }

    @PostMapping("/trash/{id}/purge")
    public String purge(@PathVariable Long id, Authentication auth) {
        trash.purge(id, viewers.current(auth).userId());
        return "redirect:/trash";
    }
}
