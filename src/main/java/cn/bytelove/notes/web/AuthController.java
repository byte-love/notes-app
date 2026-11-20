package cn.bytelove.notes.web;

import cn.bytelove.notes.config.AppProperties;
import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.Role;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.service.RegisterRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

@Controller
public class AuthController {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    private static final int MIN_PASSWORD = 8;

    private final AppProperties props;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final RegisterRateLimiter limiter;

    public AuthController(AppProperties props, AppUserRepository users, PasswordEncoder encoder,
                          RegisterRateLimiter limiter) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.limiter = limiter;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("error", error != null);
        return "login";
    }

    @GetMapping("/register")
    public String registerForm() {
        requireEnabled();
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String username,
                           @RequestParam String password,
                           @RequestParam String confirm,
                           HttpServletRequest request,
                           Model model) {
        requireEnabled();

        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            return fail(model, "用户名只能用字母、数字、下划线、连字符，3–32 位", name);
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            return fail(model, "密码至少 8 位", name);
        }
        if (!password.equals(confirm)) {
            return fail(model, "两次输入的密码不一致", name);
        }
        if (users.existsByUsername(name)) {
            return fail(model, "用户名已被占用", name);
        }
        if (!limiter.tryAcquire(clientIp(request))) {
            return fail(model, "注册太频繁，请稍后再试", name);
        }

        users.save(new AppUser(name, encoder.encode(password), name, Role.USER));

        // 注册完直接登录，省掉一次多余的登录动作
        var token = UsernamePasswordAuthenticationToken.authenticated(
                name, null, List.of(new SimpleGrantedAuthority("ROLE_" + Role.USER.name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        return "redirect:/notes";
    }

    private String fail(Model model, String message, String username) {
        model.addAttribute("error", message);
        model.addAttribute("username", username);
        return "register";
    }

    private void requireEnabled() {
        Boolean enabled = props.registerEnabled();
        if (enabled == null || !enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /** 应用在 nginx 后面，真实来源在 X-Forwarded-For 的第一段。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
