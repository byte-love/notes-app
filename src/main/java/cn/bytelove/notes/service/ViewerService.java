package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * 把 Spring Security 的 Authentication 翻译成「谁在看」。
 * 页面判断可读性靠 Viewer，不再直接读角色字符串。
 */
@Service
public class ViewerService {

    private final AppUserRepository users;

    public ViewerService(AppUserRepository users) {
        this.users = users;
    }

    public Viewer current(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
            return Viewer.anonymous();
        }
        boolean admin = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) {
                admin = true;
                break;
            }
        }
        Long id = users.findByUsername(auth.getName()).map(AppUser::getId).orElse(null);
        return new Viewer(id, admin, id != null);
    }
}
