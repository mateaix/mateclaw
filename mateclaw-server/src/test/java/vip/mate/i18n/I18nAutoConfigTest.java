package vip.mate.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.mate.common.result.R;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class I18nAutoConfigTest {

    @AfterEach
    void clearHolder() {
        R.setI18n(null);
    }

    @Test
    void closingContextClearsItsService() {
        I18nService service = localized("localized-success");
        I18nAutoConfig config = new I18nAutoConfig(service);
        config.init();

        config.destroy();

        assertEquals("result.success", R.ok().getMsg());
    }

    @Test
    void closingOlderContextDoesNotClearNewerService() {
        I18nAutoConfig older = new I18nAutoConfig(localized("older"));
        I18nAutoConfig newer = new I18nAutoConfig(localized("newer"));
        older.init();
        newer.init();

        older.destroy();

        assertEquals("newer", R.ok().getMsg());
    }

    private static I18nService localized(String message) {
        I18nService service = mock(I18nService.class);
        when(service.msg("result.success")).thenReturn(message);
        return service;
    }
}
