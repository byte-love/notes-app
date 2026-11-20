package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技术广场的只读查询：只碰 PUBLIC 且进了推荐栏的条目，按标题 / 摘要 / 正文搜。
 * <p>
 * AI 只能看到广场上本来人人可见的东西，不会因为多了一个工具就摸到别人的私有笔记。
 */
@Service
public class ExploreSearchService {

    /** 一次最多回几条：够 AI 组织回答，又不至于把整页内容塞进上下文。 */
    private static final int LIMIT = 10;
    private static final int SNIPPET = 160;

    private final JdbcTemplate jdbc;

    public ExploreSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String q) {
        String needle = q == null ? "" : q.trim();
        StringBuilder sql = new StringBuilder(
                "select slug, title, summary, kind, body from content_item"
                        + " where visibility = 'PUBLIC' and recommended = true and deleted_at is null");
        List<Object> params = new ArrayList<>();
        if (!needle.isEmpty()) {
            sql.append(" and (title ilike ? or summary ilike ? or body ilike ?)");
            String like = "%" + needle + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        sql.append(" order by updated_at desc limit ").append(LIMIT);

        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String summary = rs.getString("summary");
            if (summary == null || summary.isBlank()) {
                summary = snippet(rs.getString("body"));
            }
            row.put("title", rs.getString("title"));
            row.put("summary", summary);
            row.put("kind", rs.getString("kind").toLowerCase(Locale.ROOT));
            row.put("url", "/app" + basePath(rs.getString("kind")) + "/" + rs.getString("slug"));
            return row;
        }, params.toArray());
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("[#>*`\\[\\]()!_|-]+", " ").replaceAll("\\s+", " ").trim();
        return text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
    }

    private static String basePath(String kind) {
        return switch (kind) {
            case "BAGU" -> "/bagu";
            case "INTERVIEW" -> "/interview";
            case "ALGO" -> "/algo";
            case "PROJECT" -> "/projects";
            default -> "/notes";
        };
    }
}
