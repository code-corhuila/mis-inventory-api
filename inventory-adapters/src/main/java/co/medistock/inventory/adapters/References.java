package co.medistock.inventory.adapters;

import co.medistock.inventory.core.InventoryFailure;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class References {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String catalog, suppliers;
    public References(@Value("${inventory.catalog-url}") String catalog, @Value("${inventory.suppliers-url}") String suppliers) {
        for (String value : new String[]{catalog,suppliers}) {
            URI uri = URI.create(value);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException("Endpoint interno invalido.");
            }
        }
        this.catalog = catalog; this.suppliers = suppliers;
    }
    public void verify(UUID medication, UUID supplier, String token, String trace) {
        verify(catalog + "/api/v1/medications/" + medication, "medicationId", token, trace);
        verify(suppliers + "/api/v1/suppliers/" + supplier, "supplierId", token, trace);
    }
    private void verify(String url, String field, String token, String trace) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer " + token).header("X-Correlation-Id", trace).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) throw new InventoryFailure(422,"BUSINESS_RULE_VIOLATION","Referencia inexistente.",field);
            if (response.statusCode() != 200) throw unavailable();
            if (!mapper.readTree(response.body()).path("active").asBoolean(false)) {
                throw new InventoryFailure(422,"BUSINESS_RULE_VIOLATION","Referencia inactiva.",field);
            }
        } catch (InventoryFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception e) { throw unavailable(); }
    }
    private InventoryFailure unavailable() { return new InventoryFailure(503,"SERVICE_UNAVAILABLE","Dependencia no disponible.",null); }
}
