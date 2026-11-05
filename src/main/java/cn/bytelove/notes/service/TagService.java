package cn.bytelove.notes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 标签：把「这篇在讲什么」压成几个词，落库后可以在列表里按它筛。
 * <p>
 * 词表是固定的（resources/tags.json），不接大模型：命中一次标题算 3 分、正文算 1 分
 * （同一个词在正文里最多算 3 次），总分够 3 分才挂，最多挂 5 个。
 * 英文缩写要卡左边界，否则 ES 会命中 false、AI 会命中 wait。
 */
@Service
public class TagService {

    private static final int MAX_TAGS = 5;
    private static final int MIN_SCORE = 3;
    /** 正文只扫前这么多字：长文的标签一样看开头，扫全文纯属浪费。 */
    private static final int BODY_LIMIT = 4000;
    private static final Pattern ASCII = Pattern.compile("[A-Za-z0-9+#.]{1,12}");

    private static final Logger log = LoggerFactory.getLogger(TagService.class);

    private final JdbcTemplate jdbc;
    /** 标签 -> 关键词；顺序按 json 里写的来，打分相同时靠它稳住先后。 */
    private final Map<String, List<String>> rules = new LinkedHashMap<>();

    public TagService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ClassPathResource res = new ClassPathResource("tags.json");
        try (InputStream in = res.getInputStream()) {
            Map<String, List<String>> loaded = new ObjectMapper()
                    .readValue(in, new TypeReference<Map<String, List<String>>>() {
                    });
            loaded.forEach((tag, words) -> {
                if (tag == null || tag.isBlank() || words == null || words.isEmpty()) {
                    return;
                }
                List<String> clean = new ArrayList<>();
                for (String w : words) {
                    if (w != null && !w.isBlank()) {
                        clean.add(w.trim());
                    }
                }
                if (!clean.isEmpty()) {
                    rules.put(tag.trim(), clean);
                }
            });
        } catch (Exception e) {
            // 词表坏了不影响正文，退化成不打标签
            log.warn("tags.json 读取失败，这一版不打标签", e);
        }
    }

    /** 按词表算一条内容该挂哪些标签。 */
    public List<String> compute(String title, String body) {
        if (rules.isEmpty()) {
            return List.of();
        }
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (b.length() > BODY_LIMIT) {
            b = b.substring(0, BODY_LIMIT);
        }
        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        for (Map.Entry<String, List<String>> rule : rules.entrySet()) {
            int score = 0;
            for (String word : rule.getValue()) {
                if (hits(t, word) > 0) {
                    score += 3;
                }
                score += Math.min(hits(b, word), 3);
            }
            if (score >= MIN_SCORE) {
                scored.add(Map.entry(rule.getKey(), score));
            }
        }
        scored.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : scored) {
            if (out.size() >= MAX_TAGS) {
                break;
            }
            out.add(e.getKey());
        }
        return out;
    }

    /** 重算一条内容的标签：先清空再写，词表改了以后跑一遍就能整体对齐。 */
    public void retag(Long itemId, String title, String body) {
        List<String> tags = compute(title, body);
        jdbc.update("delete from content_tag where item_id = ?", itemId);
        for (String tag : tags) {
            jdbc.update("insert into content_tag (item_id, tag, score) values (?, ?, ?)"
                            + " on conflict (item_id, tag) do nothing",
                    itemId, tag, 0);
        }
    }

    /** 复制内容（加入我的空间）时把标签一起带过去。 */
    public void copy(Long fromItemId, Long toItemId) {
        jdbc.update("insert into content_tag (item_id, tag, score)"
                + " select ?, tag, score from content_tag where item_id = ?"
                + " on conflict (item_id, tag) do nothing", toItemId, fromItemId);
    }

    /** 列表页两处都要这份数据（卡片上的标签 + 筛选条上的条数），只查一次库 */
    public record TagsAndCounts(Map<Long, List<String>> byItem, Map<String, Integer> counts) {
    }

    /**
     * 一批内容的标签 + 每个标签的条数。以前 {@code of} 和 {@code counts} 各扫一遍
     * {@code content_tag}，列表页一次请求就是两次同样的扫描，现在合并成一次。
     */
    public TagsAndCounts load(Collection<Long> itemIds) {
        Map<Long, List<String>> byItem = new HashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return new TagsAndCounts(byItem, new LinkedHashMap<>());
        }
        Map<String, Integer> tally = new HashMap<>();
        jdbc.query("select item_id, tag from content_tag where item_id in (" + placeholders(itemIds)
                        + ") order by item_id, score desc, tag",
                rs -> {
                    long itemId = rs.getLong("item_id");
                    String tag = rs.getString("tag");
                    byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(tag);
                    tally.merge(tag, 1, Integer::sum);
                }, itemIds.toArray());
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(tally.entrySet());
        rows.sort((x, y) -> y.getValue() - x.getValue());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : rows) {
            counts.put(e.getKey(), e.getValue());
        }
        return new TagsAndCounts(byItem, counts);
    }

    /** 一批内容的标签，按打分顺序；列表页一次取完，不要每张卡片查一次库。 */
    public Map<Long, List<String>> of(Collection<Long> itemIds) {
        return load(itemIds).byItem();
    }

    /** 这批内容里每个标签有多少条，按条数倒序：列表页那排筛选标签用。 */
    public Map<String, Integer> counts(Collection<Long> itemIds) {
        return load(itemIds).counts();
    }

    /** 挂了某个标签的条目 id。 */
    public Set<Long> itemIdsWithTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList(
                "select item_id from content_tag where tag = ?", Long.class, tag));
    }

    private static String placeholders(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    /** 一个词在这段文字里出现几次；英文 / 缩写按左边界卡一下。 */
    private static int hits(String text, String word) {
        if (text.isEmpty() || word == null || word.isEmpty()) {
            return 0;
        }
        String w = word.toLowerCase(Locale.ROOT);
        if (ASCII.matcher(word).matches()) {
            Pattern p = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(w));
            var m = p.matcher(text);
            int n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        }
        int n = 0;
        int i = text.indexOf(w);
        while (i >= 0) {
            n++;
            i = text.indexOf(w, i + w.length());
        }
        return n;
    }
}
