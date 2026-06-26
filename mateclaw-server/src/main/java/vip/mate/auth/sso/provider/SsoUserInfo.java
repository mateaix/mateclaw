package vip.mate.auth.sso.provider;

/**
 * IdP 返回的标准化用户信息。各 Provider 把平台特异字段映射到此结构。
 *
 * @param externalId  open_id（provider 内唯一）
 * @param unionId     union_id（跨应用唯一, nullable — 飞书需开启 union_id 数据权限）
 * @param displayName 昵称
 * @param avatarUrl   头像 URL
 * @param email       邮箱（nullable）
 * @param mobile      手机（nullable）
 *
 * @author MateClaw Team
 */
public record SsoUserInfo(
        String externalId,
        String unionId,
        String displayName,
        String avatarUrl,
        String email,
        String mobile
) {}
