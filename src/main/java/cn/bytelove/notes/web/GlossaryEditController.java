package cn.bytelove.notes.web;

import cn.bytelove.notes.service.GlossaryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * 术语解释的编辑入口：正文里点一下带解释的词，浮窗里就能改解释 / 改语境关键词 / 删掉 / 新增。
 * 改的是整站的术语表，所以只有管理员能写。
 */
@Controller
@RequestMapping("/glossary")
public class GlossaryEditController {

    private final GlossaryService glossary;

    public GlossaryEditController(GlossaryService glossary) {
        this.glossary = glossary;
    }

    /** 新增一条（同一个术语 + 同一个语境关键词已存在时改它的解释）。 */
    @PostMapping
    @ResponseBody
    public String add(@RequestParam String term,
                      @RequestParam String tip,
                      @RequestParam(required = false) String sense,
                      Authentication auth) {
        requireAdmin(auth);
        return String.valueOf(glossary.add(term, tip, sense));
    }

    /** 改一条：术语、语境关键词、解释都能改。 */
    @PostMapping("/{id}")
    @ResponseBody
    public String update(@PathVariable long id,
                         @RequestParam String term,
                         @RequestParam String tip,
                         @RequestParam(required = false) String sense,
                         Authentication auth) {
        requireAdmin(auth);
        return glossary.update(id, term, tip, sense) ? "ok" : "";
    }

    @PostMapping("/{id}/delete")
    @ResponseBody
    public String delete(@PathVariable long id, Authentication auth) {
        requireAdmin(auth);
        return glossary.delete(id) ? "ok" : "";
    }

    private static void requireAdmin(Authentication auth) {
        boolean admin = auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
