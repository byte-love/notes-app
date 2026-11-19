package cn.bytelove.notes.web;

import cn.bytelove.notes.service.StudyTimeService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import cn.bytelove.notes.service.ViewerService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习统计：页面 + 两个接口。
 * 计时在前端（只有页面可见才算），这里只负责累加和出数；
 * 统计口径按上海时区算「今天 / 本周（周一起）/ 本月」。
 */
@Controller
public class StudyController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> CATEGORIES = List.of("algo", "bagu", "project", "notes");
    private static final int MAX_DAYS = 90;

    private final StudyTimeService study;
    private final ViewerService viewers;

    public StudyController(StudyTimeService study, ViewerService viewers) {
        this.study = study;
        this.viewers = viewers;
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        model.addAttribute("tabActive", "me");
        return "stats";
    }

    @GetMapping("/api/study/stats")
    @ResponseBody
    public Map<String, Object> statsApi(Authentication auth,
                                        @RequestParam(defaultValue = "30") int days) {
        int span = Math.max(7, Math.min(MAX_DAYS, days));
        long userId = userId(auth);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = today.minusDays(span - 1L);

        Map<LocalDate, Map<String, Integer>> byDay = new LinkedHashMap<>();
        for (StudyTimeService.Row row : study.range(userId, from)) {
            byDay.computeIfAbsent(row.day(), d -> new LinkedHashMap<>())
                    .merge(row.category(), row.seconds(), Integer::sum);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            LocalDate day = from.plusDays(i);
            Map<String, Integer> cats = byDay.getOrDefault(day, Map.of());
            int total = cats.values().stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", day.toString());
            item.put("total", total);
            item.put("by", cats);
            list.add(item);
        }

        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monthStart = today.withDayOfMonth(1);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("today", sum(list, today, today));
        totals.put("week", sum(list, weekStart, today));
        totals.put("month", sum(list, monthStart, today));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totals", totals);
        body.put("days", list);
        body.put("categories", CATEGORIES);
        return body;
    }

    @PostMapping("/api/study/tick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tick(Authentication auth,
                     @RequestParam String category,
                     @RequestParam int seconds) {
        try {
            study.record(userId(auth), category, seconds);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static int sum(List<Map<String, Object>> days, LocalDate from, LocalDate to) {
        int total = 0;
        for (Map<String, Object> day : days) {
            LocalDate d = LocalDate.parse(String.valueOf(day.get("day")));
            if (!d.isBefore(from) && !d.isAfter(to)) {
                total += (int) day.get("total");
            }
        }
        return total;
    }

    private long userId(Authentication auth) {
        Viewer viewer = viewers.current(auth);
        if (viewer == null || viewer.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return viewer.userId();
    }
}
