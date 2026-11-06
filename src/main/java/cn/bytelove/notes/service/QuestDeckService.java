package cn.bytelove.notes.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一条八股正文拆成一道可考的关卡：挖空版 + 要点骨架。
 * <p>
 * 规则都在这里，不落库——正文改一次，关卡跟着变，不用维护第二份数据。
 * <p>
 * 三条硬约束（都是拿真实题库试出来的）：
 * 1. 只从「焦点行」里挖（首段 / 列表项 / 带冒号的定义行），每行最多一个空。长答案整篇挖就是默写。
 * 2. 反引号里的东西整体算一个空，不能从代码中间抠一段出来。
 * 3. 正文里反复出现的词不挖：挖了上下文就把答案送出去了。
 * <p>
 * 挖不出足够的空、或者正文太长，就退回「模糊背」：整篇糊住自己擦，再自评。
 */
@Service
public class QuestDeckService {

    public static final String MODE_CLOZE = "cloze";
    public static final String MODE_RECALL = "recall";
    public static final String MODE_NONE = "none";

    /** 一关最多这么多个空。 */
    private static final int MAX_BLANKS = 5;
    /** 少于两个空就不值当挖，改走模糊背。 */
    private static final int MIN_BLANKS = 2;
    /** 同一个词在正文里出现两次以上就不挖：挖了一处，另一处会把答案送出去。 */
    private static final int REPEAT_LIMIT = 2;
    /** 正文超过这个长度不做挖空：滚都滚不完。 */
    private static final int MAX_CLOZE_TEXT = 4000;

    private static final Pattern FENCE = Pattern.compile("^\\s*```");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]{1,40})`");
    private static final Pattern NUM = Pattern.compile(
            "(?<![0-9A-Za-z.])(\\d+(?:\\.\\d+)?\\s?(?:年代|年|个月|月|天|秒|毫秒|分钟|小时|位|个|次|倍|层|级|"
                    + "KB|MB|GB|kb|mb|gb|bit|byte|Byte|字节|比特|%)?)(?![0-9A-Za-z])");
    private static final Pattern CAMEL = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Z][a-z]+[A-Z][A-Za-z0-9]*|[A-Z]{2,}[a-zA-Z0-9]*|[A-Za-z]+\\+\\+)(?![A-Za-z0-9_])");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.、)])\\s+(.*)$");
    private static final Pattern HEAD = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern QUOTE_HEAD = Pattern.compile("^>\\s*\\*\\*(.+?)\\*\\*\\s*$");
    /** 从飞书导入时带进来的章节标记：不是内容。 */
    private static final Pattern CHAPTER_MARK = Pattern.compile(
            "\\s*(?:-|\\*)?\\s*第[\\u4e00-\\u9fff\\d]{1,3}[章讲](?:[-－—]\\d+)?[^\\n]{0,30}[。.]?\\s*");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?:-|\\*)?\\s*第[\\u4e00-\\u9fff\\d]{1,3}[章讲][^\\n]{0,20}[。.]?");

    /** 一个空：answer 是标准答案，index 是这一关里的序号（提交时按它对应）。 */
    public record Blank(int index, String answer) {
    }

    /** 挖空正文切成片段：text 原样，blank 非空就是要填的空。 */
    public record Seg(String text, Blank blank) {
    }

    public record Line(List<Seg> segs) {
    }

    /**
     * mode: cloze / recall / none。none 表示这题正文只有「待补」这类标记，做不成关卡。
     * lines 只给 cloze；points 是模糊背自评时对照用的要点；text 是去掉代码和章节标记后的正文。
     */
    public record Deck(String mode, String text, List<Line> lines, List<String> answers,
                       List<String> points, int codePercent) {

        public boolean playable() {
            return !MODE_NONE.equals(mode);
        }
    }

    public Deck build(String body) {
        String raw = body == null ? "" : body;
        Stripped stripped = strip(raw);
        String text = stripped.text;
        List<String> points = points(text);

        if (isPlaceholder(text)) {
            return new Deck(MODE_NONE, text, List.of(), List.of(), points, stripped.percent());
        }
        List<Pick> picks = text.length() <= MAX_CLOZE_TEXT ? candidates(text) : List.of();
        if (picks.size() < MIN_BLANKS) {
            return new Deck(MODE_RECALL, text, List.of(), List.of(), points, stripped.percent());
        }
        List<String> answers = new ArrayList<>(picks.size());
        List<Pick> indexed = new ArrayList<>(picks.size());
        for (int i = 0; i < picks.size(); i++) {
            answers.add(picks.get(i).value);
            indexed.add(new Pick(picks.get(i).priority, picks.get(i).value, picks.get(i).start, i));
        }
        return new Deck(MODE_CLOZE, text, lines(text, indexed), answers, points, stripped.percent());
    }

    /** 提交时判分：忽略大小写、空格、中英文标点的全半角差异。 */
    public boolean matches(String expected, String got) {
        return normalize(expected).equals(normalize(got));
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip().toLowerCase(Locale.ROOT)
                .replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('。', '.').replace('、', ',')
                .replace('：', ':').replace('；', ';')
                .replaceAll("\\s+", "");
        return t.replaceAll("^[.,;:()\\[\\]{}\"'`]+", "").replaceAll("[.,;:()\\[\\]{}\"'`]+$", "");
    }

    // ---------------------------------------------------------------- 正文预处理

    private record Stripped(String text, int percent) {
    }

    private Stripped strip(String body) {
        int total = Math.max(1, body.length());
        int codeChars = 0;
        boolean inCode = false;
        List<String> kept = new ArrayList<>();
        for (String line : body.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                codeChars += line.length();
            } else {
                kept.add(IMAGE.matcher(line).replaceAll(""));
            }
        }
        List<String> out = new ArrayList<>(kept.size());
        for (String line : kept) {
            if (!CHAPTER_MARK.matcher(line).matches()) {
                out.add(line);
            }
        }
        return new Stripped(String.join("\n", out), Math.round(100f * codeChars / total));
    }

    /** 正文只有「待补」「第七章」这种标记，等于这题还没写答案。 */
    boolean isPlaceholder(String text) {
        String s = text.strip();
        if (s.isEmpty()) {
            return true;
        }
        if (s.equals("待补") || s.equals("暂无") || s.equals("无")) {
            return true;
        }
        return PLACEHOLDER.matcher(s).matches();
    }

    // ---------------------------------------------------------------- 要点骨架

    public List<String> points(String text) {
        List<String> pts = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String s = line.strip();
            if (s.isEmpty()) {
                continue;
            }
            Matcher head = HEAD.matcher(s);
            Matcher quote = QUOTE_HEAD.matcher(s);
            if (head.matches() || quote.matches()) {
                String t = (head.matches() ? head.group(1) : quote.group(1)).strip();
                if (t.length() >= 2 && t.length() <= 24) {
                    pts.add(t);
                }
                continue;
            }
            Matcher item = LIST_ITEM.matcher(s);
            if (item.matches()) {
                String t = item.group(1).strip();
                if (t.length() > 30) {
                    t = splitFirst(t, "：:，。");
                }
                if (t.length() >= 2 && t.length() <= 30) {
                    pts.add(t);
                }
            }
        }
        // 小标题 / 列表项不够两条时补段落首句：很多题就是一个大段落，没分点
        if (new LinkedHashSet<>(pts).size() < 2) {
            for (String line : text.split("\\R", -1)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith(">") || pts.contains(s)) {
                    continue;
                }
                String first = firstSentence(s);
                if (first.length() >= 4) {
                    pts.add(first);
                }
                if (pts.size() >= 5) {
                    break;
                }
            }
        }
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String p : pts) {
            if (seen.add(p.replace(" ", ""))) {
                uniq.add(p);
            }
        }
        return uniq.stream().limit(6).toList();
    }

    private static String splitFirst(String s, String seps) {
        String best = s;
        for (char c : seps.toCharArray()) {
            int i = best.indexOf(c);
            if (i > 0) {
                best = best.substring(0, i);
            }
        }
        return best.strip();
    }

    private static String firstSentence(String s) {
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '；') {
                cut = i + 1;
                break;
            }
        }
        String first = (cut > 0 ? s.substring(0, cut) : s).strip();
        return first.length() > 36 ? first.substring(0, 36) + "…" : first;
    }

    // ---------------------------------------------------------------- 挖空

    private record Pick(int priority, String value, int start, int index) {
    }

    private record Cand(int priority, String value, int start) {
    }

    /** 一行里最值得挖的那一个词：行内代码 > 数字 > 技术名词。 */
    private List<Cand> lineCandidates(String line) {
        List<Cand> found = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        Matcher code = INLINE_CODE.matcher(line);
        while (code.find()) {
            spans.add(new int[]{code.start(), code.end()});
            String v = code.group(1).strip();
            if (v.length() >= 2 && v.length() <= 40) {
                found.add(new Cand(0, v, code.start(1)));
            }
        }
        // 反引号没配对（代码长过 40 字识别不出来），整行不挖，免得留下半截代码
        int ticks = (int) line.chars().filter(c -> c == '`').count();
        if (ticks % 2 != 0 || ticks > 2 * spans.size()) {
            return found;
        }
        Matcher num = NUM.matcher(line);
        while (num.find()) {
            String v = num.group(1).strip();
            if (v.length() >= 2 && !inside(spans, num.start())) {
                found.add(new Cand(1, v, num.start()));
            }
        }
        Matcher camel = CAMEL.matcher(line);
        while (camel.find()) {
            String v = camel.group(1).strip();
            boolean techy = !v.isEmpty()
                    && (Character.isUpperCase(v.charAt(0)) || v.equals(v.toUpperCase(Locale.ROOT)));
            if (v.length() >= 3 && v.length() <= 20 && techy && !inside(spans, camel.start())) {
                found.add(new Cand(2, v, camel.start()));
            }
        }
        found.sort((a, b) -> a.priority != b.priority
                ? Integer.compare(a.priority, b.priority) : Integer.compare(a.start, b.start));
        return found;
    }

    private static boolean inside(List<int[]> spans, int pos) {
        for (int[] s : spans) {
            if (s[0] <= pos && pos < s[1]) {
                return true;
            }
        }
        return false;
    }

    private record Row(String line, int base) {
    }

    private List<Pick> candidates(String text) {
        List<Row> rows = new ArrayList<>();
        int offset = 0;
        for (String raw : text.split("(?<=\\n)", -1)) {
            String s = raw.strip();
            if (!s.isEmpty() && !s.startsWith(">")) {
                rows.add(new Row(s, offset + raw.indexOf(s)));
            }
            offset += raw.length();
        }
        Set<String> used = new HashSet<>();
        List<Pick> picks = new ArrayList<>();
        // 第一轮只从焦点行取；不够三个空再放宽到短行，避免长答案被挖成默写
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < rows.size(); i++) {
                if (picks.size() >= MAX_BLANKS || (pass == 0 && picks.size() >= 3)) {
                    break;
                }
                Row row = rows.get(i);
                boolean focus = i == 0 || LIST_ITEM.matcher(row.line).matches()
                        || (row.line.contains("：") && row.line.length() < 220);
                if (pass == 0 && !focus) {
                    continue;
                }
                if (pass == 1 && (focus || row.line.length() > 150)) {
                    continue;
                }
                for (Cand cand : lineCandidates(row.line)) {
                    String key = cand.value.toLowerCase(Locale.ROOT);
                    if (used.contains(key) || countOf(text, cand.value) >= REPEAT_LIMIT) {
                        continue;
                    }
                    used.add(key);
                    picks.add(new Pick(cand.priority, cand.value, row.base + cand.start, picks.size()));
                    break;
                }
            }
        }
        return picks;
    }

    private static int countOf(String text, String word) {
        if (word.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = text.indexOf(word);
        while (i >= 0) {
            n++;
            i = text.indexOf(word, i + word.length());
        }
        return n;
    }

    /** 按行切出片段：空位置换成 Blank，其余是原文。 */
    private List<Line> lines(String text, List<Pick> picks) {
        List<Line> out = new ArrayList<>();
        int offset = 0;
        for (String raw : text.split("(?<=\\n)", -1)) {
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                offset += raw.length();
                continue;
            }
            int base = offset + raw.indexOf(trimmed);
            int end = base + trimmed.length();
            List<Pick> here = new ArrayList<>();
            for (Pick p : picks) {
                if (p.start >= base && p.start + p.value.length() <= end) {
                    here.add(p);
                }
            }
            here.sort((a, b) -> Integer.compare(a.start, b.start));
            List<Seg> segs = new ArrayList<>();
            int cursor = base;
            for (Pick p : here) {
                segs.add(new Seg(text.substring(cursor, p.start), null));
                segs.add(new Seg(p.value, new Blank(p.index, p.value)));
                cursor = p.start + p.value.length();
            }
            segs.add(new Seg(text.substring(cursor, end), null));
            out.add(new Line(segs));
            offset += raw.length();
        }
        return out;
    }
}
