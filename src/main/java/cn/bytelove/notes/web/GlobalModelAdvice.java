package cn.bytelove.notes.web;

import cn.bytelove.notes.config.AppProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    private final AppProperties props;

    public GlobalModelAdvice(AppProperties props) {
        this.props = props;
    }

    @ModelAttribute("tabProjectsHref")
    public String tabProjectsHref() {
        String href = props.tabProjectsHref();
        return (href == null || href.isBlank()) ? "/" : href;
    }

    @ModelAttribute("registerEnabled")
    public boolean registerEnabled() {
        return Boolean.TRUE.equals(props.registerEnabled());
    }
}
