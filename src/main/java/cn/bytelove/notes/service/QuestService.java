package cn.bytelove.notes.service;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识考核：章节进度、判分、积分。
 * <p>
 * 关卡本身不落库，全部由正文现算（见 {@link QuestDeckService}）；这里只记「谁过了哪一题」。
 */
@Service
public class QuestService {

    /** 答对这么多就算过。挖空有个别空写错很正常，不要求全对。 */
    private static final double PASS_RATE = 0.6;
    /** 自评四档折算成的基础分：看过答案还敢说「核心都对」才给满分。 */
    private static final double SELF_ALL = 1.0;
    private static final double SELF_MOST = 0.8;
    private static final double SELF_FEW = 0.4;
    private static final double SELF_NONE = 0.1;

    private final JdbcTemplate jdbc;
    private final QuestDeckService decks;

    public QuestService(JdbcTemplate jdbc, QuestDeckService decks) {
        this.jdbc = jdbc;
        this.decks = decks;
    }

    // ---------------------------------------------------------------- 章节与题目

    /** 一道关卡：题目 + 判定方式 + 是否已过。 */
    public record Item(long id, String slug, String title, int rating, String mode, boolean passed) {
    }

    /** 一章：分类名 + 下面的关卡 + 进度。 */
    public record Chapter(String slug, String title, List<Item> items, int total, int passed) {
    }

    public record Progress(boolean passed, int clears, double bestRate) {
    }

    private record Row(long id, String slug, String title, int rating, Long parentId, boolean folder) {
    }

    /** 一条能考的关卡（附判定方式）。 */
    private record Playable(Row row, String mode) {
    }

    /**
     * 所有能考的关卡，按分类分组。
     * <p>
     * 目录节点、没写答案的题（正文只有「待补」「第七章」）都不进来——它们上不了线。
     * 整棵 BAGU 树都要读进来（不只是能考的那些），否则顺着 parent_id 往上找不到分类头。
     */
    public List<Chapter> chapters(Long userId) {
        if (userId == null) {
            return List.of();
        }
        Map<Long, Progress> progress = progress(userId);
        List<Row> all = new ArrayList<>();
        Map<Long, String> modes = new HashMap<>();
        jdbc.query("select id, slug, title, rating, parent_id, is_folder, body from content_item"
                        + " where owner_id = ? and kind = 'BAGU' and deleted_at is null"
                        + " order by sort_order, id",
                rs -> {
                    long id = rs.getLong("id");
                    long parentRaw = rs.getLong("parent_id");
                    Long parent = rs.wasNull() ? null : parentRaw;
                    all.add(new Row(id, rs.getString("slug"), rs.getString("title"),
                            rs.getInt("rating"), parent, rs.getBoolean("is_folder")));
                    modes.put(id, decks.build(rs.getString("body")).mode());
                }, userId);

        // 有子条目的节点是目录，不做关卡
        java.util.Set<Long> withChildren = new java.util.HashSet<>();
        for (Row r : all) {
            if (r.parentId != null) {
                withChildren.add(r.parentId);
            }
        }

        Map<Long, Row> byId = new LinkedHashMap<>();
        for (Row r : all) {
            byId.put(r.id, r);
        }

        Map<Long, List<Playable>> byChapter = new LinkedHashMap<>();
        List<Playable> orphans = new ArrayList<>();
        for (Row r : all) {
            if (r.folder || withChildren.contains(r.id)) {
                continue;
            }
            String mode = modes.get(r.id);
            if (QuestDeckService.MODE_NONE.equals(mode)) {
                continue;
            }
            Row top = topOf(byId, r);
            if (top == null) {
                orphans.add(new Playable(r, mode));
            } else {
                byChapter.computeIfAbsent(top.id, k -> new ArrayList<>()).add(new Playable(r, mode));
            }
        }

        List<Chapter> out = new ArrayList<>();
        for (Map.Entry<Long, List<Playable>> e : byChapter.entrySet()) {
            Row head = byId.get(e.getKey());
            out.add(chapter(head.slug, head.title, e.getValue(), progress));
        }
        if (!orphans.isEmpty()) {
            out.add(chapter("", "未分类", orphans, progress));
        }
        return out;
    }

    private Chapter chapter(String slug, String title, List<Playable> items, Map<Long, Progress> progress) {
        List<Item> list = new ArrayList<>(items.size());
        int passed = 0;
        for (Playable p : items) {
            boolean ok = progress.containsKey(p.row.id) && progress.get(p.row.id).passed();
            if (ok) {
                passed++;
            }
            list.add(new Item(p.row.id, p.row.slug, p.row.title, p.row.rating, p.mode, ok));
        }
        return new Chapter(slug, title, list, list.size(), passed);
    }

    /** 往上找到没有父节点的那个祖先；父节点不在能考集合里就返回它自己。 */
    private Row topOf(Map<Long, Row> byId, Row row) {
        Row cursor = row;
        for (int guard = 0; guard < 32; guard++) {
            if (cursor.parentId == null) {
                return cursor;
            }
            Row parent = byId.get(cursor.parentId);
            if (parent == null || parent.id == cursor.id) {
                return cursor;
            }
            cursor = parent;
        }
        return cursor;
    }

    // ---------------------------------------------------------------- 进度与积分

    public Map<Long, Progress> progress(Long userId) {
        Map<Long, Progress> out = new HashMap<>();
        if (userId == null) {
            return out;
        }
        jdbc.query("select item_id, passed, clears, best_rate from quest_progress where user_id = ?",
                rs -> {
                    out.put(rs.getLong("item_id"), new Progress(rs.getBoolean("passed"),
                            rs.getInt("clears"), rs.getDouble("best_rate")));
                }, userId);
        return out;
    }

    public Progress progressOf(Long userId, long itemId) {
        return progress(userId).getOrDefault(itemId, new Progress(false, 0, 0));
    }

    /** 过了多少题：个人主页只显示这个数，不为此把整棵题库扫一遍。 */
    public int passedCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        Integer n = jdbc.queryForObject(
                "select count(*) from quest_progress where user_id = ? and passed = true",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** 等级用三角数作门槛：第 n 级从 50n(n-1) 分开始，前几级涨得快，后面才需要攒。 */
    public record Point(int xp, int level, int levelFrom, int nextAt, int streakDays, boolean today) {
    }

    public Point point(Long userId) {
        if (userId == null) {
            return new Point(0, 1, 0, xpFor(2), 0, false);
        }
        List<Point> rows = jdbc.query("select xp, streak_days, last_day from quest_point where user_id = ?",
                (rs, n) -> {
                    int xp = rs.getInt("xp");
                    LocalDate last = rs.getObject("last_day", LocalDate.class);
                    int level = levelOf(xp);
                    return new Point(xp, level, xpFor(level), xpFor(level + 1), rs.getInt("streak_days"),
                            last != null && last.equals(LocalDate.now()));
                }, userId);
        return rows.isEmpty() ? new Point(0, 1, 0, xpFor(2), 0, false) : rows.get(0);
    }

    static int xpFor(int level) {
        return 50 * (level - 1) * level;
    }

    static int levelOf(int xp) {
        int level = 1;
        while (level < 99 && xp >= xpFor(level + 1)) {
            level++;
        }
        return level;
    }

    public record CheckResult(double accuracy, boolean passed, boolean firstClear, int xpGained,
                              List<Boolean> marks, String message) {
    }

    /** 挖空判分：逐空比，答对六成就过。 */
    public CheckResult checkCloze(Long userId, ContentItem item, List<String> given, List<String> answers) {
        List<Boolean> marks = new ArrayList<>(answers.size());
        int right = 0;
        for (int i = 0; i < answers.size(); i++) {
            String got = i < given.size() ? given.get(i) : "";
            boolean ok = decks.matches(answers.get(i), got);
            marks.add(ok);
            if (ok) {
                right++;
            }
        }
        double accuracy = answers.isEmpty() ? 1.0 : (double) right / answers.size();
        return settle(userId, item, accuracy, accuracy >= PASS_RATE, marks);
    }

    /**
     * 模糊背：两个信号一起算。
     * <p>
     * 自评决定「过不过」——很多人觉得自己背得不错，看了原文才发现漏了要点，这正是要拦住的情况；
     * 擦开多少只当系数，擦得多说明依赖提示，分数打折，但不单独决定过不过。
     */
    public CheckResult checkRecall(Long userId, ContentItem item, String self, int hintPercent) {
        double base = switch (self == null ? "" : self) {
            case "all" -> SELF_ALL;
            case "most" -> SELF_MOST;
            case "few" -> SELF_FEW;
            case "none" -> SELF_NONE;
            default -> 0;
        };
        double factor = hintPercent <= 10 ? 1.0
                : hintPercent <= 25 ? 0.95
                : hintPercent <= 50 ? 0.85 : 0.7;
        boolean passed = "all".equals(self) || "most".equals(self);
        return settle(userId, item, base * factor, passed, List.of());
    }

    private CheckResult settle(Long userId, ContentItem item, double accuracy, boolean passed,
                               List<Boolean> marks) {
        if (userId == null) {
            return new CheckResult(accuracy, passed, false, 0, marks, null);
        }
        Progress before = progressOf(userId, item.getId());
        boolean firstClear = passed && !before.passed();
        int gain = firstClear ? 10 * Math.max(1, item.getRating()) : 0;

        jdbc.update("insert into quest_progress (user_id, item_id, clears, passed, best_rate, last_rate, updated_at)"
                        + " values (?, ?, 1, ?, ?, ?, now())"
                        + " on conflict (user_id, item_id) do update"
                        + " set clears = quest_progress.clears + 1,"
                        + "     passed = quest_progress.passed or excluded.passed,"
                        + "     best_rate = greatest(quest_progress.best_rate, excluded.best_rate),"
                        + "     last_rate = excluded.last_rate,"
                        + "     updated_at = now()",
                userId, item.getId(), passed, accuracy, accuracy);

        if (firstClear) {
            jdbc.update("insert into quest_point (user_id, xp, streak_days, last_day) values (?, ?, 1, current_date)"
                            + " on conflict (user_id) do update set"
                            + "  streak_days = case"
                            + "    when quest_point.last_day = current_date then quest_point.streak_days"
                            + "    when quest_point.last_day = current_date - 1 then quest_point.streak_days + 1"
                            + "    else 1 end,"
                            + "  last_day = current_date,"
                            + "  xp = quest_point.xp + excluded.xp",
                    userId, gain);
        }
        return new CheckResult(accuracy, passed, firstClear, gain, marks, null);
    }

    public static String kindLabel(ContentKind kind) {
        return kind == ContentKind.BAGU ? "八股考核" : "算法考核";
    }
}
