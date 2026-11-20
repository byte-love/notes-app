package cn.bytelove.notes.web;

import cn.bytelove.notes.service.CategoryService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设置页：外观、背景、正文这些「跟页面怎么显示有关」的偏好都放这儿。
 * 账号页（/me）只管账号本身的信息和凭证，不再混着放（2026-11-16 用户要求分开）。
 * 笔记分类也在这儿管：它是「我的笔记」这个空间的结构，不属于任何单篇内容。
 */
@Controller
public class SettingsController {

    private final CategoryService categories;
    private final ViewerService viewers;

    public SettingsController(CategoryService categories, ViewerService viewers) {
        this.categories = categories;
        this.viewers = viewers;
    }

    @GetMapping("/settings")
    public String settings(Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() != null) {
            model.addAttribute("myCategories", categories.of(viewer.userId()));
            model.addAttribute("catCounts", categories.counts(viewer.userId()));
        }
        return "settings";
    }
}
