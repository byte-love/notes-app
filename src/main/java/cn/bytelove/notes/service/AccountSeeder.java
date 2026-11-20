package cn.bytelove.notes.service;

import cn.bytelove.notes.config.AppProperties;
import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.Role;
import cn.bytelove.notes.repo.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动时按配置建好两个账号。已存在的账号不会被覆盖，避免重启把密码重置掉。
 */
@Component
public class AccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AccountSeeder(AppUserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed(props.ownerUsername(), props.ownerPassword(), "管理员", Role.ADMIN);
        seed(props.viewerUsername(), props.viewerPassword(), "用户", Role.USER);
    }

    private void seed(String username, String rawPassword, String displayName, Role role) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("跳过创建 {}：未配置对应密码", username);
            return;
        }
        if (users.existsByUsername(username)) {
            return;
        }
        users.save(new AppUser(username, encoder.encode(rawPassword), displayName, role));
        log.info("已创建账号 {}（角色 {}）", username, role);
    }
}
