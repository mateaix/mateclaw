package vip.mate.i18n;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.common.result.R;

/**
 * 在启动时将 I18nService 注入到 R（统一响应类）中，
 * 使 R.ok() / R.fail() 返回的 msg 跟随系统语言设置。
 *
 * @author MateClaw Team
 */
@Component
@RequiredArgsConstructor
public class I18nAutoConfig {

    private final I18nService i18nService;

    @PostConstruct
    public void init() {
        R.setI18n(i18nService);
    }

    @PreDestroy
    public void destroy() {
        R.clearI18n(i18nService);
    }
}
