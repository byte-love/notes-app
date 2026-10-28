package cn.bytelove.notes.config;

import cn.bytelove.notes.service.LoginAttemptService;
import org.springframework.context.annotation.Bean;
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
                        .requestMatchers("/", "/notes", "/notes/**", "/projects", "/projects/**",
                                "/bagu", "/bagu/**", "/algo", "/algo/**",
                                "/login", "/api/**", "/css/**", "/media/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
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
                .logout(logout -> logout
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.sendRedirect(request.getContextPath() + "/notes"))
                );
        return http.build();
    }
}
