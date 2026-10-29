package cn.bytelove.notes.web;

import cn.bytelove.notes.service.ChangelogService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@Controller
public class ChangelogController {

    private final ChangelogService changelog;
    private final ViewerService viewers;

    public ChangelogController(ChangelogService changelog, ViewerService viewers) {
        this.changelog = changelog;
        this.viewers = viewers;
    }

    @GetMapping("/changelog")
    public String changelog(Model model) {
        model.addAttribute("releases", changelog.releases());
        model.addAttribute("tabActive", "updates");
        return "changelog";
    }

    @GetMapping("/admin/changelog")
    public String manage(Model model, Authentication auth) {
        requireAdmin(auth);
        model.addAttribute("releases", changelog.releases());
        model.addAttribute("tabActive", "admin");
        return "admin/changelog";
    }

    @PostMapping("/admin/changelog/new")
    public String create(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate releasedOn,
                         @RequestParam(required = false) String version,
                         @RequestParam String title,
                         @RequestParam String body,
                         Authentication auth) {
        requireAdmin(auth);
        changelog.addRelease(releasedOn, version == null || version.isBlank() ? null : version.trim(),
                title.trim(), body);
        return "redirect:/admin/changelog";
    }

    @PostMapping("/admin/changelog/delete")
    public String delete(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate releasedOn,
                         @RequestParam String title,
                         Authentication auth) {
        requireAdmin(auth);
        changelog.deleteRelease(releasedOn, title);
        return "redirect:/admin/changelog";
    }

    @PostMapping("/admin/changelog/entry/{id}/delete")
    public String deleteEntry(@PathVariable long id, Authentication auth) {
        requireAdmin(auth);
        changelog.deleteEntry(id);
        return "redirect:/admin/changelog";
    }

    /** 更新日志是站点的门面，只让管理员改。 */
    private void requireAdmin(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (!viewer.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
