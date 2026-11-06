package cn.bytelove.notes.config;

import cn.bytelove.notes.service.LoginAttemptService;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, LoginAttemptService attempts) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 技术广场、用户指南和更新记录对游客开放，写操作要登录
                        .requestMatchers(HttpMethod.POST, "/explore/copy/**").authenticated()
                                                .requestMatchers("/explore", "/explore/**", "/guide",
                                                        "/changelog", "/updates").permitAll()
                        .requestMatchers("/", "/notes", "/notes/**", "/projects", "/projects/**",
                                "/bagu", "/bagu/**", "/interview", "/interview/**", "/algo", "/algo/**",
                                // /api/ai/** 和 /mcp 是给本地 AI 用的，没有会话，靠 Bearer token 自己鉴权
                                // /csrf 只回一份当前会话的 token，没有它前端没法在会话换掉后自愈
                                "/login", "/register", "/csrf", "/api/**", "/mcp", "/css/**", "/js/**", "/img/**",
                                "/media/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/star/**", "/rate/**", "/memo").authenticated()
                        .anyRequest().authenticated()
                )
                // 机器调用的接口不靠 cookie，也就没有 CSRF 可言：主站表单仍然照旧校验
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/ai/**", "/mcp"))
                .formLogin(login -> login
                        .loginPage("/login")
                        .successHandler((request, response, authentication) -> {
                            attempts.loginSucceeded(authentication.getName());
                            boolean owner = authentication.getAuthorities().stream()
                                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            response.sendRedirect(request.getContextPath() + (owner ? "/admin" : "/notes"));
                        })
                        .failureHandler((request, response, exception) -> {
                            attempts.loginFailed(request.getParameter("username"));
                            response.sendRedirect(request.getContextPath() + "/login?error");
                        })
                        .permitAll()
                )
                // 会话存在内存里，重启进程就会掉登录，所以再加一层 remember-me：
                // 登录一次 30 天内重启也不用重新登，点「退出」才清掉
                .rememberMe(remember -> remember
                        .key("byte-love-notes-remember-me")
                        .tokenValiditySeconds(30 * 24 * 3600)
                        .alwaysRemember(true)
                )
                .logout(logout -> logout
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.sendRedirect(request.getContextPath() + "/notes"))
                );
        return http.build();
    }
}
