package vip.mate.wiki.dto;

/**
 * Create/update payload for a Wiki source group.
 */
public record SourceGroupRequest(String alias, String path, String fileFilter,
                                  String scanMode, String cronExpr, Boolean enabled) {}
