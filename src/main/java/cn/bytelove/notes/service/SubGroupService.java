package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大分类的二次分组：条目多的小节（比如「项目与业务」三十条）在页面上太挤，
 * 按标题关键词再拆成几档，页面上默认收起，点开才展开。
 * <p>
 * 规则在 resources/subgroups.json，按分组 slug 配；顺序匹配，命中第一个词就归到那一档，
 * 都不命中归「其他」。改分类只要改 json，不用动数据库。
 */
@Service
public class SubGroupService {

    /** 一个分组超过这么多条才拆，太少了拆开反而碎。 */
    private static final int MIN_ITEMS = 13;
    private static final String OTHERS = "其他";

    private static final Logger log = LoggerFactory.getLogger(SubGroupService.class);

    public record Rule(String name, List<String> words) {
    }

    public record SubGroup(String name, List<ContentItem> items) {
    }

    private final Map<String, List<Rule>> rules = new LinkedHashMap<>();

    public SubGroupService() {
        ClassPathResource res = new ClassPathResource("subgroups.json");
        try (InputStream in = res.getInputStream()) {
            Map<String, List<Rule>> loaded = new ObjectMapper()
                    .readValue(in, new TypeReference<Map<String, List<Rule>>>() {
                    });
            loaded.forEach((slug, list) -> {
                List<Rule> clean = new ArrayList<>();
                for (Rule r : list) {
                    if (r.name() != null && r.words() != null && !r.words().isEmpty()) {
                        clean.add(new Rule(r.name(), r.words().stream()
                                .filter(w -> w != null && !w.isBlank())
                                .map(w -> w.trim().toLowerCase(Locale.ROOT))
                                .toList()));
                    }
                }
                rules.put(slug, clean);
            });
        } catch (Exception e) {
            // 规则文件坏了不影响列表，退化成不分组
            log.warn("subgroups.json 读取失败，列表不做二次分组", e);
        }
    }

    /**
     * 按分组 slug 把条目拆成几档；不满足条件返回空列表（页面就按原来的平铺渲染）。
     */
    public List<SubGroup> split(String slug, List<ContentItem> items) {
        if (slug == null || items == null || items.size() < MIN_ITEMS) {
            return List.of();
        }
        List<Rule> group = rules.get(slug);
        if (group == null || group.isEmpty()) {
            return List.of();
        }

        Map<String, List<ContentItem>> buckets = new LinkedHashMap<>();
        for (Rule r : group) {
            buckets.put(r.name(), new ArrayList<>());
        }
        List<ContentItem> others = new ArrayList<>();

        for (ContentItem item : items) {
            String haystack = ((item.getTitle() == null ? "" : item.getTitle()) + " "
                    + (item.getSummary() == null ? "" : item.getSummary())).toLowerCase(Locale.ROOT);
            String hit = null;
            for (Rule r : group) {
                for (String w : r.words()) {
                    if (haystack.contains(w)) {
                        hit = r.name();
                        break;
                    }
                }
                if (hit != null) {
                    break;
                }
            }
            if (hit == null) {
                others.add(item);
            } else {
                buckets.get(hit).add(item);
            }
        }

        List<SubGroup> out = new ArrayList<>();
        buckets.forEach((name, list) -> {
            if (!list.isEmpty()) {
                out.add(new SubGroup(name, list));
            }
        });
        if (!others.isEmpty()) {
            out.add(new SubGroup(OTHERS, others));
        }
        // 只分出一档就没意义，保持原样
        return out.size() < 2 ? List.of() : out;
    }
}
