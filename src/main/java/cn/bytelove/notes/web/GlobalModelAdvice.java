package cn.bytelove.notes.web;

import cn.bytelove.notes.config.AppProperties;
import cn.bytelove.notes.service.UserBadge;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    /** 顶栏账号块要的东西：名字、首字母、色相。 */
    public record NavUser(String name, String initial, int hue) {
    }

    private final AppProperties props;

    public GlobalModelAdvice(AppProperties props) {
        this.props = props;
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
}
