package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统更新日志。分类沿用 Keep a Changelog 的四个词：新增 / 优化 / 修复 / 移除。
 */
@Service
public class ChangelogService {

    private final JdbcTemplate jdbc;

    public ChangelogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(long id, String category, String label, String content) {
    }

    public record Release(LocalDate releasedOn, String version, String title, List<Entry> entries) {
    }

    public static final String[][] CATEGORIES = {
            {"ADDED", "新增"},
            {"CHANGED", "优化"},
            {"FIXED", "修复"},
            {"REMOVED", "移除"}
    };

    private static String labelOf(String category) {
        for (String[] c : CATEGORIES) {
            if (c[0].equals(category)) {
                return c[1];
            }
        }
        return category;
    }

    /** 按发布时间倒序，一组里按分类再按录入顺序。 */
    public List<Release> releases() {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        Map<String, Release> meta = new LinkedHashMap<>();

        jdbc.query("select id, version, released_on, title, category, content from changelog_entry "
                        + "order by released_on desc, title, sort_order, id",
                rs -> {
                    LocalDate day = rs.getDate("released_on").toLocalDate();
                    String version = rs.getString("version");
                    String title = rs.getString("title");
                    String key = day + "|" + version + "|" + title;
                    meta.putIfAbsent(key, new Release(day, version, title, null));
                    String category = rs.getString("category");
                    grouped.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Entry(rs.getLong("id"), category, labelOf(category),
                                    rs.getString("content")));
                });

        List<Release> out = new ArrayList<>();
        for (Map.Entry<String, Release> e : meta.entrySet()) {
            Release r = e.getValue();
            out.add(new Release(r.releasedOn(), r.version(), r.title(), grouped.get(e.getKey())));
        }
        return out;
    }

    /**
     * 一次录入一整组。每行一条，格式：
     *   [新增] 支持注册
     *   [修复] 备忘录跳转 404
     * 没有前缀的行按「优化」算。
     */
    public int addRelease(LocalDate releasedOn, String version, String title, String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        int order = 0;
        int n = 0;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String category = "CHANGED";
            for (String[] c : CATEGORIES) {
                String prefix = "[" + c[1] + "]";
                if (line.startsWith(prefix)) {
                    category = c[0];
                    line = line.substring(prefix.length()).trim();
                    break;
                }
            }
            if (line.isEmpty()) {
                continue;
            }
            jdbc.update("insert into changelog_entry (version, released_on, title, category, content, sort_order) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    version, releasedOn, title, category, line, order++);
            n++;
        }
        return n;
    }

    public void deleteRelease(LocalDate releasedOn, String title) {
        jdbc.update("delete from changelog_entry where released_on = ? and title = ?", releasedOn, title);
    }

    public void deleteEntry(long id) {
        jdbc.update("delete from changelog_entry where id = ?", id);
    }
}
