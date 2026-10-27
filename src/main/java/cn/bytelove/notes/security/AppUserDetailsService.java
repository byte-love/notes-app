package cn.bytelove.notes.security;

import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.service.LoginAttemptService;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final LoginAttemptService attempts;

    public AppUserDetailsService(AppUserRepository users, LoginAttemptService attempts) {
        this.users = users;
        this.attempts = attempts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (attempts.isBlocked(username)) {
            throw new LockedException("账号已被临时锁定");
        }
        AppUser u = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        return User.withUsername(u.getUsername())
                .password(u.getPasswordHash())
                .roles(u.getRole().name())
                .disabled(!u.isEnabled())
                .build();
    }
}
