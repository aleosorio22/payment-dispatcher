package gt.umg.agua.dispatcher.facturador;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * El trato con Digifact: pedir el token y mandar documentos a certificar.
 *
 * <p>El token dura un mes, asi que se guarda y se reusa. Cuando caduca,
 * Digifact responde 401 y se pide uno nuevo en el acto; no hace falta llevar
 * la cuenta de cuando vence.
 */
@Component
public class ClienteDigifact {

    private static final Logger log = LoggerFactory.getLogger(ClienteDigifact.class);

    private final RestClient http;
    private final FacturadorProps props;
    private final ObjectMapper mapper;

    /** El token vigente. Volatile porque varios mensajes se atienden a la vez. */
    private volatile String token;

    public ClienteDigifact(RestClient.Builder builder, FacturadorProps props) {
        this.props = props;
        this.http = builder.baseUrl(props.digifact().baseUrl()).build();
        // Digifact mete saltos de linea sin escapar dentro de los textos de su
        // JSON de error. Un parser estricto se atraganta justo cuando mas
        // falta hace leer el mensaje: cuando algo salio mal.
        this.mapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }

    /**
     * Manda el documento y devuelve como le fue.
     *
     * @throws RuntimeException si Digifact no contesta; eso si se reintenta
     */
    public Certificacion certificar(Nuc nuc) {
        try {
            return interpretar(enviar(nuc, token()));
        } catch (HttpClientErrorException.Unauthorized caduco) {
            // El token se vencio mientras tanto. Uno nuevo y se repite.
            log.info("El token de Digifact caduco, pidiendo otro");
            token = null;
            return interpretar(enviar(nuc, token()));
        }
    }

    private String enviar(Nuc nuc, String token) {
        String cuerpo = escribir(nuc);

        return http.post()
                .uri(constructor -> constructor
                        .path("/v2/transform/nuc_json")
                        .queryParam("TAXID", props.digifact().taxId())
                        .queryParam("FORMAT", "XML")
                        .queryParam("USERNAME", props.digifact().usuarioCorto())
                        .build())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .body(cuerpo)
                .retrieve()
                .body(String.class);
    }

    /**
     * Digifact contesta 200 tanto cuando certifica como cuando rechaza; lo que
     * cambia es el cuerpo. La unica senal confiable es el numero de
     * autorizacion: si viene, el documento existe para la SAT.
     */
    private Certificacion interpretar(String respuesta) {
        JsonNode raiz = leer(respuesta);
        String autorizacion = texto(raiz, "authNumber");

        if (!autorizacion.isBlank()) {
            return Certificacion.lograda(
                    autorizacion,
                    texto(raiz, "batch"),
                    texto(raiz, "serial"),
                    texto(raiz, "enrolledTimeStamp"));
        }

        String detalle = primeraLineaUtil(texto(raiz, "description"));
        String mensaje = texto(raiz, "message");
        return Certificacion.rechazada(
                raiz.path("code").asInt(),
                detalle.isBlank() ? mensaje : mensaje + ": " + detalle);
    }

    /**
     * El campo description trae media pagina de diagnostico. La linea que
     * sirve es la que nombra la validacion que fallo.
     */
    private String primeraLineaUtil(String descripcion) {
        for (String linea : descripcion.split("\\R")) {
            String limpia = linea.trim();
            if (limpia.startsWith("FEL_") || limpia.contains("VALIDACION") || limpia.startsWith("Error")) {
                return limpia;
            }
        }
        return descripcion.lines().map(String::trim).filter(l -> !l.isBlank()).findFirst().orElse("");
    }

    private String token() {
        String vigente = token;
        if (vigente != null) {
            return vigente;
        }

        String nuevo = texto(leer(http.post()
                .uri("/login/get_token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(credenciales())
                .retrieve()
                .body(String.class)), "Token");

        if (nuevo.isBlank()) {
            throw new IllegalStateException("Digifact no devolvio token");
        }

        token = nuevo;
        return nuevo;
    }

    private String credenciales() {
        return """
                {"Username":"%s","Password":"%s"}"""
                .formatted(props.digifact().usuario(), props.digifact().password());
    }

    private String escribir(Nuc nuc) {
        try {
            return mapper.writeValueAsString(nuc);
        } catch (Exception imposible) {
            throw new IllegalStateException("No se pudo escribir el NUC", imposible);
        }
    }

    private JsonNode leer(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta ilegible de Digifact: " + e.getMessage(), e);
        }
    }

    private String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isTextual() ? valor.asText() : "";
    }
}
