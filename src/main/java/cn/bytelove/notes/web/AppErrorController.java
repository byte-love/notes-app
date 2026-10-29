package cn.bytelove.notes.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 兜底错误页。不放这个的话，404 会落到 Spring Boot 的白标页
 * 「This application has no explicit mapping for /error」，用户一脸懵。
 */
@Controller
public class AppErrorController implements ErrorController {

    @RequestMapping("/error")
    public String error(HttpServletRequest request, Model model) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = raw == null ? 500 : Integer.parseInt(raw.toString());

        String heading;
        String hint;
        boolean needLogin = false;
        switch (status) {
            case 403 -> {
                heading = "这条内容需要登录";
                hint = "它只对登录用户开放。登录之后再回来就能看到。";
                needLogin = true;
            }
            case 404 -> {
                heading = "页面不存在";
                hint = "这个地址没有对应的内容，可能是链接过期了，或者内容已经被删掉。";
            }
            default -> {
                heading = "服务器出了点问题";
                hint = "刚才那个操作没成功，稍后再试一次。";
            }
        }

        model.addAttribute("status", status);
        model.addAttribute("heading", heading);
        model.addAttribute("hint", hint);
        model.addAttribute("needLogin", needLogin);
        model.addAttribute("pageTitle", heading + " · byte-love");
        model.addAttribute("tabActive", "none");
        return "error";
    }
}
