package co.medistock.inventory.adapters;

import co.medistock.inventory.core.*;
import com.nimbusds.jwt.SignedJWT;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class HttpEventPublisher implements EventRelay.Publisher {
    private final HttpClient client;
    private final URI endpoint;
    private final Path tokenFile;
    private final NimbusJwtDecoder decoder;
    private final String keyId,environment;
    private final UUID subject;
    private final JsonMapper mapper=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();
    public HttpEventPublisher(URI origin,Path tokenFile,RSAPublicKey key,String keyId,String environment,UUID subject) {
        if(!Set.of("http","https").contains(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null ||
            origin.getQuery()!=null || origin.getFragment()!=null || !Set.of("","/").contains(origin.getPath()) ||
            key.getModulus().bitLength()<2048 || keyId==null || keyId.isBlank() ||
            !Set.of("dev","qa","main").contains(environment) || subject==null || subject.equals(new UUID(0,0)))
            throw new IllegalArgumentException("Invalid relay configuration");
        this.endpoint=origin.resolve("/internal/events/stock-changed"); this.tokenFile=tokenFile;
        this.keyId=keyId; this.environment=environment; this.subject=subject;
        decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),new JwtIssuerValidator("mis-security")));
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri,SocketAddress address,IOException error) { }
            }).build();
    }
    private String credential() throws Exception {
        byte[] bytes;
        try(var input=Files.newInputStream(tokenFile)) { bytes=input.readNBytes(8193); }
        if(bytes.length>8192) throw new IllegalStateException("Credential too large");
        String token=new String(bytes,StandardCharsets.US_ASCII).strip();
        var verified=decoder.decode(token);
        var raw=SignedJWT.parse(token).getPayload().toJSONObject();
        Object issued=raw.get("iat"),expires=raw.get("exp");
        if(!(issued instanceof Integer || issued instanceof Long) || !(expires instanceof Integer || expires instanceof Long))
            throw new IllegalStateException("Integer token dates required");
        long iat=((Number)issued).longValue(),exp=((Number)expires).longValue(),now=Instant.now().getEpochSecond();
        if(!keyId.equals(verified.getHeaders().get("kid")) || !subject.toString().equals(raw.get("sub")) ||
            !"mis-events".equals(raw.get("aud")) || !"mis-inventory-relay".equals(raw.get("service")) ||
            !environment.equals(raw.get("environment")) || !List.of().equals(raw.get("roles")) ||
            !canonical(raw.get("jti")) || iat>now || exp<=now || exp<=iat || exp-iat<60 || exp-iat>3600)
            throw new IllegalStateException("Wrong relay identity");
        return token;
    }
    private static boolean canonical(Object value) {
        if(!(value instanceof String text)) return false;
        try { var id=UUID.fromString(text); return id.toString().equals(text) && !id.equals(new UUID(0,0)); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    @Override public EventRelay.Acknowledgement publish(StockEvent event) {
        CompletableFuture<HttpResponse<byte[]>> response=null;
        var body=new LimitedBody();
        try {
            String token=credential();
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(6))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .header("X-Correlation-Id",(String)event.payload().get("traceId"))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event.payload()))).build();
            response=client.sendAsync(request,info -> body);
            // Bound the complete response, not only the arrival of HTTP headers.
            var received=response.get(6,TimeUnit.SECONDS);
            if(received.statusCode()!=200) throw new IllegalStateException("Worker did not confirm");
            Map<String,Object> ack=mapper.readValue(received.body(),new TypeReference<Map<String,Object>>(){});
            if(ack==null || !ack.keySet().equals(Set.of("eventId","sequence","accepted")) ||
                !event.eventId().toString().equals(ack.get("eventId")) || !Boolean.TRUE.equals(ack.get("accepted")) ||
                !(ack.get("sequence") instanceof Integer || ack.get("sequence") instanceof Long) ||
                ((Number)ack.get("sequence")).longValue()!=event.sequence()) throw new IllegalStateException("Invalid Worker acknowledgement");
            return new EventRelay.Acknowledgement(event.eventId(),event.sequence(),true);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Relay delivery interrupted");
        } catch(Exception unavailable) { throw new IllegalStateException("Relay delivery not confirmed"); }
        finally { if(response!=null && !response.isDone()) response.cancel(true); body.cancel(); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        volatile Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription=value; if(result.isDone()) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if(result.isDone()) return;
            for(var chunk:chunks) {
                if(chunk.remaining()>65536-bytes.size()) { cancel(); return; }
                byte[] data=new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        void cancel() {
            if(subscription!=null) subscription.cancel();
            result.completeExceptionally(new IOException("Response cancelled or too large"));
        }
    }
}
