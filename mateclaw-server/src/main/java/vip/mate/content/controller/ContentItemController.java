package vip.mate.content.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.content.model.ContentItemEntity;
import vip.mate.content.service.ContentItemService;

import java.util.Map;

/**
 * Read-only content calendar API — lists produced 公众号 / 小红书 pieces and their
 * lifecycle status, so operators can see what's drafted / packaged / published /
 * pending. Writes happen through the tools ({@code content_item} + auto-record on
 * delivery), not here.
 */
@Tag(name = "内容日历")
@RestController
@RequestMapping("/api/v1/content-items")
@RequiredArgsConstructor
public class ContentItemController {

    private final ContentItemService contentItemService;

    @Operation(summary = "内容日历分页列表")
    @GetMapping
    public R<IPage<ContentItemEntity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status) {
        return R.ok(contentItemService.page(page, size, platform, status));
    }

    @Operation(summary = "内容日历状态计数")
    @GetMapping("/summary")
    public R<Map<String, Long>> summary() {
        return R.ok(contentItemService.summary());
    }
}
