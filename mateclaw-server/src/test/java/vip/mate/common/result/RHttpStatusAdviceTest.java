package vip.mate.common.result;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RHttpStatusAdviceTest {

    @Test
    void failEnvelopeSetsHttpStatusFromBodyCode() {
        RHttpStatusAdvice advice = new RHttpStatusAdvice();
        MockHttpServletResponse servlet = new MockHttpServletResponse();

        advice.beforeBodyWrite(R.fail(400, "bad input"), null, MediaType.APPLICATION_JSON,
                null, null, new ServletServerHttpResponse(servlet));

        assertEquals(400, servlet.getStatus());
    }

    @Test
    void defaultFailEnvelopeSetsInternalServerErrorStatus() {
        RHttpStatusAdvice advice = new RHttpStatusAdvice();
        MockHttpServletResponse servlet = new MockHttpServletResponse();

        advice.beforeBodyWrite(R.fail("boom"), null, MediaType.APPLICATION_JSON,
                null, null, new ServletServerHttpResponse(servlet));

        assertEquals(500, servlet.getStatus());
    }
}
