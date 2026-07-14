package vip.mate.datasource.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.datasource.model.DatasourceEntity;
import vip.mate.datasource.repository.DatasourceMapper;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceServicePasswordPersistenceTest {

    @Test
    @DisplayName("connection test never persists a decrypted datasource password")
    void testConnectionReencryptsPasswordBeforeUpdate() {
        // Given a datasource whose password is encrypted at creation time.
        String plaintext = "reader-password";
        DatasourceMapper mapper = mock(DatasourceMapper.class);
        DatasourceConnectionManager connectionManager = mock(DatasourceConnectionManager.class);
        DatasourceService service = new DatasourceService(mapper, connectionManager);
        ReflectionTestUtils.setField(service, "encryptKey", "test-datasource-key");

        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(1L);
        datasource.setName("DUCHA");
        datasource.setPassword(plaintext);
        service.create(datasource);
        assertNotEquals(plaintext, datasource.getPassword());
        when(mapper.selectById(1L)).thenReturn(datasource);
        doAnswer(invocation -> {
            assertTrue(plaintext.equals(datasource.getPassword()));
            return true;
        }).when(connectionManager).testConnection(datasource);

        // When MateClaw tests the JDBC connection.
        assertTrue(service.testConnection(1L));

        // Then the entity sent back to MyBatis must be encrypted again.
        assertNotEquals(plaintext, datasource.getPassword());
        assertTrue(datasource.getPassword().matches("[0-9a-f]+"));
        verify(mapper).updateById(datasource);
    }
}
