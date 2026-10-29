package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ProjectMetaService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供静态作品集页面读取登录态和项目数据。同源请求，浏览器自动带 session cookie。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ContentItemRepository items;
    private final ViewerService viewers;
    private final VisibilityService visibility;
    private final ProjectMetaService meta;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiController(ContentItemRepository items, ViewerService viewers,
                         VisibilityService visibility, ProjectMetaService meta) {
        this.items = items;
        this.viewers = viewers;
        this.visibility = visibility;
        this.meta = meta;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        boolean owner = authenticated && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);

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

    /** 作品集首页的项目卡片从这里取数据，按「同级排序」排。 */
    @GetMapping("/projects")
    public ResponseEntity<List<JsonNode>> projects(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        List<ContentItem> pool = new ArrayList<>(
                items.findByKindAndRecommendedTrueOrderBySortOrderAscIdAsc(ContentKind.PROJECT));
        if (viewer.userId() != null) {
            for (ContentItem mine : items.findByOwnerIdAndKindOrderBySortOrderAsc(
                    viewer.userId(), ContentKind.PROJECT)) {
                boolean dup = pool.stream().anyMatch(p -> p.getId().equals(mine.getId()));
                if (!dup) {
                    pool.add(mine);
                }
            }
        }

        List<ContentItem> readable = new ArrayList<>();
        for (ContentItem item : pool) {
            if (visibility.canRead(item, viewer)) {
                readable.add(item);
            }
        }

        Map<Long, String> docs = meta.docs(readable.stream().map(ContentItem::getId).toList());
        List<JsonNode> out = new ArrayList<>();
        for (ContentItem item : readable) {
            String doc = docs.get(item.getId());
            if (doc == null || doc.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(doc);
                if (node instanceof ObjectNode obj) {
                    obj.put("slug", item.getSlug());
                }
                out.add(node);
            } catch (Exception ignored) {
                // 单条 JSON 坏了不该让整个列表挂掉
            }
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(out);
    }
}
