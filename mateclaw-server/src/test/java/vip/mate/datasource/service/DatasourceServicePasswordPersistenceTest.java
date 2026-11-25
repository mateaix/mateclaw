package vip.mate.datasource.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.repository.DatasourceMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceServicePasswordPersistenceTest {

    private static final String PASSWORD_MASK = "******";
    private static final String PLAINTEXT = "reader-password";

    @Test
    @DisplayName("create masks the response password while persisting ciphertext")
    void createMasksPasswordInResponse() {
        Fixture fixture = new Fixture();

        DatasourceEntity response = fixture.service.create(fixture.newDatasource());

        assertEquals(PASSWORD_MASK, response.getPassword());
        String ciphertext = fixture.persisted().getPassword();
        assertNotEquals(PLAINTEXT, ciphertext);
        assertEquals(PLAINTEXT, fixture.service.getDecrypted(1L).getPassword());
    }

    @Test
    @DisplayName("update masks a newly encrypted password in the response")
    void updateMasksPasswordInResponse() {
        Fixture fixture = new Fixture();
        fixture.service.create(fixture.newDatasource());
        DatasourceEntity update = fixture.newDatasource();
        update.setId(1L);
        update.setPassword("replacement-password");

        DatasourceEntity response = fixture.service.update(update);

        assertEquals(PASSWORD_MASK, response.getPassword());
        assertEquals("replacement-password", fixture.service.getDecrypted(1L).getPassword());
    }

    @Test
    @DisplayName("reusing a masked create response keeps repeated updates decryptable")
    void repeatedUpdatesPreserveDecryptability() {
        Fixture fixture = new Fixture();
        DatasourceEntity created = fixture.service.create(fixture.newDatasource());
        String originalCiphertext = fixture.persisted().getPassword();

        for (int i = 0; i < 3; i++) {
            DatasourceEntity update = copy(created);
            update.setName("DUCHA " + i);

            DatasourceEntity response = fixture.service.update(update);

            assertEquals(PASSWORD_MASK, response.getPassword());
            assertEquals(originalCiphertext, fixture.persisted().getPassword());
            assertEquals(PLAINTEXT, fixture.service.getDecrypted(1L).getPassword());
        }
    }

    @Test
    @DisplayName("legacy plaintext passwords remain usable")
    void legacyPlaintextPasswordFallbackIsPreserved() {
        DatasourceMapper mapper = mock(DatasourceMapper.class);
        DatasourceService service = new DatasourceService(mapper, mock(DatasourceConnectionManager.class));
        ReflectionTestUtils.setField(service, "encryptKey", "test-datasource-key");
        DatasourceEntity legacy = new DatasourceEntity();
        legacy.setId(1L);
        legacy.setName("legacy");
        legacy.setPassword(PLAINTEXT);
        when(mapper.selectById(1L)).thenReturn(legacy);

        assertEquals(PLAINTEXT, service.getDecrypted(1L).getPassword());
    }

    @Test
    @DisplayName("connection test never persists a decrypted datasource password")
    void testConnectionReencryptsPasswordBeforeUpdate() {
        Fixture fixture = new Fixture();
        fixture.service.create(fixture.newDatasource());
        doAnswer(invocation -> {
            DatasourceEntity entity = invocation.getArgument(0);
            assertEquals(PLAINTEXT, entity.getPassword());
            return true;
        }).when(fixture.connectionManager).testConnection(any(DatasourceEntity.class));

        assertTrue(fixture.service.testConnection(1L));

        String persistedPassword = fixture.persisted().getPassword();
        assertNotEquals(PLAINTEXT, persistedPassword);
        assertTrue(persistedPassword.matches("[0-9a-f]+"));
        assertEquals(PLAINTEXT, fixture.service.getDecrypted(1L).getPassword());
        verify(fixture.mapper).updateById(any(DatasourceEntity.class));
    }

    private static DatasourceEntity copy(DatasourceEntity source) {
        DatasourceEntity target = new DatasourceEntity();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static class Fixture {
        private final DatasourceMapper mapper = mock(DatasourceMapper.class);
        private final DatasourceConnectionManager connectionManager = mock(DatasourceConnectionManager.class);
        private final DatasourceService service = new DatasourceService(mapper, connectionManager);
        private final AtomicReference<DatasourceEntity> stored = new AtomicReference<>();

        private Fixture() {
            ReflectionTestUtils.setField(service, "encryptKey", "test-datasource-key");
            doAnswer(invocation -> {
                DatasourceEntity entity = invocation.getArgument(0);
                entity.setId(1L);
                stored.set(copy(entity));
                return 1;
            }).when(mapper).insert(any(DatasourceEntity.class));
            doAnswer(invocation -> {
                stored.set(copy(invocation.getArgument(0)));
                return 1;
            }).when(mapper).updateById(any(DatasourceEntity.class));
            when(mapper.selectById(1L)).thenAnswer(invocation -> copy(stored.get()));
        }

        private DatasourceEntity newDatasource() {
            DatasourceEntity entity = new DatasourceEntity();
            entity.setName("DUCHA");
            entity.setPassword(PLAINTEXT);
            return entity;
        }

        private DatasourceEntity persisted() {
            return stored.get();
        }
    }
}
