package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.Role;
import cn.bytelove.notes.domain.Visibility;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VisibilityService {

    public boolean isOwner(Authentication auth) {
        return hasRole(auth, "ROLE_OWNER");
    }

    public boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
    }

    public boolean canView(Visibility visibility, Authentication auth) {
        if (visibility == null) {
            return false;
        }
        return switch (visibility) {
            case PUBLIC -> true;
            case MEMBER -> isAuthenticated(auth);
            case PRIVATE -> isOwner(auth);
        };
    }

    public List<Visibility> visibleTo(Authentication auth) {
        List<Visibility> list = new ArrayList<>();
        list.add(Visibility.PUBLIC);
        if (isAuthenticated(auth)) {
            list.add(Visibility.MEMBER);
        }
        if (isOwner(auth)) {
            list.add(Visibility.PRIVATE);
        }
        return list;
    }

    public Role currentRole(Authentication auth) {
        if (isOwner(auth)) {
            return Role.OWNER;
        }
        return isAuthenticated(auth) ? Role.VIEWER : null;
    }

    private boolean hasRole(Authentication auth, String role) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (role.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
