package vip.mate.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ILinkClientUploadUrlTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getUploadUrlRequestsNoThumbnailForFileUploads() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getuploadurl", exchange -> handleUploadUrl(exchange, requestBody));
        server.start();

        ObjectMapper appMapper = new ObjectMapper();
        appMapper.getFactory().configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);
        ILinkClient client = new ILinkClient("token", "http://127.0.0.1:" + server.getAddress().getPort(), appMapper);

        client.getUploadUrl("file-key", 3, "user-1", 123, "md5", 144, "00112233445566778899aabbccddeeff");

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.get("media_type").asInt()).isEqualTo(3);
        assertThat(body.get("rawsize").isNumber()).isTrue();
        assertThat(body.get("filesize").isNumber()).isTrue();
        assertThat(body.get("no_need_thumb").asBoolean()).isTrue();
        assertThat(body.at("/base_info/channel_version").asText()).isEqualTo("1.0.2");
    }

    @Test
    void getUploadUrlThrowsBusinessErrorBeforeCheckingUploadParam() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/getuploadurl", exchange -> {
            byte[] response = "{\"ret\":-2}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ILinkClient client = new ILinkClient("token", "http://127.0.0.1:" + server.getAddress().getPort(), objectMapper);

        assertThatThrownBy(() -> client.getUploadUrl("file-key", 3, "user-1", 123, "md5", 144,
                "00112233445566778899aabbccddeeff"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("getUploadUrl business error: ret=-2");
    }

    @Test
    void sendMessageUsesWireJsonAndRejectsBusinessErrors() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ilink/bot/sendmessage", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ret\":-7}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ObjectMapper appMapper = new ObjectMapper();
        appMapper.getFactory().configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);
        ILinkClient client = new ILinkClient("token", "http://127.0.0.1:" + server.getAddress().getPort(), appMapper);
        Map<String, Object> fileItem = Map.of(
                "type", 4,
                "file_item", Map.of(
                        "file_name", "report.pptx",
                        "md5", "abc",
                        "len", "123",
                        "media", Map.of("encrypt_query_param", "encrypted", "aes_key", "aes", "encrypt_type", 1)
                )
        );
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("to_user_id", "user-1");
        msg.put("item_list", List.of(fileItem));

        assertThatThrownBy(() -> client.sendMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sendMessage business error: ret=-7");

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.at("/msg/item_list/0/file_item/len").isTextual()).isTrue();
        assertThat(body.at("/msg/item_list/0/file_item/md5").asText()).isEqualTo("abc");
        assertThat(body.at("/msg/item_list/0/file_item/media/encrypt_type").asInt()).isEqualTo(1);
    }

    private void handleUploadUrl(HttpExchange exchange, AtomicReference<String> requestBody) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = "{\"ret\":0,\"upload_param\":\"encrypted\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
