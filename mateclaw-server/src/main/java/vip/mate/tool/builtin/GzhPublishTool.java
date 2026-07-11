package vip.mate.tool.builtin;

import cn.hutool.http.HttpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.system.service.SystemSettingService;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Built-in tool: publish a generated 图文 article to a WeChat Official Account.
 *
 * <p>The realistic, compliant endpoint is the <b>draft box</b> (草稿箱): the tool
 * uploads the cover image as a permanent material and creates a draft article via
 * the Official Account draft API. The account owner then reviews and taps
 * "publish" in the WeChat backend. Mass-send / one-click publish to all followers
 * is deliberately gated: it is an outward, irreversible action restricted by
 * platform verification and rate limits, so {@code publish} requires an explicit
 * confirmation flag and is only meaningful for verified accounts.
 *
 * <p>Credentials are read from system settings ({@code weixinoa.app_id} /
 * {@code weixinoa.app_secret}); nothing runs until they are configured.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GzhPublishTool {

    private static final String SETTING_APP_ID = "weixinoa.app_id";
    private static final String SETTING_APP_SECRET = "weixinoa.app_secret";

    private final SystemSettingService systemSettingService;

    @Tool(name = "gzh_publish", description = """
        Publish a generated image-text article to a WeChat Official Account (微信公众号).

        Actions:
          - draft  (default): upload the cover image and create a draft in the
            Official Account 草稿箱. The user then taps "publish" in the WeChat
            backend. This is the recommended, compliant path.
          - publish: submit an already-drafted article for free-publish. Only works
            for verified accounts and is an irreversible outward action, so it
            requires confirmPublish=true AND you MUST get explicit user confirmation
            of the final content before calling it.

        `content` must be WeChat-editor-compatible HTML with INLINE styles only
        (公众号 ignores <style> blocks). `coverImageUrl` is required for a draft
        (WeChat requires a cover / thumb). Requires weixinoa.app_id and
        weixinoa.app_secret to be configured in system settings.
        """)
    public String gzh_publish(
            @ToolParam(description = "Action: draft (default) or publish", required = false)
            String action,
            @ToolParam(description = "Article title (required for draft)", required = false)
            String title,
            @ToolParam(description = "Article body as inline-styled HTML (required for draft)", required = false)
            String content,
            @ToolParam(description = "Cover image URL — uploaded as the article thumb (required for draft)", required = false)
            String coverImageUrl,
            @ToolParam(description = "Author / source name", required = false)
            String author,
            @ToolParam(description = "Short summary shown in the article list (<=120 chars); auto-derived if omitted", required = false)
            String digest,
            @ToolParam(description = "Draft media_id to free-publish (required for publish action)", required = false)
            String draftMediaId,
            @ToolParam(description = "Must be true to actually free-publish; forces explicit user confirmation", required = false)
            Boolean confirmPublish) {

        String appId = systemSettingService.getString(SETTING_APP_ID, "");
        String appSecret = systemSettingService.getString(SETTING_APP_SECRET, "");
        if (appId.isBlank() || appSecret.isBlank()) {
            return "Error: WeChat Official Account is not configured. Set '" + SETTING_APP_ID
                    + "' and '" + SETTING_APP_SECRET + "' in system settings first.";
        }

        WxMpService wxMpService = buildService(appId, appSecret);
        String act = (action == null || action.isBlank()) ? "draft" : action.trim().toLowerCase();

        return switch (act) {
            case "draft" -> createDraft(wxMpService, title, content, coverImageUrl, author, digest);
            case "publish" -> freePublish(wxMpService, draftMediaId, confirmPublish);
            default -> "Error: unknown action '" + act + "'. Use 'draft' or 'publish'.";
        };
    }

    private String createDraft(WxMpService wxMpService, String title, String content,
                               String coverImageUrl, String author, String digest) {
        if (title == null || title.isBlank()) {
            return "Error: title is required for a draft.";
        }
        if (content == null || content.isBlank()) {
            return "Error: content (inline-styled HTML) is required for a draft.";
        }
        if (coverImageUrl == null || coverImageUrl.isBlank()) {
            return "Error: coverImageUrl is required — WeChat needs a cover/thumb for the article.";
        }

        // 1. Download the cover and upload it as a permanent image material -> thumb media_id.
        String thumbMediaId;
        File tmpCover = null;
        try {
            tmpCover = Files.createTempFile("gzh_cover_", ".jpg").toFile();
            HttpUtil.downloadFile(coverImageUrl, tmpCover);
            WxMpMaterial material = new WxMpMaterial();
            material.setName(tmpCover.getName());
            material.setFile(tmpCover);
            WxMpMaterialUploadResult uploaded = wxMpService.getMaterialService()
                    .materialFileUpload(WxConsts.MediaFileType.IMAGE, material);
            thumbMediaId = uploaded.getMediaId();
            if (thumbMediaId == null || thumbMediaId.isBlank()) {
                return "Error: cover upload returned no media_id.";
            }
        } catch (WxErrorException e) {
            log.warn("[GzhPublish] cover upload failed: {}", e.getMessage());
            return "Error: cover upload failed — " + e.getMessage();
        } catch (Exception e) {
            log.warn("[GzhPublish] cover download/upload failed: {}", e.getMessage());
            return "Error: cover download/upload failed — " + e.getMessage();
        } finally {
            if (tmpCover != null) {
                //noinspection ResultOfMethodCallIgnored
                tmpCover.delete();
            }
        }

        // 2. Build the draft article and submit it.
        try {
            WxMpDraftArticles article = new WxMpDraftArticles();
            article.setTitle(trimTo(title, 64));
            article.setContent(content);
            article.setThumbMediaId(thumbMediaId);
            if (author != null && !author.isBlank()) {
                article.setAuthor(trimTo(author, 8));
            }
            article.setDigest(digest != null && !digest.isBlank()
                    ? trimTo(digest, 120)
                    : deriveDigest(content));

            String draftMediaId = wxMpService.getDraftService()
                    .addDraft(new WxMpAddDraft(List.of(article)));
            log.info("[GzhPublish] draft created, media_id={}, title='{}'", draftMediaId, title);
            return "✅ 已存入公众号草稿箱。\n"
                    + "draft media_id: " + draftMediaId + "\n"
                    + "请到公众号后台「草稿箱」核对排版后点击「发表」。\n"
                    + "如需直接群发（仅认证号），可用 gzh_publish action=publish draftMediaId=" + draftMediaId
                    + " confirmPublish=true，并在发布前与用户再次确认内容。";
        } catch (WxErrorException e) {
            log.warn("[GzhPublish] addDraft failed: {}", e.getMessage());
            return "Error: creating the draft failed — " + e.getMessage();
        }
    }

    private String freePublish(WxMpService wxMpService, String draftMediaId, Boolean confirmPublish) {
        if (draftMediaId == null || draftMediaId.isBlank()) {
            return "Error: draftMediaId is required for publish. Create a draft first.";
        }
        if (confirmPublish == null || !confirmPublish) {
            return "Publish is an irreversible outward action. Confirm the final content with the user, "
                    + "then call again with confirmPublish=true.";
        }
        try {
            String publishId = wxMpService.getFreePublishService().submit(draftMediaId);
            log.info("[GzhPublish] free-publish submitted, publish_id={}, draft={}", publishId, draftMediaId);
            return "✅ 已提交群发（free-publish）。publish_id: " + publishId
                    + "\n注意：发布结果由微信异步审核，请在公众号后台确认最终状态。";
        } catch (WxErrorException e) {
            log.warn("[GzhPublish] free-publish failed: {}", e.getMessage());
            return "Error: free-publish failed (verified accounts only) — " + e.getMessage();
        }
    }

    private WxMpService buildService(String appId, String appSecret) {
        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        config.setAppId(appId);
        config.setSecret(appSecret);
        WxMpService service = new WxMpServiceImpl();
        service.setWxMpConfigStorage(config);
        return service;
    }

    /** Strip tags and clamp to a length for the article digest. */
    private static String deriveDigest(String htmlContent) {
        String plain = htmlContent.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return trimTo(plain, 120);
    }

    private static String trimTo(String v, int max) {
        if (v == null) {
            return "";
        }
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
