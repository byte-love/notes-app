package cn.bytelove.notes.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 把当前会话的 CSRF token 换一份发给页面。
 * 进程重启、会话过期、别处重新登录之后，页面里那份 token 已经作废，第一次提交会 403；
 * 前端在页面回到前台 / 定时 / 提交前调用这里把 token 换新，就不用用户白写一遍。
 * 返回的是给页面用的那一份（经过一次编码的），直接塞回表单 / meta 就能用。
 */
@RestController
public class CsrfController {

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> token(HttpServletRequest request) {
        Object attr = request.getAttribute(CsrfToken.class.getName());
        CsrfToken token = attr instanceof CsrfToken t ? t
                : (CsrfToken) request.getAttribute("_csrf");
        if (token == null) {
            return ResponseEntity.status(500).body(Map.of("error", "no-csrf"));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("header", token.getHeaderName(), "token", token.getToken()));
    }
}
