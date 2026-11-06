package cn.bytelove.notes.web;

import cn.bytelove.notes.domain.ContentItem;
import cn.bytelove.notes.domain.ContentKind;
import cn.bytelove.notes.repo.ContentItemRepository;
import cn.bytelove.notes.service.MarkdownRenderer;
import cn.bytelove.notes.service.QuestDeckService;
import cn.bytelove.notes.service.QuestService;
import cn.bytelove.notes.service.ViewerService;
import cn.bytelove.notes.service.VisibilityService.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识考核：章节进度 + 一题一关（读 → 背 → 考）。
 * <p>
 * 只考自己空间里的内容，游客没有入口（八股是 MEMBER 可见，本来就进不来）。
 */
@Controller
@RequestMapping("/quest")
public class QuestController {

    private final QuestService quests;
    private final QuestDeckService decks;
    private final ContentItemRepository items;
    private final ViewerService viewers;
    private final MarkdownRenderer markdown;

    public QuestController(QuestService quests, QuestDeckService decks, ContentItemRepository items,
                           ViewerService viewers, MarkdownRenderer markdown) {
        this.quests = quests;
        this.decks = decks;
        this.items = items;
        this.viewers = viewers;
        this.markdown = markdown;
    }

    @GetMapping
    public String index(@RequestParam(name = "chapter", required = false) String chapter,
                        Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        List<QuestService.Chapter> chapters = quests.chapters(viewer.userId());

        QuestService.Chapter current = null;
        if (chapter != null && !chapter.isBlank()) {
            for (QuestService.Chapter c : chapters) {
                if (chapter.equals(c.slug())) {
                    current = c;
                    break;
                }
            }
        }

        int total = 0;
        int passed = 0;
        for (QuestService.Chapter c : chapters) {
            total += c.total();
            passed += c.passed();
        }

        model.addAttribute("point", quests.point(viewer.userId()));
        model.addAttribute("chapters", chapters);
        model.addAttribute("current", current);
        model.addAttribute("totalItems", total);
        model.addAttribute("totalPassed", passed);
        model.addAttribute("tabActive", "quest");
        model.addAttribute("pageTitle", "知识考核");
        return "quest";
    }

    @GetMapping("/{slug}")
    public String level(@PathVariable String slug, Model model, Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = owned(viewer, slug);
        QuestDeckService.Deck deck = decks.build(item.getBody());

        String chapterSlug = null;
        String chapterTitle = null;
        for (QuestService.Chapter c : quests.chapters(viewer.userId())) {
            for (QuestService.Item i : c.items()) {
                if (i.id() == item.getId()) {
                    chapterSlug = c.slug();
                    chapterTitle = c.title();
                    break;
                }
            }
            if (chapterTitle != null) {
                break;
            }
        }

        model.addAttribute("item", item);
        model.addAttribute("deck", deck);
        model.addAttribute("html", markdown.toHtml(deck.text()));
        model.addAttribute("progress", quests.progressOf(viewer.userId(), item.getId()));
        model.addAttribute("point", quests.point(viewer.userId()));
        model.addAttribute("chapterSlug", chapterSlug);
        model.addAttribute("chapterTitle", chapterTitle);
        model.addAttribute("tabActive", "quest");
        model.addAttribute("pageTitle", item.getTitle());
        return "quest-item";
    }

    /** 提交一次作答。答案以服务端现算的为准，前端传回来的只是用户填的内容。 */
    @PostMapping("/{slug}/check")
    @ResponseBody
    public Map<String, Object> check(@PathVariable String slug, @RequestBody CheckBody body,
                                     Authentication auth) {
        Viewer viewer = viewers.current(auth);
        ContentItem item = owned(viewer, slug);
        QuestDeckService.Deck deck = decks.build(item.getBody());

        QuestService.CheckResult result;
        int hint = 0;
        if (QuestDeckService.MODE_CLOZE.equals(deck.mode())) {
            List<String> given = body == null || body.answers() == null ? List.of() : body.answers();
            result = quests.checkCloze(viewer.userId(), item, given, deck.answers());
        } else {
            hint = body == null || body.hint() == null ? 0 : Math.max(0, Math.min(100, body.hint()));
            result = quests.checkRecall(viewer.userId(), item, body == null ? null : body.self(), hint);
        }

        QuestService.Point point = quests.point(viewer.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accuracy", Math.round(result.accuracy() * 100));
        out.put("passed", result.passed());
        out.put("firstClear", result.firstClear());
        out.put("xp", result.xpGained());
        out.put("marks", result.marks());
        out.put("answers", deck.answers());
        out.put("hint", hint);
        out.put("xpTotal", point.xp());
        out.put("level", point.level());
        return out;
    }

    private ContentItem owned(Viewer viewer, String slug) {
        if (viewer.userId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return items.findByOwnerIdAndSlug(viewer.userId(), slug)
                .filter(i -> i.getKind() == ContentKind.BAGU)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record CheckBody(List<String> answers, String self, Integer hint) {
    }

    /** 关卡列表里的「下一个没过的」：进页面时给个入口，省得自己找。 */
    public static List<QuestService.Item> pending(List<QuestService.Chapter> chapters) {
        List<QuestService.Item> out = new ArrayList<>();
        for (QuestService.Chapter c : chapters) {
            for (QuestService.Item i : c.items()) {
                if (!i.passed()) {
                    out.add(i);
                }
            }
        }
        return out;
    }
}
