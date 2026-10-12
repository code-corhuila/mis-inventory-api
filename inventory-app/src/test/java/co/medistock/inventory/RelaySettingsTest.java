package co.medistock.inventory;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RelaySettingsTest {
    @TempDir Path folder;
    Map<String,String> environment() {
        return new HashMap<>(Map.of("RELAY_DB_URL","jdbc:postgresql://postgres:5432/medistock",
            "RELAY_DB_PASSWORD_FILE",folder.resolve("password").toString(),"RELAY_CREDENTIALS_PATH",folder.toString(),
            "RELAY_KEY_ID","dev-key","RELAY_ENVIRONMENT","dev","RELAY_SERVICE_SUBJECT",UUID.randomUUID().toString(),
            "RELAY_WORKER_ORIGIN","http://worker:8080"));
    }
    @Test void disabledByDefaultAndStrictOptIn() {
        assertFalse(RelaySettings.enabled(Map.of())); assertFalse(RelaySettings.enabled(Map.of("INVENTORY_RELAY_MODE","false")));
        assertTrue(RelaySettings.enabled(Map.of("INVENTORY_RELAY_MODE","true")));
        for(String value:List.of("1","TRUE","","yes")) assertThrows(IllegalArgumentException.class,() -> RelaySettings.enabled(Map.of("INVENTORY_RELAY_MODE",value)));
    }
    @Test void missingSettingsFailClosed() {
        for(String key:environment().keySet()) {
            var env=environment(); env.remove(key); assertThrows(IllegalArgumentException.class,() -> RelaySettings.from(env));
        }
    }
    @Test void rejectsUntrustedDatabaseUrlsAndSubjects() {
        for(String url:List.of("jdbc:mysql://host/db","jdbc:postgresql://user:password@host/db","jdbc:postgresql://host/db?user=admin","jdbc:postgresql://host/")) {
            var env=environment(); env.put("RELAY_DB_URL",url); assertThrows(IllegalArgumentException.class,() -> RelaySettings.from(env));
        }
        var env=environment(); env.put("RELAY_SERVICE_SUBJECT",new UUID(0,0).toString());
        assertThrows(IllegalArgumentException.class,() -> RelaySettings.from(env));
    }
    @Test void fileSecretPreservesSpacesAndOnlyRemovesFinalNewline() throws Exception {
        var settings=RelaySettings.from(environment());
        Files.writeString(settings.passwordFile()," synthetic password \r\n"); assertEquals(" synthetic password ",settings.password());
        for(String value:List.of("","\n","first\nsecond","x".repeat(4097))) {
            Files.writeString(settings.passwordFile(),value); assertThrows(IllegalArgumentException.class,settings::password);
        }
        Files.write(settings.passwordFile(),new byte[]{(byte)255}); assertThrows(IllegalArgumentException.class,settings::password);
        Files.delete(settings.passwordFile()); assertThrows(IllegalArgumentException.class,settings::password);
    }
    @Test void refusesApplicationLoginAndAdministrativeFlags() {
        var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of("username","inventory_app"));
        assertThrows(IllegalStateException.class,() -> RelayRuntime.verifyLogin(jdbc));
        for(String flag:List.of("rolsuper","rolcreatedb","rolcreaterole","rolreplication","rolbypassrls")) {
            var identity=new HashMap<String,Object>(); identity.put("username","inventory_relay_app");
            for(String key:List.of("rolsuper","rolcreatedb","rolcreaterole","rolreplication","rolbypassrls")) identity.put(key,key.equals(flag));
            org.mockito.Mockito.when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString())).thenReturn(identity);
            assertThrows(IllegalStateException.class,() -> RelayRuntime.verifyLogin(jdbc));
        }
    }
}
