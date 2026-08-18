package vip.mate.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.llm.anthropic.oauth.ClaudeCodeOAuthService;
import vip.mate.llm.failover.AvailableProviderPool;
import vip.mate.llm.failover.ProviderHealthProperties;
import vip.mate.llm.failover.ProviderHealthTracker;
import vip.mate.llm.failover.ProviderInitProbe;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.model.ProviderOptionDTO;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.probe.ContextProbeProperties;
import vip.mate.llm.probe.ModelContextWindowResolver;
import vip.mate.llm.repository.ModelProviderMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The provider-options projection that workspace members read when they bind an
 * agent to a preferred provider. The full provider list stays admin-only, so
 * these tests pin the two properties that make the narrower endpoint safe and
 * useful: it carries no connection settings, and it lists only providers that
 * are actually usable.
 */
class ModelProviderServiceOptionsTest {

    private ModelProviderMapper providerMapper;
    private ModelConfigService modelConfigService;
    private AvailableProviderPool pool;

    private ModelProviderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        providerMapper = mock(ModelProviderMapper.class);
        modelConfigService = mock(ModelConfigService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectProvider<ClaudeCodeOAuthService> claudeCodeOAuthProvider = mock(ObjectProvider.class);
        when(claudeCodeOAuthProvider.getIfAvailable()).thenReturn(null);
        pool = new AvailableProviderPool();
        ProviderHealthProperties props = new ProviderHealthProperties();
        props.setFailureThreshold(1);
        ProviderHealthTracker healthTracker = new ProviderHealthTracker(props);
        ProviderInitProbe initProbe = mock(ProviderInitProbe.class);
        ObjectProvider<ProviderInitProbe> initProbeProvider = mock(ObjectProvider.class);
        when(initProbeProvider.getIfAvailable()).thenReturn(initProbe);
        when(initProbe.hasBeenProbed(any())).thenReturn(true);

        service = new ModelProviderService(providerMapper, modelConfigService, eventPublisher,
                claudeCodeOAuthProvider, pool, healthTracker, initProbeProvider,
                new ModelContextWindowResolver(List.of(), new ContextProbeProperties()),
                new ConversationWindowProperties());
    }

    @Test
    @DisplayName("configured providers surface as id + display name")
    void configuredProvidersBecomeOptions() {
        ModelProviderEntity openai = cloud("openai", "OpenAI");
        openai.setApiKey("sk-test-1234567890");
        seedProviders(openai);
        pool.add("openai");

        List<ProviderOptionDTO> options = service.listProviderOptions();

        assertThat(options).containsExactly(new ProviderOptionDTO("openai", "OpenAI"));
    }

    @Test
    @DisplayName("a provider without credentials is not offered as a choice")
    void unconfiguredProviderIsFilteredOut() {
        ModelProviderEntity kimi = cloud("kimi", "Kimi");
        kimi.setApiKey("");
        seedProviders(kimi);

        assertThat(service.listProviderOptions()).isEmpty();
    }

    @Test
    @DisplayName("the option carries no credential or connection field")
    void optionExposesNoConnectionSettings() {
        // Members reach this projection; the guarantee is structural, so assert
        // on the record's shape rather than on one serialized instance.
        List<String> fields = Arrays.stream(ProviderOptionDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(fields).containsExactly("id", "name");
        assertThat(fields).noneSatisfy(f -> {
            String lower = f.toLowerCase(Locale.ROOT);
            assertThat(lower).containsAnyOf("key", "url", "token", "secret");
        });
    }

    private void seedProviders(ModelProviderEntity... rows) {
        for (ModelProviderEntity p : rows) {
            if (p.getEnabled() == null) p.setEnabled(true);
        }
        when(providerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rows));
        List<ModelConfigEntity> models = Arrays.stream(rows).map(p -> {
            ModelConfigEntity m = new ModelConfigEntity();
            m.setProvider(p.getProviderId());
            m.setModelName(p.getProviderId() + "-model");
            m.setName(p.getProviderId() + "-model");
            m.setBuiltin(true);
            return m;
        }).toList();
        when(modelConfigService.listModels()).thenReturn(models);
    }

    private static ModelProviderEntity cloud(String id, String name) {
        ModelProviderEntity p = new ModelProviderEntity();
        p.setProviderId(id);
        p.setName(name);
        p.setIsLocal(false);
        p.setIsCustom(false);
        p.setRequireApiKey(true);
        return p;
    }
}
