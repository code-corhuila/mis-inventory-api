package co.medistock.inventory;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

public record RelaySettings(String databaseUrl,Path passwordFile,Path credentials,String keyId,
    String environment,UUID subject,URI workerOrigin) {
    public static boolean enabled(Map<String,String> env) {
        String mode=env.getOrDefault("INVENTORY_RELAY_MODE","false");
        if(!Set.of("true","false").contains(mode)) throw new IllegalArgumentException("Invalid relay mode");
        return mode.equals("true");
    }
    public static RelaySettings from(Map<String,String> env) {
        String url=required(env,"RELAY_DB_URL");
        if(!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException("PostgreSQL URL required");
        URI database=URI.create(url.substring(5));
        if(database.getHost()==null || database.getUserInfo()!=null || database.getQuery()!=null ||
            database.getFragment()!=null || database.getPath().length()<2) throw new IllegalArgumentException("Invalid relay database URL");
        String environment=required(env,"RELAY_ENVIRONMENT");
        if(!Set.of("dev","qa","main").contains(environment)) throw new IllegalArgumentException("Invalid relay environment");
        String subject=required(env,"RELAY_SERVICE_SUBJECT"); UUID id=UUID.fromString(subject);
        if(!id.toString().equals(subject) || id.equals(new UUID(0,0))) throw new IllegalArgumentException("Canonical relay subject required");
        return new RelaySettings(url,Path.of(required(env,"RELAY_DB_PASSWORD_FILE")),Path.of(required(env,"RELAY_CREDENTIALS_PATH")),
            required(env,"RELAY_KEY_ID"),environment,id,URI.create(required(env,"RELAY_WORKER_ORIGIN")));
    }
    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);
        if(value==null || value.isBlank()) throw new IllegalArgumentException("Missing "+key);
        return value;
    }
    public String password() {
        try(var input=Files.newInputStream(passwordFile)) {
            byte[] bytes=input.readNBytes(4097);
            if(bytes.length>4096) throw new IllegalArgumentException("Secret too large");
            String value=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("\\r?\\n$","");
            if(value.isBlank() || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Single-line secret required");
            return value;
        } catch(java.io.IOException missing) { throw new IllegalArgumentException("Cannot read relay password file"); }
    }
}
