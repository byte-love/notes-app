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
                        // 推荐栏和更新记录对游客开放，写操作要登录
                        .requestMatchers(HttpMethod.POST, "/explore/copy/**").authenticated()
                                                .requestMatchers("/explore", "/explore/**", "/changelog", "/updates").permitAll()
                        .requestMatchers("/", "/notes", "/notes/**", "/projects", "/projects/**",
                                "/bagu", "/bagu/**", "/algo", "/algo/**",
                                "/login", "/register", "/api/**", "/css/**", "/media/**", "/favicon.ico",
                                "/error").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/star/**", "/memo").authenticated()
                        .anyRequest().authenticated()
                )
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
