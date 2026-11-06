package cn.bytelove.notes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 项目的结构化字段存在 project_meta.doc 里（jsonb）。
 * 字段是中英双语、action/result 还是数组，拆成列会变成二十几个，整体存 JSON 更省。
 */
@Service
public class ProjectMetaService {

    private final JdbcTemplate jdbc;
    private final ProjectTermAnnotator terms;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProjectMetaService(JdbcTemplate jdbc, ProjectTermAnnotator terms) {
        this.jdbc = jdbc;
        this.terms = terms;
    }

    public Optional<String> findDoc(Long itemId) {
        var list = jdbc.queryForList(
                "select doc::text from project_meta where item_id = ?", String.class, itemId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<ProjectView> view(Long itemId) {
        return findDoc(itemId).flatMap(ProjectView::parse).map(this::annotated);
    }

    /** 项目页的文字也挂术语浮窗：英文缩写、字段名这些东西，看的人不一定都认识。 */
    private ProjectView annotated(ProjectView v) {
        List<ProjectView.Item> actions = v.actions().stream()
                .map(a -> new ProjectView.Item(a.text(), terms.annotate(a.textHtml()),
                        a.note(), terms.annotate(a.noteHtml())))
                .toList();
        List<ProjectView.Result> results = v.results().stream()
                .map(r -> new ProjectView.Result(r.text(), terms.annotate(r.textHtml())))
                .toList();
        return new ProjectView(v.slug(), v.title(), v.subtitle(), v.role(), v.period(), v.stack(),
                v.problem(), terms.annotate(v.problemHtml()), actions, results,
                v.repo(), v.diagram(), v.shots());
    }

    public Map<Long, String> docs(Collection<Long> itemIds) {
        Map<Long, String> result = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", itemIds.stream().map(x -> "?").toList());
        jdbc.query("select item_id, doc::text as doc from project_meta where item_id in (" + placeholders + ")",
                rs -> {
                    long id = rs.getLong("item_id");
                    result.put(id, rs.getString("doc"));
                },
                itemIds.toArray());
        return result;
    }

    /** 存之前把 doc 里的 slug 强制对齐内容本身的 slug，避免链接和详情页对不上。 */
    public void save(Long itemId, String json, String slug) {
        jdbc.update("insert into project_meta (item_id, doc) values (?, ?::jsonb) "
                + "on conflict (item_id) do update set doc = excluded.doc", itemId, alignSlug(json, slug));
    }

    public void delete(Long itemId) {
        jdbc.update("delete from project_meta where item_id = ?", itemId);
    }

    /** 只校验能不能解析成 JSON；字段缺不缺由前端页面兜。 */
    public boolean isValid(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            mapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String alignSlug(String json, String slug) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            var node = mapper.readTree(json);
            if (node instanceof ObjectNode obj) {
                obj.put("slug", slug);
                return mapper.writeValueAsString(obj);
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }
}
