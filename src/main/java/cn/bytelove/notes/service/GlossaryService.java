package cn.bytelove.notes.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 术语表（正文里那个浮动窗口）。
 * <ul>
 *   <li>存在 {@code glossary_term} 表里，第一次启动时用 {@code resources/glossary.json} 播种（表非空就不动）；
 *       放库里是因为要能在页面上直接改 / 删 / 新增，jar 里的 json 写不了</li>
 *   <li>一个术语可以有多个义项：{@code sense} 是语境关键词（逗号分隔），**只有关键词出现在这一段的正文里
 *       才命中那一条**。例：标签 在 Redis 段落里是 hash tag，在模型段落里才是 label；
 *       一个术语只有语境义项、又一条都没命中时宁可不标，也不把别的义项套上去</li>
 *   <li>按长度倒序匹配，保证「SXSSF」先于「SXSS」、「wx.setStorageSync」先于「wx」</li>
 *   <li>纯 ASCII 术语大小写不敏感（session 与 Session 是同一个词），但输出保留正文原样</li>
 *   <li>每个术语每篇只标一次，标在它真正第一次出现的位置</li>
 * </ul>
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** 命中结果：displayText 是正文里的原样，canonical 是术语的标准写法，entry 是查到的那一条解释。 */
    public record Match(String displayText, String canonical, Entry entry) {
    }

    /** 一条术语解释。sense 里是语境关键词（逗号分隔），空 = 通用义项。 */
    public record Entry(long id, String term, String sense, String tip) {

        boolean contextual() {
            return sense != null && !sense.isBlank();
        }

        List<String> keywords() {
            List<String> out = new ArrayList<>();
            if (!contextual()) {
                return out;
            }
            for (String raw : sense.split("[,，]")) {
                String k = raw.trim();
                if (!k.isEmpty()) {
                    out.add(k);
                }
            }
            return out;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    private volatile Map<String, List<Entry>> byTerm;
    private volatile List<String> termsByLengthDesc;
    /** 术语表每换一次就 +1，给正文渲染缓存当 key 用（否则改了术语浮窗还是旧的） */
    private volatile long version;

    public GlossaryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long version() {
        ensureLoaded();
        return version;
    }

    public int size() {
        ensureLoaded();
        return termsByLengthDesc.size();
    }

    public Set<String> newUsedSet() {
        return new LinkedHashSet<>();
    }

    /** 从 index 开始尝试命中一个术语；used 里已有的不再重复标注。context 是这一段正文，用来挑义项。 */
    public Match matchAt(String text, int index, Set<String> used, String context) {
        ensureLoaded();
        for (String term : termsByLengthDesc) {
            if (used.contains(term)) {
                continue;
            }
            int len = term.length();
            if (index + len > text.length()) {
                continue;
            }
            boolean ascii = isAscii(term);
            boolean hit = ascii
                    ? text.regionMatches(true, index, term, 0, len)
                    : text.startsWith(term, index);
            if (!hit) {
                continue;
            }
            if (ascii && !boundaryOk(text, index, len)) {
                continue;
            }
            Entry entry = pick(byTerm.get(key(term)), context);
            if (entry == null) {
                continue;
            }
            return new Match(text.substring(index, index + len), term, entry);
        }
        return null;
    }

    /** 语境关键词命中优先；没命中的语境义项一律不用，只回落到通用义项（没有就是 null）。 */
    private Entry pick(List<Entry> entries, String context) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        String ctx = context == null ? "" : context.toLowerCase(Locale.ROOT);
        Entry generic = null;
        for (Entry e : entries) {
            if (!e.contextual()) {
                if (generic == null) {
                    generic = e;
                }
                continue;
            }
            for (String keyword : e.keywords()) {
                if (ctx.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return e;
                }
            }
        }
        return generic;
    }

    // ---------- 编辑（页面上点术语直接改） ----------

    public long add(String term, String tip, String sense) {
        String t = term == null ? "" : term.trim();
        String d = tip == null ? "" : tip.trim();
        if (t.isEmpty() || d.isEmpty()) {
            return 0;
        }
        Long id = jdbc.queryForObject(
                "insert into glossary_term (term, sense, tip) values (?, ?, ?) "
                        + "on conflict (term, sense) do update set tip = excluded.tip, updated_at = now() "
                        + "returning id",
                Long.class, t, normalizeSense(sense), d);
        reload();
        return id == null ? 0 : id;
    }

    /** 改解释 / 改语境关键词；术语本身也能改（同一术语同时只留一条同 sense 的记录）。 */
    public boolean update(long id, String term, String tip, String sense) {
        String t = term == null ? "" : term.trim();
        String d = tip == null ? "" : tip.trim();
        if (t.isEmpty() || d.isEmpty()) {
            return false;
        }
        int n = jdbc.update("update glossary_term set term = ?, sense = ?, tip = ?, updated_at = now() where id = ?",
                t, normalizeSense(sense), d, id);
        if (n > 0) {
            reload();
        }
        return n > 0;
    }

    public boolean delete(long id) {
        int n = jdbc.update("delete from glossary_term where id = ?", id);
        if (n > 0) {
            reload();
        }
        return n > 0;
    }

    private static String normalizeSense(String sense) {
        if (sense == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String raw : sense.split("[,，]")) {
            String k = raw.trim();
            if (!k.isEmpty() && !parts.contains(k)) {
                parts.add(k);
            }
        }
        return String.join(",", parts);
    }

    // ---------- 载入 ----------

    private void ensureLoaded() {
        if (byTerm != null) {
            return;
        }
        synchronized (this) {
            if (byTerm != null) {
                return;
            }
            try {
                seedIfEmpty();
                reload();
            } catch (Exception e) {
                // 库不可用（迁移没跑、连接断了）时退回 jar 里的种子：浮窗还能用，只是改不了
                applyTo(readSeed());
                log.warn("术语表读库失败，改用 glossary.json 的种子：{}", e.getMessage());
            }
        }
    }

    public synchronized void reload() {
        List<Entry> all = jdbc.query("select id, term, sense, tip from glossary_term order by id", this::map);
        applyTo(all);
        log.info("术语表已加载：{} 条 / {} 个词", all.size(), termsByLengthDesc.size());
    }

    private void applyTo(List<Entry> all) {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        List<String> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : all) {
            grouped.computeIfAbsent(key(e.term()), k -> new ArrayList<>()).add(e);
            if (seen.add(key(e.term()))) {
                terms.add(e.term());
            }
        }
        terms.sort(Comparator.comparingInt(String::length).reversed());
        byTerm = grouped;
        termsByLengthDesc = terms;
        version++;
    }

    /** 表里一条都没有时，用 jar 里的 glossary.json 播种一次。 */
    private void seedIfEmpty() {
        Integer n = jdbc.queryForObject("select count(*) from glossary_term", Integer.class);
        if (n != null && n > 0) {
            return;
        }
        int imported = 0;
        for (Entry e : readSeed()) {
            imported += insertSeed(e.term(), e.sense(), e.tip());
        }
        log.info("术语表已从 glossary.json 导入 {} 条", imported);
    }

    /** 读 jar 里的 glossary.json：值是字符串就是通用义项，是数组就是同一术语的多个语境义项。 */
    private List<Entry> readSeed() {
        List<Entry> out = new ArrayList<>();
        try (InputStream in = new ClassPathResource("glossary.json").getInputStream()) {
            Map<String, JsonNode> seeded = mapper.readValue(in,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, JsonNode>>() {
                    });
            for (Map.Entry<String, JsonNode> e : seeded.entrySet()) {
                String term = e.getKey();
                JsonNode node = e.getValue();
                if (node.isTextual()) {
                    out.add(new Entry(0, term, "", node.asText()));
                } else if (node.isArray()) {
                    for (JsonNode sense : node) {
                        String when = "";
                        if (sense.has("when") && sense.get("when").isArray()) {
                            List<String> words = new ArrayList<>();
                            for (JsonNode w : sense.get("when")) {
                                words.add(w.asText());
                            }
                            when = String.join(",", words);
                        }
                        out.add(new Entry(0, term, normalizeSense(when), sense.path("tip").asText()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("术语表种子读取失败：{}", e.getMessage());
        }
        return out;
    }

    private int insertSeed(String term, String sense, String tip) {
        if (term == null || term.isBlank() || tip == null || tip.isBlank()) {
            return 0;
        }
        return jdbc.update("insert into glossary_term (term, sense, tip) values (?, ?, ?) "
                        + "on conflict (term, sense) do nothing",
                term.trim(), normalizeSense(sense), tip.trim());
    }

    private static String key(String term) {
        return term == null ? "" : term.toLowerCase(Locale.ROOT);
    }

    private Entry map(ResultSet rs, int index) throws SQLException {
        return new Entry(rs.getLong("id"), rs.getString("term"), rs.getString("sense"), rs.getString("tip"));
    }

    private boolean boundaryOk(String text, int start, int len) {
        if (start > 0 && isWordChar(text.charAt(start - 1))) {
            return false;
        }
        int end = start + len;
        return end >= text.length() || !isWordChar(text.charAt(end));
    }

    private boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
