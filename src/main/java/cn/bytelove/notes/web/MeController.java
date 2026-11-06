package cn.bytelove.notes.web;

import cn.bytelove.notes.config.AppProperties;
import cn.bytelove.notes.domain.AppUser;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.AppUserRepository;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.ApiTokenService;
import cn.bytelove.notes.service.BookmarkService;
import cn.bytelove.notes.service.ContentStarService;
import cn.bytelove.notes.service.RatingService;
import cn.bytelove.notes.service.QuestService;
import cn.bytelove.notes.service.TrashService;
import cn.bytelove.notes.service.UserBadge;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MeController {

    private final AppUserRepository users;
    private final ContentItemRepository items;
    private final ContentStarService stars;
    private final ViewerService viewers;
    private final TrashService trash;
    private final BookmarkService bookmarks;
    private final RatingService ratings;
    private final QuestService quests;
    private final ApiTokenService tokens;
    private final AppProperties props;

    public MeController(AppUserRepository users, ContentItemRepository items,
                        ContentStarService stars, ViewerService viewers, TrashService trash,
                        BookmarkService bookmarks, RatingService ratings, QuestService quests,
                        ApiTokenService tokens, AppProperties props) {
        this.users = users;
        this.items = items;
        this.stars = stars;
        this.viewers = viewers;
        this.trash = trash;
        this.bookmarks = bookmarks;
        this.ratings = ratings;
        this.quests = quests;
        this.tokens = tokens;
        this.props = props;
    }

    @GetMapping("/me")
    public String me(Model model, Authentication auth, HttpServletRequest request) {
        Viewer viewer = viewers.current(auth);
        AppUser user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("displayName", user.getDisplayName());
        model.addAttribute("username", user.getUsername());
        model.addAttribute("joined", user.getCreatedAt());
        model.addAttribute("initial", UserBadge.initial(user.getDisplayName()));
        model.addAttribute("avatarHue", UserBadge.hue(user.getUsername()));

        model.addAttribute("noteCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.NOTE));
        model.addAttribute("baguCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.BAGU));
        model.addAttribute("algoCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.ALGO));
        model.addAttribute("projectCount", items.countByOwnerIdAndKind(viewer.userId(), ContentKind.PROJECT));
        model.addAttribute("memoCount", stars.count(viewer.userId()));
        model.addAttribute("trashCount", trash.list(viewer.userId()).size());
        model.addAttribute("bookmarkCount", bookmarks.count(viewer.userId()));
        model.addAttribute("ratingSummary", ratings.summary(viewer.userId()));
        model.addAttribute("questPoint", quests.point(viewer.userId()));
        model.addAttribute("questPassed", quests.passedCount(viewer.userId()));

        // AI 接入：只展示 token 的前几位，明文在生成那一刻由 flash 带过来一次
        tokens.describe(viewer.userId()).ifPresent(t -> {
            model.addAttribute("tokenHint", t[0]);
            model.addAttribute("tokenCreatedAt", t[1]);
        });
        // MCP 地址给绝对地址：用户要往别的客户端里贴，带域名的才能直接用。
        // 配了 notes.mcp-url 就用它（测试环境指到正式站，不会把 8180 复制出去）
        String mcpUrl = mcpUrl(request);
        model.addAttribute("mcpUrl", mcpUrl);
        model.addAttribute("apiBase", mcpUrl.replaceAll("/mcp$", "") + "/api/ai");
        // 刚生成 token 时，顺手把「复制给 AI 的整段话」也备好，用户不用自己拼
        Object fresh = model.getAttribute("newToken");
        if (fresh instanceof String token) {
            model.addAttribute("mcpPrompt", aiPrompt(mcpUrl, token));
        }

        model.addAttribute("tabActive", "me");
        return "me";
    }

    /** MCP 地址：配了 notes.mcp-url 用配置的，没配就按当前请求的 scheme://host:port/app 拼。 */
    private String mcpUrl(HttpServletRequest request) {
        String configured = props.mcpUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().toUriString() + "/mcp";
    }

    /**
     * 一段可以直接粘给 AI 的说明：地址、token、配法。
     * <p>
     * 这里<b>只放配置</b>，不放「存笔记该问什么」那些规则 —— 规则由服务端在 MCP 握手时下发
     * （`initialize.instructions`），用户重连一次就同步。以前这里抄了一份规则，结果改了服务端
     * 这份还停在旧说法（2026-11-04 发现的：这里还写着「先问权限、由我决定新建还是覆盖」，
     * 而服务端已经改成「先给默认值、点头就写」）。
     */
    private static String aiPrompt(String mcpUrl, String token) {
        String cmd = "claude mcp add --transport http byte-love-notes " + mcpUrl
                + " --header \"Authorization: Bearer " + token + "\"";
        String json = "{\"mcpServers\":{\"byte-love-notes\":{\"type\":\"http\",\"url\":\"" + mcpUrl
                + "\",\"headers\":{\"Authorization\":\"Bearer " + token + "\"}}}}";
        return String.join("\n",
                "我有个笔记站，帮我把它的 MCP 配到你这儿，配好之后我说「存成笔记」你就直接存进去。",
                "",
                "MCP 地址（HTTP）：" + mcpUrl,
                "认证头：Authorization: Bearer " + token,
                "",
                "Claude Code 用这条命令：",
                cmd,
                "",
                "Cursor / Claude Desktop 往配置文件里加：",
                json,
                "",
                "配好之后不用记别的规则：连上时服务端会自己下发这段说明（目录怎么选、权限和星级怎么定、"
                        + "什么时候要问我），你照它说的做就行。覆盖和删除任何内容之前，必须先问我。");
    }

    /** 生成（或重新生成）个人 token；旧的立即作废。 */
    @PostMapping("/me/token")
    public String issueToken(Authentication auth, RedirectAttributes redirect) {
        Viewer viewer = viewers.current(auth);
        redirect.addFlashAttribute("newToken", tokens.issue(viewer.userId()));
        return "redirect:/me";
    }

    @PostMapping("/me/token/delete")
    public String revokeToken(Authentication auth) {
        tokens.revoke(viewers.current(auth).userId());
        return "redirect:/me";
    }
}
