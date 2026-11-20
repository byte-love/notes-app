package cn.bytelove.notes.web;

import cn.bytelove.notes.config.AppProperties;
import cn.bytelove.notes.service.CategoryService;
import cn.bytelove.notes.service.UserBadge;
import cn.bytelove.notes.service.ViewerService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    /** 顶栏账号块要的东西：名字、首字母、色相。 */
    public record NavUser(String name, String initial, int hue) {
    }

    private final AppProperties props;
    private final ViewerService viewers;
    private final CategoryService categories;

    public GlobalModelAdvice(AppProperties props, ViewerService viewers, CategoryService categories) {
        this.props = props;
        this.viewers = viewers;
        this.categories = categories;
    }

    @ModelAttribute("tabProjectsHref")
    public String tabProjectsHref() {
        String href = props.tabProjectsHref();
        return (href == null || href.isBlank()) ? "/" : href;
    }

    @ModelAttribute("registerEnabled")
    public boolean registerEnabled() {
        return Boolean.TRUE.equals(props.registerEnabled());
    }

    @ModelAttribute("navUser")
    public NavUser navUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return null;
        }
        String name = auth.getName();
        return new NavUser(name, UserBadge.initial(name), UserBadge.hue(name));
    }

    /**
     * 顶栏「我的笔记」的落点：分类是自己排的，所以指向第一个页签；
     * 一个分类都没有（新账号或全删了）才退回老列表页。
     */
    @ModelAttribute("mineHome")
    public String mineHome(Authentication auth) {
        Long userId = viewers.current(auth).userId();
        if (userId == null) {
            return "/notes";
        }
        return categories.homePath(userId);
    }

    /** 术语解释可以在页面上的浮窗里直接改，只有管理员看得到入口。 */
    @ModelAttribute("glossaryAdmin")
    public boolean glossaryAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * 标签的配色：同一个词永远同一个颜色（按词本身算，不随机），模板里写成
     * {@code data-tone="${tagTone.of(t)}"}，具体色值在 app.css 的 t0~t7 里。
     */
    public static final class TagTone {
        public String of(String tag) {
            if (tag == null || tag.isEmpty()) {
                return "t0";
            }
            int h = 0;
            for (int i = 0; i < tag.length(); i++) {
                h = h * 31 + tag.charAt(i);
            }
            return "t" + Math.floorMod(h, 8);
        }
    }

    @ModelAttribute("tagTone")
    public TagTone tagTone() {
        return new TagTone();
    }
}
