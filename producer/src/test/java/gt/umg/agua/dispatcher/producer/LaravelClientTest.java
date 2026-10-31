package gt.umg.agua.dispatcher.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifica el trato con el monolito sin levantar el monolito: el servidor
 * simulado responde lo mismo que responde Laravel, y de paso comprueba que
 * la peticion sale con el token y con el verbo correctos.
 */
class LaravelClientTest {

    private static final String RESPUESTA = """
            {
              "data": [
                {
                  "llave": "EQA-000123",
                  "pagoId": 123,
                  "folio": "REC-0001",
                  "ronda": 1,
                  "correlationId": "7f1c2a9e-4b3d-4a1e-9c2f-0a1b2c3d4e5f",
                  "cliente": {
                    "codigo": "CLI-0042",
                    "nombre": "Walter Salguero",
                    "nit": "CF",
                    "email": "walter@example.com"
                  },
                  "contador": "CTR-0001",
                  "periodo": { "anio": 2026, "mes": 9 },
                  "formaPago": "TRANSFERENCIA",
                  "fechaPago": "2026-10-26T14:32:11-06:00",
                  "fechaEmision": "2026-10-26",
                  "total": "102.37",
                  "descripcion": "Servicio de agua 2026-09",
                  "detalle": [
                    { "rubro": "AGUA_POTABLE", "descripcion": "Consumo 2026-09", "monto": "102.37" }
                  ]
                }
              ]
            }
            """;

    private MockRestServiceServer monolito;
    private LaravelClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        monolito = MockRestServiceServer.bindTo(builder).build();
        cliente = new LaravelClient(builder, props());
    }

    @Test
    @DisplayName("reclama con POST, manda el token y mapea la respuesta al canonico")
    void reclamaYMapea() {
        monolito.expect(requestTo("http://monolito.test/api/integracion/pagos/reclamar?limite=50"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Integracion-Token", "token-de-prueba"))
                .andRespond(withSuccess(RESPUESTA, MediaType.APPLICATION_JSON));

        List<PagoMensaje> pagos = cliente.reclamar(50);

        monolito.verify();
        assertThat(pagos).hasSize(1);

        PagoMensaje pago = pagos.getFirst();
        assertThat(pago.llave()).isEqualTo("EQA-000123");
        assertThat(pago.formaPago()).isEqualTo(FormaPago.TRANSFERENCIA);
        assertThat(pago.total()).isEqualByComparingTo(new BigDecimal("102.37"));
    }

    @Test
    @DisplayName("devuelve lista vacia cuando no hay pagos por entregar")
    void sinPagosDevuelveListaVacia() {
        monolito.expect(requestTo("http://monolito.test/api/integracion/pagos/reclamar?limite=50"))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThat(cliente.reclamar(50)).isEmpty();
    }

    @Test
    @DisplayName("deja escapar el error del monolito para que el ciclo lo atrape")
    void propagaElErrorDelMonolito() {
        monolito.expect(requestTo("http://monolito.test/api/integracion/pagos/reclamar?limite=50"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> cliente.reclamar(50)).isInstanceOf(Exception.class);
    }

    private static ProducerProps props() {
        return new ProducerProps(
                new ProducerProps.Laravel("http://monolito.test/api/integracion", "token-de-prueba"),
                "pagos-envio",
                "facturas-emitir",
                new ProducerProps.Reclamo(30_000, 50));
    }
}