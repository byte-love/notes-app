package cn.bytelove.notes.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 给项目页的文字挂术语浮窗。
 * <p>
 * 项目页的内容不是 markdown，是 project_meta 里一行行的短句，走不了 {@link MarkdownRenderer}
 * 那套「渲染完再扫文本节点」的路子。所以这里在<b>已经转义好的 HTML</b> 上再过一遍：
 * 跳过标签和 {@code <code>} 里的内容，其余文字逐个位置试匹配术语，命中就包一层 span，
 * 和正文页用的是同一套 class 和同一个浮窗样式。
 */
@Service
public class ProjectTermAnnotator {

    private final GlossaryService glossary;

    public ProjectTermAnnotator(GlossaryService glossary) {
        this.glossary = glossary;
    }

    public String annotate(String html) {
        if (html == null || html.isEmpty() || glossary.size() == 0) {
            return html;
        }
        // 同一段里一个术语只标第一次，和正文页的行为一致
        Set<String> used = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder(html.length() + 64);
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                int end = html.indexOf('>', i);
                if (end < 0) {
                    out.append(html, i, html.length());
                    break;
                }
                String tag = html.substring(i, end + 1);
                out.append(tag);
                i = end + 1;
                // <code> 里是字段名 / 类名，标了反而干扰阅读
                if (tag.startsWith("<code")) {
                    int close = html.indexOf("</code>", i);
                    if (close < 0) {
                        out.append(html, i, html.length());
                        break;
                    }
                    out.append(html, i, close + 7);
                    i = close + 7;
                }
                continue;
            }
            GlossaryService.Match hit = glossary.matchAt(html, i, used, null);
            if (hit != null) {
                used.add(hit.canonical());
                out.append(span(hit));
                i += hit.displayText().length();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String span(GlossaryService.Match hit) {
        GlossaryService.Entry e = hit.entry();
        return "<span class=\"gl\" data-tip=\"" + attr(e.tip())
                + "\" data-term=\"" + attr(e.term())
                + "\" data-sense=\"" + attr(e.sense() == null ? "" : e.sense())
                + "\" data-tid=\"" + e.id() + "\">" + hit.displayText() + "</span>";
    }

    private static String attr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
