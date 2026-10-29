package cn.bytelove.notes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notes")
public record AppProperties(
        String ownerUsername,
        String ownerPassword,
        String viewerUsername,
        String viewerPassword,
        String tabProjectsHref,
        Boolean registerEnabled
) {
}
