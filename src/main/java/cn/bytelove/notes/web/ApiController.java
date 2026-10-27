package cn.bytelove.notes.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供静态作品集页面读取登录态。同源请求，浏览器自动带 session cookie。
 * 同时下发 CSRF token，静态页面才能安全地发起登出请求。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        boolean owner = authenticated && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_OWNER"::equals);

        body.put("authenticated", authenticated);
        body.put("owner", owner);
        body.put("username", authenticated ? auth.getName() : null);
        String ctx = request.getContextPath();
        body.put("loginUrl", ctx + "/login");
        body.put("logoutUrl", ctx + "/logout");
        body.put("adminUrl", ctx + "/admin");
        body.put("notesUrl", ctx + "/notes");

        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf != null) {
            body.put("csrfHeader", csrf.getHeaderName());
            body.put("csrfToken", csrf.getToken());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
