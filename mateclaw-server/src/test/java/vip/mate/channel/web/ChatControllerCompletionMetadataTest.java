package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.workspace.conversation.model.MessageEntity;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatControllerCompletionMetadataTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    @SuppressWarnings("unchecked")
    void doneCarriesPersistedFinishReasonIncludingWrappedMetadata(boolean wrapped) throws Exception {
        var mapper = new ObjectMapper();
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller,"objectMapper",mapper);
        var saved = new MessageEntity(); saved.setId(1L);
        String metadata = "{\"finishReason\":\"incomplete\",\"segments\":[]}";
        saved.setMetadata(wrapped ? mapper.writeValueAsString(metadata) : metadata);
        var method = ChatController.class.getDeclaredMethod("buildDonePayload",String.class,String.class,MessageEntity.class,
                int.class,int.class,boolean.class,Integer.class); method.setAccessible(true);
        var payload = (Map<String,Object>)method.invoke(controller,"conversation","completed",saved,1,2,true,3);
        assertEquals("completed",payload.get("status"));
        assertEquals("incomplete",payload.get("finishReason"));
    }
}
