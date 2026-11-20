package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.NoteCategory;
import cn.bytelove.notes.service.CategoryService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分类的写操作：设置页里的改名 / 排序 / 删除，列表页签上的「+」新建，都走这里。
 * 只用表单 POST + 重定向，不额外引前端框架；权限按「这个分类是不是你的」判。
 */
@Controller
public class CategoryController {

    private final CategoryService categories;
    private final ViewerService viewers;

    public CategoryController(CategoryService categories, ViewerService viewers) {
        this.categories = categories;
        this.viewers = viewers;
    }

    @PostMapping("/settings/categories")
    public String create(@RequestParam("name") String name,
                         @RequestParam(name = "back", required = false) String back,
                         Authentication auth, HttpServletRequest request) {
        long userId = requireUser(auth);
        NoteCategory created = categories.create(userId, name);
        // 从页签上的「+」点进来的，建完直接落到这个新分类里
        return "redirect:" + safePath(back, "/c/" + created.getSlug(), request);
    }

    @PostMapping("/settings/categories/{id}/rename")
    public String rename(@PathVariable Long id,
                         @RequestParam("name") String name,
                         @RequestParam(name = "back", required = false) String back,
                         Authentication auth, HttpServletRequest request) {
        categories.rename(requireUser(auth), id, name);
        return "redirect:" + safePath(back, "/settings", request);
    }

    @PostMapping("/settings/categories/{id}/move")
    public String move(@PathVariable Long id,
                       @RequestParam(name = "dir", defaultValue = "up") String dir,
                       @RequestParam(name = "back", required = false) String back,
                       Authentication auth, HttpServletRequest request) {
        categories.move(requireUser(auth), id, !"down".equalsIgnoreCase(dir));
        return "redirect:" + safePath(back, "/settings", request);
    }

    @PostMapping("/settings/categories/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(name = "back", required = false) String back,
                         Authentication auth, HttpServletRequest request) {
        categories.delete(requireUser(auth), id);
        return "redirect:" + safePath(back, "/settings", request);
    }

    private long requireUser(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return viewer.userId();
    }

    /**
     * 只回站内路径：外部地址、协议相对地址一律退回默认值。
     * 前端有时会把带上下文前缀的 /app/notes 递过来，而 redirect: 的值本身已经
     * 是上下文相对路径，再拼一次就成了 /app/app/notes，所以这里先削掉前缀。
     */
    private static String safePath(String back, String fallback, HttpServletRequest request) {
        if (back == null || back.isBlank()) {
            return fallback;
        }
        String path = back.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            return fallback;
        }
        String ctx = request == null ? "" : request.getContextPath();
        if (!ctx.isEmpty() && path.startsWith(ctx + "/")) {
            path = path.substring(ctx.length());
        }
        return path;
    }
}
