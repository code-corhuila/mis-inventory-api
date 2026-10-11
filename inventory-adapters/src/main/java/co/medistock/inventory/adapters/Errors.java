package co.medistock.inventory.adapters;

import co.medistock.inventory.core.InventoryFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class Errors {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public Map<String, Object> body(HttpServletRequest request, String code, String message, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", code); result.put("message", message); result.put("traceId", request.getAttribute("traceId"));
        if (field != null) result.put("details", List.of(Map.of("field", field, "message", "Valor invalido.")));
        return result;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body(request, code, message, null)));
    }

    @ExceptionHandler(InventoryFailure.class)
    public ResponseEntity<?> business(HttpServletRequest request, InventoryFailure error) {
        return ResponseEntity.status(error.status()).body(body(request, error.code(), error.getMessage(), error.field()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> json(HttpServletRequest request) { return business(request, InventoryFailure.invalid("body")); }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> type(HttpServletRequest request, MethodArgumentTypeMismatchException error) {
        return business(request, InventoryFailure.invalid(error.getName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> missing(HttpServletRequest request) {
        return business(request, new InventoryFailure(404, "NOT_FOUND", "Ruta inexistente.", null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> forbidden(HttpServletRequest request) {
        return business(request, new InventoryFailure(403, "FORBIDDEN", "Permisos insuficientes.", null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> database(HttpServletRequest request) {
        return business(request, new InventoryFailure(503, "SERVICE_UNAVAILABLE", "Dependencia no disponible.", null));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(HttpServletRequest request) {
        return business(request, new InventoryFailure(405, "METHOD_NOT_ALLOWED", "Metodo no permitido.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(HttpServletRequest request) {
        return business(request, new InventoryFailure(500, "INTERNAL_ERROR", "Error interno.", null));
    }
}
