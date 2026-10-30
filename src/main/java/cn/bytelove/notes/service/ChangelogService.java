package cn.bytelove.notes.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统更新日志。
 * 一次发布 = 日期 + 版本号 + 标题，卡片里再分若干小节，每节若干条要点。
 * 要点里用方括号包起来的部分会渲染成小标签，比如 [删除]、[回收站]。
 */
@Service
public class ChangelogService {

    private static final Pattern CHIP = Pattern.compile("\\[([^\\]]{1,20})]");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]{1,200})\\*\\*");

    private final JdbcTemplate jdbc;

    public ChangelogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(long id, String raw, String html) {
    }

    public record Section(String name, List<Item> items) {
    }

    public record Release(LocalDate releasedOn, String version, String title, List<Section> sections) {
        public int count() {
            int n = 0;
            for (Section s : sections) {
                n += s.items().size();
            }
            return n;
        }
    }

    /** 一天一卡片：同一天发几个版本都收在这张卡里（参考 Nuxt UI 的 changelog 层次） */
    public record Day(LocalDate releasedOn, List<Release> releases) {
    }

    public List<Day> days() {
        Map<LocalDate, List<Release>> byDay = new LinkedHashMap<>();
        for (Release r : releases()) {
            byDay.computeIfAbsent(r.releasedOn(), d -> new ArrayList<>()).add(r);
        }
        List<Day> out = new ArrayList<>(byDay.size());
        byDay.forEach((d, rs) -> out.add(new Day(d, rs)));
        return out;
    }

    public List<Release> releases() {
        Map<String, Map<String, List<Item>>> grouped = new LinkedHashMap<>();
        Map<String, Release> meta = new LinkedHashMap<>();

        jdbc.query("select id, version, released_on, title, section, content from changelog_entry "
                        + "order by released_on desc, version desc nulls last, title, sort_order, id",
                rs -> {
                    LocalDate day = rs.getDate("released_on").toLocalDate();
                    String version = rs.getString("version");
                    String title = rs.getString("title");
                    String key = day + "|" + version + "|" + title;
                    meta.putIfAbsent(key, new Release(day, version, title, null));

                    String section = rs.getString("section");
                    String name = section == null || section.isBlank() ? null : section;
                    String content = rs.getString("content");
                    grouped.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .computeIfAbsent(name == null ? "" : name, k -> new ArrayList<>())
                            .add(new Item(rs.getLong("id"), content, toHtml(content)));
                });

        List<Release> out = new ArrayList<>();
        for (Map.Entry<String, Release> e : meta.entrySet()) {
            Release r = e.getValue();
            List<Section> sections = new ArrayList<>();
            for (Map.Entry<String, List<Item>> s : grouped.get(e.getKey()).entrySet()) {
                sections.add(new Section(s.getKey().isEmpty() ? null : s.getKey(), s.getValue()));
            }
            out.add(new Release(r.releasedOn(), r.version(), r.title(), sections));
        }
        return out;
    }

    /**
     * 一次录入一整组。每行一条：
     *   ## 论文列表支持删除     ← 开一个小节
     *   - 每行操作列新增 [删除]  ← 一条要点
     * 没有 ## 的条目会落在没有小节名的分组里。
     */
    public int addRelease(LocalDate releasedOn, String version, String title, String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        String section = null;
        int order = 0;
        int n = 0;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("##")) {
                section = line.replaceFirst("^#+\\s*", "").trim();
                if (section.isEmpty()) {
                    section = null;
                }
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = line.substring(2).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            jdbc.update("insert into changelog_entry (version, released_on, title, section, content, sort_order) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    version, releasedOn, title, section, line, order++);
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

    /** 先转义，再把 [xxx] 包成小标签，避免录入的内容直接当 HTML 用。 */
    static String toHtml(String text) {
        String escaped = text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        String bolded = BOLD.matcher(escaped).replaceAll("<b>$1</b>");
        Matcher m = CHIP.matcher(bolded);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<span class=\"cl-chip\">[" + m.group(1) + "]</span>"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
