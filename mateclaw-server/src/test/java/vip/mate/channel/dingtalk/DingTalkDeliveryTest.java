package vip.mate.channel.dingtalk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DingTalkDeliveryTest {
    @Test void shortMarkdownIsUnchanged() {
        assertEquals(java.util.List.of("中".repeat(3500)), DingTalkDelivery.chunks("中".repeat(3500)));
    }
    @Test void longUnicodeIsPreserved() {
        String text = "😀中文".repeat(2000);
        var chunks = DingTalkDelivery.chunks(text);
        assertTrue(chunks.size() > 1);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(s -> s.length() <= 3000));
        assertTrue(chunks.stream().noneMatch(s -> Character.isHighSurrogate(s.charAt(s.length()-1))));
    }
    @Test void tablesRepeatHeaders() {
        String header = "| 名称 | 内容 |\n| --- | --- |\n";
        var chunks = DingTalkDelivery.chunks(header + "| 项目 | 内容内容内容 |\n".repeat(400));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(s -> s.startsWith(header) && s.length() <= 3000));
    }
    @Test void fencesRemainRenderable() {
        var chunks = DingTalkDelivery.chunks("```java\n" + "println();\n".repeat(600) + "```");
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(s -> s.startsWith("```java\n") && s.endsWith("```")));
    }
    @Test void openingFenceReservesItsClosingMarker() {
        var chunks = DingTalkDelivery.chunks("a".repeat(2990) + "\n```java\n" + "x\n".repeat(400) + "```");
        assertTrue(chunks.stream().allMatch(s -> s.length() <= 3000));
    }
    @Test void longerFencesKeepTheirMarkerLength() {
        var chunks = DingTalkDelivery.chunks("````java\n" + "```\nprintln();\n".repeat(600) + "````");
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(s -> s.startsWith("````java\n") && s.endsWith("````") && s.length() <= 3000));
    }
    @Test void webhookSplitsAndStopsOnBusinessFailure() throws Exception {
        var entity = new vip.mate.channel.model.ChannelEntity();
        entity.setId(1L); entity.setChannelType("dingtalk"); entity.setConfigJson("{}");
        var adapter = new DingTalkChannelAdapter(entity, null, new com.fasterxml.jackson.databind.ObjectMapper(), null);
        var client = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        @SuppressWarnings("unchecked")
        java.net.http.HttpResponse<String> response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body()).thenReturn("{\"errcode\":310000}");
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var field = DingTalkChannelAdapter.class.getDeclaredField("httpClient"); field.setAccessible(true); field.set(adapter, client);
        var method = DingTalkChannelAdapter.class.getDeclaredMethod("sendMarkdownViaWebhook", String.class, String.class);
        method.setAccessible(true);
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(adapter, "https://example.invalid/hook", "中".repeat(8000)));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).send(
                org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any());
        org.mockito.Mockito.when(response.body()).thenReturn("{\"errcode\":0}");
        org.mockito.Mockito.clearInvocations(client);
        method.invoke(adapter, "https://example.invalid/hook", "中".repeat(8000));
        var requests = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(3)).send(requests.capture(),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any());
        assertTrue(requests.getAllValues().stream().allMatch(r -> r.bodyPublisher().isPresent()));
    }

    @Test void webhookRequiresIntegerSuccessCode() {
        assertThrows(IllegalStateException.class, () -> DingTalkDelivery.validateWebhook(200, "{}"));
        assertThrows(IllegalStateException.class, () -> DingTalkDelivery.validateWebhook(200, "{\"errcode\":\"0\"}"));
        assertDoesNotThrow(() -> DingTalkDelivery.validateWebhook(200, "{\"errcode\":0}"));
    }
    @Test void rejectsBusinessAndHttpFailures() {
        assertDoesNotThrow(() -> DingTalkDelivery.validate(200, "{\"errcode\":0}"));
        assertDoesNotThrow(() -> DingTalkDelivery.validate(200, "{\"processQueryKey\":\"ok\"}"));
        assertThrows(IllegalStateException.class, () -> DingTalkDelivery.validate(200, "{\"errcode\":310000,\"errmsg\":\"denied\"}"));
        assertThrows(IllegalStateException.class, () -> DingTalkDelivery.validate(500, "oops"));
        assertThrows(IllegalStateException.class, () -> DingTalkDelivery.validate(200, "garbage"));
    }
}
