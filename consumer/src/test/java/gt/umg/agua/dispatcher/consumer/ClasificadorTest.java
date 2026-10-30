package gt.umg.agua.dispatcher.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La tabla de decision de los dos contratos. Cada caso de aqui sale de una
 * respuesta documentada en los yaml del inge, asi que si manana cambia una
 * decision, el contrato y esta prueba se leen juntos.
 */
class ClasificadorTest {

    private final Clasificador clasificador = new Clasificador(new ObjectMapper());

    @Test
    @DisplayName("un 201 es entregado y no se vuelve a intentar")
    void el201EsEntregado() {
        assertThat(clasificador.clasificar(201, "")).isEqualTo(Clasificacion.ENTREGADO);
    }

    @Test
    @DisplayName("el duplicado de cualquiera de los dos destinos cuenta como entregado")
    void elDuplicadoCuentaComoEntregado() {
        assertThat(clasificador.clasificar(409, "DUPLICATE_PAYMENT")).isEqualTo(Clasificacion.ENTREGADO);
        assertThat(clasificador.clasificar(409, "DOCUMENTO_DUPLICADO")).isEqualTo(Clasificacion.ENTREGADO);
    }

    @Test
    @DisplayName("la misma llave con otros datos es error nuestro: no se reintenta")
    void laLlaveReutilizadaEsDefinitiva() {
        assertThat(clasificador.clasificar(409, "IDEMPOTENCY_KEY_REUSED")).isEqualTo(Clasificacion.DEFINITIVO);
        assertThat(clasificador.clasificar(409, "DOCUMENTO_REUTILIZADO")).isEqualTo(Clasificacion.DEFINITIVO);
    }

    @Test
    @DisplayName("lo que el destino rechaza por los datos del pago es definitivo")
    void losRechazosDeValidacionSonDefinitivos() {
        assertThat(clasificador.clasificar(400, "")).isEqualTo(Clasificacion.DEFINITIVO);
        assertThat(clasificador.clasificar(403, "")).isEqualTo(Clasificacion.DEFINITIVO);
        assertThat(clasificador.clasificar(422, "AMOUNT_LIMIT_EXCEEDED")).isEqualTo(Clasificacion.DEFINITIVO);
    }

    @Test
    @DisplayName("el 429 y los 5xx se reintentan")
    void losFallosPasajerosSeReintentan() {
        assertThat(clasificador.clasificar(429, "")).isEqualTo(Clasificacion.REINTENTABLE);
        assertThat(clasificador.clasificar(500, "INTERNAL_ERROR")).isEqualTo(Clasificacion.REINTENTABLE);
        assertThat(clasificador.clasificar(502, "")).isEqualTo(Clasificacion.REINTENTABLE);
        assertThat(clasificador.clasificar(503, "SERVICE_UNAVAILABLE")).isEqualTo(Clasificacion.REINTENTABLE);
        assertThat(clasificador.clasificar(504, "")).isEqualTo(Clasificacion.REINTENTABLE);
    }

    @Test
    @DisplayName("sin respuesta se reintenta, nunca se descarta")
    void sinRespuestaSeReintenta() {
        // Es el caso del escenario TIMEOUT: no se sabe si el pago entro, y
        // descartarlo seria perderlo.
        assertThat(clasificador.clasificar(0, "")).isEqualTo(Clasificacion.REINTENTABLE);
    }

    @Test
    @DisplayName("lee el codigo de negocio del cuerpo de error")
    void leeElCodigoDelCuerpo() {
        FeignException e = error(409,
                "{\"status\":409,\"code\":\"DUPLICATE_PAYMENT\",\"message\":\"ya fue registrado\"}",
                Map.of());

        assertThat(clasificador.campoDeTexto(e, "code")).isEqualTo("DUPLICATE_PAYMENT");
        assertThat(clasificador.campoDeTexto(e, "message")).isEqualTo("ya fue registrado");
    }

    @Test
    @DisplayName("lee los campos en espanol del ERP")
    void leeLosCamposDelErp() {
        FeignException e = error(409,
                "{\"estado_http\":409,\"codigo_error\":\"DOCUMENTO_DUPLICADO\",\"mensaje\":\"ya fue contabilizado\"}",
                Map.of());

        assertThat(clasificador.campoDeTexto(e, "codigo_error")).isEqualTo("DOCUMENTO_DUPLICADO");
        assertThat(clasificador.campoDeTexto(e, "mensaje")).isEqualTo("ya fue contabilizado");
    }

    @Test
    @DisplayName("tolera un cuerpo que no es del contrato")
    void toleraUnCuerpoQueNoEsDelContrato() {
        // Un 502 de API Gateway no se parece a BankError, y un pago no puede
        // perderse por eso.
        FeignException deApiGateway = error(502, "{\"message\":\"Internal server error\"}", Map.of());
        assertThat(clasificador.campoDeTexto(deApiGateway, "code")).isEmpty();

        FeignException sinJson = error(503, "<html>503</html>", Map.of());
        assertThat(clasificador.campoDeTexto(sinJson, "code")).isEmpty();
    }

    @Test
    @DisplayName("respeta el Retry-After pero no mas alla del techo")
    void respetaElRetryAfterHastaElTecho() {
        Duration techo = Duration.ofSeconds(10);

        assertThat(clasificador.esperaPedida(error(503, "{}", Map.of("Retry-After", List.of("3"))), techo))
                .isEqualTo(Duration.ofSeconds(3));
        // El mensaje esta invisible en la cola mientras se espera: pasado el
        // techo es mejor soltarlo y que la cola lo devuelva.
        assertThat(clasificador.esperaPedida(error(429, "{}", Map.of("retry-after", List.of("600"))), techo))
                .isEqualTo(techo);
        assertThat(clasificador.esperaPedida(error(429, "{}", Map.of("Retry-After", List.of("manana"))), techo))
                .isEqualTo(Duration.ZERO);
        assertThat(clasificador.esperaPedida(error(500, "{}", Map.of()), techo))
                .isEqualTo(Duration.ZERO);
    }

    private FeignException error(int status, String cuerpo, Map<String, Collection<String>> cabeceras) {
        // El ultimo argumento es la plantilla de la peticion, que aqui no se
        // usa: lo unico que interesa de la respuesta es su codigo y su cuerpo.
        Request peticion = Request.create(Request.HttpMethod.POST, "/bank/v1/payments",
                Map.of(), new byte[0], StandardCharsets.UTF_8, null);
        Response respuesta = Response.builder()
                .status(status)
                .reason("")
                .request(peticion)
                .headers(cabeceras)
                .body(cuerpo, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("enviar", respuesta);
    }
}
