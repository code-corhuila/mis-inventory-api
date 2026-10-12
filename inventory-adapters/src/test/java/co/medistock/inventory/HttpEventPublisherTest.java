package co.medistock.inventory;

import co.medistock.inventory.adapters.HttpEventPublisher;
import co.medistock.inventory.core.StockEvent;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HttpEventPublisherTest {
    @TempDir Path folder;
    static KeyPair pair;
    HttpServer server; ExecutorService executor;
    Path tokenFile; UUID subject=UUID.randomUUID(),id=UUID.randomUUID();
    volatile int calls,status=200; volatile String answer,authorization,trace,requestBody;
    volatile boolean stall; final CountDownLatch release=new CountDownLatch(1);
    HttpEventPublisher publisher;
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair=generator.generateKeyPair();
    }
    Map<String,Object> claims() {
        long now=Instant.now().getEpochSecond();
        return new HashMap<>(Map.of("sub",subject.toString(),"iss","mis-security","aud","mis-events",
            "service","mis-inventory-relay","environment","dev","roles",List.of(),"iat",now,"exp",now+3600,"jti",UUID.randomUUID().toString()));
    }
    String signed(Map<String,Object> claims,String kid,KeyPair signer) throws Exception {
        var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),new Payload(claims));
        jwt.sign(new RSASSASigner((RSAPrivateKey)signer.getPrivate())); return jwt.serialize();
    }
    void credential(Map<String,Object> claims) throws Exception { Files.writeString(tokenFile,signed(claims,"dev-key",pair)); }
    StockEvent event() {
        var body=new HashMap<String,Object>();
        body.putAll(Map.of("eventId",id.toString(),"movementId",id.toString(),"lotId",id.toString(),"medicationId",id.toString(),
            "supplierId",id.toString(),"eventType","inventory.stock.changed","schemaVersion",1,"sequence",1L,
            "occurredAt","2026-10-11T12:00:00Z","traceId","relay-http-test"));
        body.putAll(Map.of("movementType","ENTRY","expiresOn","2099-01-01","quantity",10,"delta",10,"onHandQuantity",10,"entryId",id.toString()));
        body.put("exitId",null); return new StockEvent(body);
    }
    String ack() { return "{\"eventId\":\""+id+"\",\"sequence\":1,\"accepted\":true}"; }
    URI origin() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
    @BeforeEach void start() throws Exception {
        tokenFile=folder.resolve("service.jwt"); credential(claims()); answer=ack();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/internal/events/stock-changed",exchange -> {
            calls++; authorization=exchange.getRequestHeaders().getFirst("Authorization"); trace=exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location",origin()+"/redirect");
            try(var output=exchange.getResponseBody()) {
                if(stall) {
                    exchange.sendResponseHeaders(200,0); output.write('{'); output.flush();
                    try { release.await(15,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                } else {
                    byte[] bytes=answer.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length); output.write(bytes);
                }
            } catch(java.io.IOException cancelled) { }
        });
        server.start();
        publisher=new HttpEventPublisher(origin(),tokenFile,(RSAPublicKey)pair.getPublic(),"dev-key","dev",subject);
    }
    @AfterEach void stop() { release.countDown(); if(server!=null) server.stop(0); if(executor!=null) executor.shutdownNow(); }
    @Test void sendsImmutableEventOwnCredentialAndTraceThenRereadsRenewal() throws Exception {
        assertEquals(id,publisher.publish(event()).eventId()); assertEquals(1,publisher.publish(event()).sequence());
        assertEquals("relay-http-test",trace); assertEquals("Bearer "+Files.readString(tokenFile),authorization);
        var mapper=JsonMapper.builder().build();
        assertEquals(mapper.readTree(mapper.writeValueAsString(event().payload())),mapper.readTree(requestBody));
        String first=authorization; Path renewed=folder.resolve("renewed.tmp"); Files.writeString(renewed,signed(claims(),"dev-key",pair));
        Files.move(renewed,tokenFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        assertTrue(publisher.publish(event()).accepted()); assertNotEquals(first,authorization); assertEquals(3,calls);
    }
    @Test void rejectsWrongClaimsBeforeSending() throws Exception {
        for(var patch:List.of(Map.of("service","mis-worker"),Map.of("sub",UUID.randomUUID().toString()),
            Map.of("aud","mis-api"),Map.of("aud",List.of("mis-events")),Map.of("roles",List.of("INVENTORY")),
            Map.of("environment","qa"),Map.of("iss","foreign"),Map.of("jti","invalid"),Map.of("iat",1.5),
            Map.of("exp",Instant.now().getEpochSecond()-1),Map.of("exp",Instant.now().getEpochSecond()+7200))) {
            var claims=claims(); claims.putAll(patch); credential(claims);
            assertThrows(IllegalStateException.class,() -> publisher.publish(event()));
        }
        var claims=claims(); claims.remove("exp"); credential(claims);
        assertThrows(IllegalStateException.class,() -> publisher.publish(event())); assertEquals(0,calls);
    }
    @Test void rejectsForeignSignatureKeyIdAndMissingOrOversizedFile() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var foreign=generator.generateKeyPair();
        for(String token:List.of(signed(claims(),"dev-key",foreign),signed(claims(),"qa-key",pair),"invalid","x".repeat(8193))) {
            Files.writeString(tokenFile,token); assertThrows(IllegalStateException.class,() -> publisher.publish(event()));
        }
        Files.delete(tokenFile); assertThrows(IllegalStateException.class,() -> publisher.publish(event())); assertEquals(0,calls);
    }
    @Test void rejectsNon200WithoutFollowingRedirect() {
        for(int code:new int[]{302,401,403,409,503}) {
            status=code; assertThrows(IllegalStateException.class,() -> publisher.publish(event()));
        }
        assertEquals(5,calls);
    }
    @Test void rejectsCorruptMismatchedDuplicateAndOversizedAcknowledgements() {
        for(String body:List.of("invalid","[]","null",ack().replace(id.toString(),UUID.randomUUID().toString()),
            ack().replace("\"sequence\":1","\"sequence\":2"),ack().replace("\"sequence\":1","\"sequence\":1.0"),
            ack().replace("\"sequence\":1","\"sequence\":\"1\""),ack().replace("true","false"),
            ack().replace("true","\"true\""),ack().replace("}",",\"extra\":1}"),
            ack().replace("}",",\"sequence\":1}"),"x".repeat(65537))) {
            answer=body; assertThrows(IllegalStateException.class,() -> publisher.publish(event()));
        }
    }
    @Test void fullResponseDeadlineIncludesStalledBody() {
        stall=true; Instant start=Instant.now();
        assertThrows(IllegalStateException.class,() -> publisher.publish(event()));
        assertTrue(Duration.between(start,Instant.now()).toSeconds()<10); assertEquals(1,calls);
    }
    @Test void refusesUnsafeConfiguration() {
        for(String url:List.of("file:///tmp/worker","http://user:secret@localhost","http://localhost/path","http://localhost?key=x","http://localhost#fragment"))
            assertThrows(IllegalArgumentException.class,() -> new HttpEventPublisher(URI.create(url),tokenFile,(RSAPublicKey)pair.getPublic(),"dev-key","dev",subject));
        assertThrows(IllegalArgumentException.class,() -> new HttpEventPublisher(origin(),tokenFile,(RSAPublicKey)pair.getPublic(),"dev-key","dev",new UUID(0,0)));
    }
}
