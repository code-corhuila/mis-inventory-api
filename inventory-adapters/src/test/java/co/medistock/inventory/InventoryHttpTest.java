package co.medistock.inventory;

import co.medistock.inventory.adapters.*;
import co.medistock.inventory.core.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryHttpTest {
    Entries entries=mock(Entries.class);
    Exits exits=mock(Exits.class);
    InventoryRead read=mock(InventoryRead.class);
    StockEvents stockEvents=mock(StockEvents.class);
    ExpiryAlerts alerts=mock(ExpiryAlerts.class);
    ExpiryAlertRead alertRead=mock(ExpiryAlertRead.class);
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    UUID actor=UUID.randomUUID(),id=UUID.randomUUID(),lot=UUID.randomUUID(),med=UUID.randomUUID(),supplier=UUID.randomUUID();
    Entry entry=new Entry(id,lot,med,supplier,"LOT-1",LocalDate.of(2099,1,1),10,"PENDING",actor,Instant.parse("2026-10-11T12:00:00Z"));
    Exits.Exit exit=new Exits.Exit(id,lot,med,supplier,"LOT-1",2,"Entrega",8,actor,entry.createdAt());
    MockMvc http;
    String entryBody() {
        return "{\"medicationId\":\""+med+"\",\"supplierId\":\""+supplier+"\",\"lotNumber\":\" lot-1 \",\"expiresOn\":\"2099-01-01\",\"quantity\":10}";
    }
    @BeforeEach void setup() {
        // Principal sintetico y puertos mock: no valida firmas, filtros ni autorizacion.
        var jwt=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(actor.toString()).build();
        http=MockMvcBuilders.standaloneSetup(new EntryApi(entries,jdbc),new ExitApi(exits),new InventoryReadApi(read),new StockEventApi(stockEvents),new ExpiryAlertApi(alerts,alertRead))
            .setControllerAdvice(new Errors()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType()==Jwt.class; }
                public Object resolveArgument(MethodParameter p,ModelAndViewContainer c,NativeWebRequest w,WebDataBinderFactory b) { return jwt; }
            }).build();
    }
    @Test void entryCreateAndReplayReturnCorrectStatusAndLocation() throws Exception {
        var normalized=new NewEntry(med,supplier,"LOT-1",entry.expiresOn(),10);
        when(entries.prepare(actor,"prepare-http",normalized,"synthetic","http-trace"))
            .thenReturn(new Entries.Result(entry,true),new Entries.Result(entry,false));
        for(int status:new int[]{201,200}) http.perform(post("/api/v1/entries").header("Idempotency-Key","prepare-http")
            .requestAttr("traceId","http-trace").contentType("application/json").content(entryBody()))
            .andExpect(status().is(status)).andExpect(header().string("Location","/api/v1/entries/"+id))
            .andExpect(jsonPath("$.lotNumber").value("LOT-1"));
        verify(entries,times(2)).prepare(actor,"prepare-http",normalized,"synthetic","http-trace");
    }
    @Test void transitionsAllowAbsentAndEmptyBodiesButRejectData() throws Exception {
        when(entries.transition(actor,"transition-http",id,"activate","http-trace")).thenReturn(entry);
        http.perform(post("/api/v1/entries/"+id+"/activate").header("Idempotency-Key","transition-http").requestAttr("traceId","http-trace"))
            .andExpect(status().isOk());
        http.perform(post("/api/v1/entries/"+id+"/activate").header("Idempotency-Key","transition-http").requestAttr("traceId","http-trace")
            .contentType("application/json").content("{}")).andExpect(status().isOk());
        http.perform(post("/api/v1/entries/"+id+"/cancel").requestAttr("traceId","http-trace")
            .contentType("application/json").content("{\"quantity\":2}")).andExpect(status().isBadRequest());
        verify(entries,times(2)).transition(actor,"transition-http",id,"activate","http-trace"); verifyNoMoreInteractions(entries);
    }
    @Test void exitCreateAndReplayForwardNormalizedBody() throws Exception {
        when(exits.create(actor,"exit-http",new NewExit(lot,2,"Entrega"),"http-trace"))
            .thenReturn(new Exits.Result(exit,true),new Exits.Result(exit,false));
        String body="{\"lotId\":\""+lot+"\",\"quantity\":2,\"reason\":\" Entrega \"}";
        for(int code:new int[]{201,200}) http.perform(post("/api/v1/exits").header("Idempotency-Key","exit-http")
            .requestAttr("traceId","http-trace").contentType("application/json").content(body))
            .andExpect(status().is(code)).andExpect(header().string("Location","/api/v1/exits/"+id))
            .andExpect(jsonPath("$.remainingQuantity").value(8));
    }
    @Test void entryAndExitReadsUseCanonicalIdentifiers() throws Exception {
        when(entries.find(id)).thenReturn(entry); when(exits.find(id)).thenReturn(exit);
        http.perform(get("/api/v1/entries/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        http.perform(get("/api/v1/exits/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
        http.perform(get("/api/v1/entries/not-a-uuid")).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/exits/not-a-uuid")).andExpect(status().isBadRequest());
        verify(entries).find(id); verify(exits).find(id); verifyNoMoreInteractions(entries,exits);
    }
    @Test void stockAndMovementFiltersForwardWithPageEnvelope() throws Exception {
        var stockQuery=new InventoryQuery(1,5,med,null,null,false,null,null);
        var movementQuery=new InventoryQuery(1,20,null,null,lot,null,LocalDate.of(2026,10,11),null);
        when(read.stock(stockQuery)).thenReturn(new InventoryRead.Page<>(List.of(),new InventoryRead.Meta(1,5,0)));
        when(read.movements(movementQuery)).thenReturn(new InventoryRead.Page<>(List.of(),new InventoryRead.Meta(1,20,0)));
        http.perform(get("/api/v1/stock").param("medicationId",med.toString()).param("expired","false").param("limit","5"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.limit").value(5)).andExpect(jsonPath("$.data").isArray());
        http.perform(get("/api/v1/movements").param("lotId",lot.toString()).param("from","2026-10-11"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0));
        verify(read).stock(stockQuery); verify(read).movements(movementQuery);
    }
    @Test void invalidJsonBodiesAndFiltersDoNotCallPorts() throws Exception {
        http.perform(post("/api/v1/entries").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest());
        http.perform(post("/api/v1/exits").contentType("application/json").content("{\"lotId\":\""+lot+"\",\"reason\":\"Entrega\"}"))
            .andExpect(status().isBadRequest());
        for(String path:List.of("/api/v1/stock","/api/v1/movements")) {
            http.perform(get(path).param("page","1","2")).andExpect(status().isBadRequest());
            http.perform(get(path).param("unknown","yes")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(entries,exits,read);
    }
    @Test void errorsKeepCorrelationAndHideDatabaseDetails() throws Exception {
        when(exits.find(id)).thenThrow(new DataAccessResourceFailureException("private-db-details"));
        http.perform(get("/api/v1/exits/"+id).requestAttr("traceId","http-trace"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("Dependencia no disponible.")).andExpect(jsonPath("$.traceId").value("http-trace"));
        when(entries.find(id)).thenThrow(new InventoryFailure(404,"NOT_FOUND","Entrada inexistente.",null));
        http.perform(get("/api/v1/entries/"+id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
    @Test void healthChecksEveryOwnedPersistenceTable() throws Exception {
        http.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        verify(jdbc,times(9)).queryForObject(anyString(),eq(Long.class));
        verifyNoInteractions(entries,exits,read);
    }
    @Test void expiryEvaluationAndReadExposeOwnerResults() throws Exception {
        when(alerts.evaluate(actor,lot,30)).thenReturn(Optional.empty());
        http.perform(post("/api/v1/internal/alerts/expiry/evaluate").contentType("application/json")
            .content("{\"lotId\":\""+lot+"\",\"windowDays\":30}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.lotId").value(lot.toString()))
            .andExpect(jsonPath("$.alertPresent").value(false)).andExpect(jsonPath("$.alert").value(org.hamcrest.Matchers.nullValue()));
        var query=new ExpiryQuery(2,1,lot,"RESOLVED","EXPIRED");
        when(alertRead.list(query)).thenReturn(new InventoryRead.Page<>(List.of(),new InventoryRead.Meta(2,1,1)));
        http.perform(get("/api/v1/alerts/expiry").param("page","2").param("limit","1").param("lotId",lot.toString())
            .param("state","RESOLVED").param("severity","EXPIRED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data").isEmpty());
        verify(alerts).evaluate(actor,lot,30); verify(alertRead).list(query);
    }
    @Test void expiryRejectsClientBalancesDatesAndNonIntegerWindows() throws Exception {
        for(String value:List.of("0","366","\"30\"","30.5","true","null"))
            http.perform(post("/api/v1/internal/alerts/expiry/evaluate").contentType("application/json")
                .content("{\"lotId\":\""+lot+"\",\"windowDays\":"+value+"}")).andExpect(status().isBadRequest());
        for(String body:List.of("{}","{\"lotId\":\"bad\",\"windowDays\":30}",
            "{\"lotId\":\""+lot+"\",\"windowDays\":30,\"onHandQuantity\":10}",
            "{\"lotId\":\""+lot+"\",\"windowDays\":30,\"evaluatedOn\":\"2099-01-01\"}"))
            http.perform(post("/api/v1/internal/alerts/expiry/evaluate").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(alerts,alertRead);
    }
    @Test void expiryDependencyErrorKeepsTraceAndHidesDetails() throws Exception {
        when(alertRead.list(any())).thenThrow(new DataAccessResourceFailureException("private-db-details"));
        http.perform(get("/api/v1/alerts/expiry").requestAttr("traceId","expiry-http"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.traceId").value("expiry-http"))
            .andExpect(jsonPath("$.message").value("Dependencia no disponible."));
    }
    @Test void ownerEventRequestAndReceiptMapWithoutExtraFields() throws Exception {
        var body=new HashMap<String,Object>();
        body.putAll(Map.of("eventId",id.toString(),"movementId",id.toString(),"lotId",lot.toString(),"medicationId",med.toString(),
            "supplierId",supplier.toString(),"eventType","inventory.stock.changed","schemaVersion",1,"sequence",1,
            "occurredAt","2026-10-11T12:00:00Z","traceId","original-event"));
        body.putAll(Map.of("movementType","ENTRY","expiresOn","2099-01-01","quantity",10,"delta",10,"onHandQuantity",10,"entryId",id.toString()));
        body.put("exitId",null);
        when(stockEvents.apply(eq(actor),any(StockEvent.class))).thenReturn(new StockEvents.Result(id,1,true));
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        http.perform(post("/api/v1/internal/events/stock-changed").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.eventId").value(id.toString()))
            .andExpect(jsonPath("$.sequence").value(1)).andExpect(jsonPath("$.applied").value(true));
        body.put("unknown",true);
        http.perform(post("/api/v1/internal/events/stock-changed").contentType("application/json").content(mapper.writeValueAsString(body)))
            .andExpect(status().isBadRequest());
        verify(stockEvents).apply(eq(actor),any(StockEvent.class)); verifyNoMoreInteractions(stockEvents);
    }
}
