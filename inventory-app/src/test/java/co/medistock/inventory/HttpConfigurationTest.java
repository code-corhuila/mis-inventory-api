package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.core.JacksonException;
import static org.junit.jupiter.api.Assertions.*;

class HttpConfigurationTest {
    @Test void unknownLengthPostIsBoundedAndValidBodyRemainsReadable() throws Exception {
        for(int size:new int[]{65536,65537}) {
            var request=new MockHttpServletRequest("POST","/api/v1/entries") {
                @Override public long getContentLengthLong() { return -1; }
            };
            request.setContent(new byte[size]); var response=new MockHttpServletResponse();
            new RequestContext(new Errors()).doFilter(request,response,(a,b) -> {
                assertEquals(65536,a.getInputStream().readAllBytes().length);
                if(size>65536) fail("Oversized request reached handler");
            });
            assertEquals(size>65536?413:200,response.getStatus());
        }
    }
    @Test void jsonRejectsUnknownFieldsAndCoercion() {
        var mapper=new HttpConfiguration().jsonMapper();
        for(String body:new String[]{"{\"unknown\":true}","{\"quantity\":\"2\"}","{\"quantity\":2.5}","{\"lotId\":7}"})
            assertThrows(JacksonException.class,() -> mapper.readValue(body,ExitApi.Request.class));
    }
    @Test void correlationIsValidatedPropagatedAndMdcCleared() throws Exception {
        for(String trace:new String[]{"server-test","invalid correlation"}) {
            var request=new MockHttpServletRequest("GET","/health"); request.addHeader("X-Correlation-Id",trace);
            var response=new MockHttpServletResponse();
            new RequestContext(new Errors()).doFilter(request,response,(a,b) -> assertEquals("server-test",a.getAttribute("traceId")));
            assertEquals(trace.equals("server-test")?200:400,response.getStatus());
            assertNotNull(response.getHeader("X-Correlation-Id")); assertNull(org.slf4j.MDC.get("traceId"));
        }
    }
}
