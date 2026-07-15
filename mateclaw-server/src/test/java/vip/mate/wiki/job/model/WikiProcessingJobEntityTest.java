package vip.mate.wiki.job.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiProcessingJobEntityTest {

    @Test
    void postgresCompatiblePrimaryKeyUsesApplicationAssignedId() throws NoSuchFieldException {
        TableId tableId = WikiProcessingJobEntity.class
                .getDeclaredField("id")
                .getAnnotation(TableId.class);

        assertEquals(IdType.ASSIGN_ID, tableId.type());
    }
}
