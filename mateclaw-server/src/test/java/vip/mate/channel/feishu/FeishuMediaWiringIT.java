package vip.mate.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.MateClawApplication;
import vip.mate.channel.media.GeneratedFileScrubber;
import vip.mate.channel.media.MediaSizePolicy;
import vip.mate.channel.media.MediaUploader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test — confirms the new Layer 1 + Layer 2 media beans
 * are wired by Spring with the right contracts, so a runtime
 * NoSuchBeanDefinitionException can't slip through to first user
 * traffic after a deploy.
 *
 * <p>Does NOT touch any real Feishu credentials or call the upstream
 * SDK — purely a Spring container contract test.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class FeishuMediaWiringIT {

    @Autowired private FeishuClientFactory clientFactory;
    @Autowired private FeishuMediaUploader mediaUploader;
    @Autowired private FeishuSizePolicy sizePolicy;
    @Autowired private GeneratedFileScrubber scrubber;
    @Autowired private FeishuStreamingCardManager streamingCardManager;
    @Autowired private vip.mate.channel.feishu.cards.FeishuCardDispatcher cardDispatcher;
    @Autowired private List<MediaUploader> uploaderBeans;
    @Autowired private List<MediaSizePolicy> policyBeans;

    @Test
    @DisplayName("all Layer 1 + Layer 2 beans resolve")
    void beansAreWired() {
        assertNotNull(clientFactory);
        assertNotNull(mediaUploader);
        assertNotNull(sizePolicy);
        assertNotNull(scrubber);
        assertNotNull(streamingCardManager);
        assertNotNull(cardDispatcher);
        assertTrue(cardDispatcher.registeredKindNames().contains("tool_guard_approval"),
                "tool_guard kind should be auto-registered on dispatcher construction");
    }

    @Test
    @DisplayName("MediaUploader SPI picks up FeishuMediaUploader by channelType")
    void uploaderSpiContractsHold() {
        boolean hasFeishu = uploaderBeans.stream()
                .anyMatch(u -> "feishu".equals(u.channelType()));
        assertTrue(hasFeishu,
                "FeishuMediaUploader missing from MediaUploader SPI collection: "
                        + uploaderBeans.stream().map(MediaUploader::channelType).toList());
    }

    @Test
    @DisplayName("MediaSizePolicy SPI picks up FeishuSizePolicy by channelType")
    void policySpiContractsHold() {
        boolean hasFeishu = policyBeans.stream()
                .anyMatch(p -> "feishu".equals(p.channelType()));
        assertTrue(hasFeishu,
                "FeishuSizePolicy missing from MediaSizePolicy SPI collection: "
                        + policyBeans.stream().map(MediaSizePolicy::channelType).toList());
    }
}
