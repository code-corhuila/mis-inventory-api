package co.medistock.inventory;

import co.medistock.inventory.adapters.References;
import co.medistock.inventory.core.InventoryFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ReferencesTest {
    HttpServer server;
    int status;
    String body;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            assertEquals("Bearer test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("trace-001",exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] data=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,data.length);
            try(var output=exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    void verify() {
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        new References(url,url).verify(UUID.randomUUID(),UUID.randomUUID(),"test-token","trace-001");
    }
    @Test void acceptsActiveReferences() { status=200;body="{\"active\":true}";assertDoesNotThrow(this::verify); }
    @Test void rejectsInactiveReferences() { status=200;body="{\"active\":false}";assertEquals(422,assertThrows(InventoryFailure.class,this::verify).status()); }
    @Test void rejectsMissingReferences() { status=404;body="{}";assertEquals(422,assertThrows(InventoryFailure.class,this::verify).status()); }
    @Test void dependencyFailureIsNotBusinessRejection() { status=503;body="{}";assertEquals(503,assertThrows(InventoryFailure.class,this::verify).status()); }
    @Test void malformedDependencyIsUnavailable() { status=200;body="invalid";assertEquals(503,assertThrows(InventoryFailure.class,this::verify).status()); }
}
