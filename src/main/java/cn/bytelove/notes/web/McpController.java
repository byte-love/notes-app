package cn.bytelove.notes.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程 MCP 服务：把同一套笔记操作以 JSON-RPC 暴露出去，
 * 这样不想在本机装 Node 的人只要在 AI 客户端里填一个地址 + token 就能用。
 * <p>
 * 协议按 MCP 的 Streamable HTTP 最小实现：一次 POST 一个 JSON-RPC 请求，直接回 JSON。
 * 鉴权复用 {@code Authorization: Bearer <个人 token>}，具体校验在 {@link AiController} 里。
 */
@RestController
public class McpController {

    private static final String PROTOCOL = "2025-06-18";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiController ai;

    public McpController(AiController ai) {
        this.ai = ai;
    }

    /**
     * 入口故意不用 {@code @RequestBody}：客户端可能发单条 JSON-RPC，也可能发批量数组（数组里装一条或多条），
     * 还可能不带 Content-Type、或者只声明 {@code Accept: text/event-stream}。走 MVC 的参数解析和内容协商时，
     * 这几种都会被拦成 400 / 406 / 415 的整页 HTML，AI 那边只看到「400 错误页面」，完全看不出原因
     * （2026-11-04 同事接 Claude Code 就是这么被挡住的）。所以这里自己读原始字节、自己解析，回什么都走 JSON。
     */
    @PostMapping("/mcp")
    public ResponseEntity<Object> rpc(@RequestHeader(name = "Authorization", required = false) String auth,
                                      HttpServletRequest request) throws IOException {
        byte[] raw;
        try (InputStream in = request.getInputStream()) {
            raw = in.readAllBytes();
        }
        Object parsed = null;
        if (raw.length > 0) {
            try {
                parsed = JSON.readValue(raw, Object.class);
            } catch (Exception e) {
                return json(error(null, -32700, "JSON 解析失败: " + e.getMessage()));
            }
        }
        if (parsed instanceof List<?> batch) {
            // 批量：逐条处理，通知类没有回复就跳过；一条回复都没有时按协议回 202
            List<Object> replies = new ArrayList<>();
            for (Object one : batch) {
                Map<String, Object> single = asMap(one);
                if (single == null) {
                    replies.add(error(null, -32600, "批量里混进了不是对象的消息"));
                    continue;
                }
                Map<String, Object> r = handle(single, auth, request);
                if (r != null) {
                    replies.add(r);
                }
            }
            return replies.isEmpty() ? ResponseEntity.accepted().build() : json(replies);
        }
        Map<String, Object> single = asMap(parsed);
        if (single == null) {
            return json(error(null, -32600, "收到的不是 JSON-RPC 请求"));
        }
        Map<String, Object> r = handle(single, auth, request);
        return r == null ? ResponseEntity.accepted().build() : json(r);
    }

    /** 一条 JSON-RPC 消息；通知类（没有 id 的 notifications/*）返回 null，调用方据此不回包。 */
    private Map<String, Object> handle(Map<String, Object> body, String auth, HttpServletRequest request) {
        Object id = body.get("id");
        String method = body.get("method") == null ? "" : String.valueOf(body.get("method"));
        if (method.isEmpty()) {
            return error(id, -32600, "缺少 method");
        }
        // 通知类消息没有 id，按协议回 202 即可，不用给 result
        if (id == null && method.startsWith("notifications/")) {
            return null;
        }
        try {
            Object result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", PROTOCOL,
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "byte-love-notes", "version", "1.0.0"));
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(auth, body, request);
                default -> throw new Rpc(-32601, "不支持的方法: " + method);
            };
            return reply(id, "result", result);
        } catch (Rpc e) {
            return error(id, e.code, e.getMessage());
        } catch (Exception e) {
            // 业务异常也按工具错误回给 AI，别让它看到一坨 500 页面
            return error(id, -32000, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static ResponseEntity<Object> json(Object body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return reply(id, "error", Map.of("code", code, "message", message == null ? "" : message));
    }

    /** id 可能为 null（客户端没给），Map.of 不收 null，所以手搭。 */
    private static Map<String, Object> reply(Object id, String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put(key, value);
        return out;
    }

    private Map<String, Object> call(String auth, Map<String, Object> body,
                                     HttpServletRequest request) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        String name = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        Object out = switch (name) {
            case "notes_folders" -> ai.folders(auth);
            case "notes_search" -> ai.search(auth, str(args, "q"));
            case "note_get" -> ai.get(auth, str(args, "slug"), request);
            case "note_create" -> ai.create(auth, new AiController.NoteIn(
                    str(args, "title"), str(args, "body"), str(args, "summary"), null,
                    str(args, "parent"), str(args, "visibility"), str(args, "kind"), null,
                    Boolean.TRUE.equals(args.get("folder"))), request);
            case "note_update" -> ai.update(auth, str(args, "slug"), new AiController.NoteIn(
                    str(args, "title"), str(args, "body"), str(args, "summary"), null,
                    str(args, "parent"), str(args, "visibility"), str(args, "kind"),
                    Boolean.TRUE.equals(args.get("confirm")), null), request);
            case "note_delete" -> ai.delete(auth, str(args, "slug"),
                    Boolean.TRUE.equals(args.get("confirm")));
            default -> throw new Rpc(-32602, "没有这个工具: " + name);
        };
        String text = out instanceof String s ? s : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("notes_folders", "列出我自己笔记的目录（slug / 标题 / 完整路径 / 类型）。新建笔记前用它挑 parent。",
                props(Map.of())));
        tools.add(tool("notes_search", "在我自己的笔记里按关键词搜（标题、摘要、正文都搜）。用来查重，只搜自己名下的。",
                props(Map.of("q", str("关键词，可留空表示全部")))));
        tools.add(tool("note_get", "读一篇我自己笔记的完整内容。",
                props(Map.of("slug", str("笔记的 slug")), "slug")));
        tools.add(tool("note_create", "新建一篇笔记（或者一个目录）。永远只会新建，不会覆盖任何已有内容；如果返回里 similar 不为空，"
                        + "说明站里有标题相近的旧笔记，要把这几篇告诉用户，问他是新建还是去改旧的。"
                        + "**visibility 必填**：先问用户这篇给什么权限 —— PUBLIC 任何用户可见（会进技术广场）；"
                        + "MEMBER 登录可见（只有用户把链接发给别人、对方注册登录后才能看，不进技术广场）；"
                        + "PRIVATE 仅自己。用户已经说过了就直接用那个值，不要再问一遍。"
                        + "**要开新分类（比如用户学的 Python，现有目录全是 Java）就传 folder=true**："
                        + "这时 title 就是分类名、body 可以留空；建好以后返回的 slug 拿去当下一次 note_create 的 parent。",
                props(Map.of(
                        "title", str("标题（folder=true 时是分类名）"),
                        "body", str("正文，Markdown；folder=true 时可以留空"),
                        "summary", str("摘要，可不填（会自动取正文开头）"),
                        "parent", str("父目录 slug，可用 notes_folders 查；不填就放在顶层"),
                        "visibility", str("必填。PUBLIC 任何用户可见 / MEMBER 登录可见（不进广场） / PRIVATE 仅自己。"
                                + "用户没说就先问，说了就别再问"),
                        "kind", str("内容类型：note（默认）/ bagu / algo / project"),
                        "folder", Map.of("type", "boolean",
                                "description", str("只当目录用（分类）：列表里不占卡片，可以在它下面继续新建。新建分类时传 true"))),
                        "title", "visibility")));
        tools.add(tool("note_update", "覆盖一篇已有笔记。**必须先把这个 slug 对应的标题念给用户、得到明确同意**，"
                        + "再把 confirm 传 true；服务端会先把旧版本存快照再覆盖。没带 confirm 会被拒绝。",
                props(Map.of(
                        "slug", str("要覆盖的笔记 slug"),
                        "title", str("新标题，不填就保留原样"),
                        "body", str("新正文（Markdown），不填就保留原样"),
                        "summary", str("新摘要"),
                        "parent", str("新的父目录 slug"),
                        "visibility", str("可见性：PUBLIC / MEMBER / PRIVATE"),
                        "confirm", Map.of("type", "boolean", "description", str("用户同意覆盖后传 true"))),
                        "slug", "confirm")));
        tools.add(tool("note_delete", "删除一篇笔记（软删，30 天内可在站点回收站恢复）。"
                        + "**必须先向用户复述要删的那篇标题并得到同意**，再传 confirm=true。",
                props(Map.of("slug", str("要删的笔记 slug"),
                        "confirm", Map.of("type", "boolean", "description", str("用户同意删除后传 true"))),
                        "slug", "confirm")));
        return tools;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private static Map<String, Object> props(Map<String, ?> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        return schema;
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** JSON-RPC 错误。 */
    private static final class Rpc extends RuntimeException {
        private final int code;

        Rpc(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
