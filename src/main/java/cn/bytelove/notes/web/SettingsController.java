package cn.bytelove.notes.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 设置页：外观、背景、正文这些「跟页面怎么显示有关」的偏好都放这儿。
 * 账号页（/me）只管账号本身的信息和凭证，不再混着放（2026-11-16 用户要求分开）。
 */
@Controller
public class SettingsController {

    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }
}
