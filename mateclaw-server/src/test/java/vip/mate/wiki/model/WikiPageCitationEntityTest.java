package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiPageCitationEntityTest {

    @Test
    void postgresCompatiblePrimaryKeyUsesApplicationAssignedId() throws NoSuchFieldException {
        TableId tableId = WikiPageCitationEntity.class
                .getDeclaredField("id")
                .getAnnotation(TableId.class);

        assertEquals(IdType.ASSIGN_ID, tableId.type());
    }
}
