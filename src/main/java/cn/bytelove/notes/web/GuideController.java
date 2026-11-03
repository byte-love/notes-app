package cn.bytelove.notes.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 用户指南：一页讲清站里各个部分怎么用。
 * 页面是静态文案 + 截图（图片在 static/img/guide/），没有数据依赖。
 */
@Controller
public class GuideController {

    @GetMapping("/guide")
    public String guide(Model model) {
        model.addAttribute("tabActive", "guide");
        return "guide";
    }
}
