package cn.bytelove.notes.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 术语表。
 * — 按长度倒序匹配，保证「SXSSF」先于「SXSS」、「wx.setStorageSync」先于「wx」命中
 * — 纯 ASCII 术语大小写不敏感（session 与 Session 是同一个词），但输出保留正文原样
 * — 每个术语每篇只标一次，标在它真正第一次出现的位置
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** 命中结果：displayText 是正文里的原样（保留大小写），canonical 是术语表里的标准写法。 */
    public record Match(String displayText, String canonical) {
    }

    private final Map<String, String> definitions = new LinkedHashMap<>();
    private final List<String> termsByLengthDesc = new ArrayList<>();

    public GlossaryService(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("glossary.json").getInputStream()) {
            Map<String, String> loaded = mapper.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
            definitions.putAll(loaded);
            termsByLengthDesc.addAll(loaded.keySet());
            termsByLengthDesc.sort(Comparator.comparingInt(String::length).reversed());
            log.info("术语表已加载：{} 条", definitions.size());
        } catch (Exception e) {
            log.warn("术语表加载失败，浮窗将不可用：{}", e.getMessage());
        }
    }

    public int size() {
        return definitions.size();
    }

    public String definition(String term) {
        return definitions.get(term);
    }

    public Set<String> newUsedSet() {
        return new LinkedHashSet<>();
    }

    /** 从 index 开始尝试命中一个术语；used 里已有的不再重复标注。 */
    public Match matchAt(String text, int index, Set<String> used) {
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
            return new Match(text.substring(index, index + len), term);
        }
        return null;
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
