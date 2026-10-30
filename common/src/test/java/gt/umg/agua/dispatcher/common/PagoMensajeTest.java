package gt.umg.agua.dispatcher.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Esta prueba es el contrato con el monolito: el JSON de abajo esta copiado
 * de lo que devuelve {@code GET /api/integracion/pagos/reclamar}. Si Laravel
 * cambia un nombre de campo, aqui se nota antes de que un pago se pierda en
 * produccion.
 */
class PagoMensajeTest {

    private static final String CANONICO = """
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
                {
                  "rubro": "AGUA_POTABLE",
                  "descripcion": "Consumo 2026-09",
                  "monto": "102.37"
                }
              ]
            }
            """;

    /**
     * Configurado como lo deja Spring Boot: fechas en texto ISO y no como
     * numero de epoca, que es lo que hace un ObjectMapper recien creado.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** El de produccion: application.yml le fija spring.jackson.time-zone. */
    private final ObjectMapper enHoraDeGuatemala = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultTimeZone(TimeZone.getTimeZone("America/Guatemala"))
            .build();

    @Test
    @DisplayName("lee el canonico que emite el monolito")
    void deserializaElCanonico() throws Exception {
        PagoMensaje pago = mapper.readValue(CANONICO, PagoMensaje.class);

        assertThat(pago.llave()).isEqualTo("EQA-000123");
        assertThat(pago.pagoId()).isEqualTo(123L);
        assertThat(pago.folio()).isEqualTo("REC-0001");
        assertThat(pago.ronda()).isEqualTo(1);
        assertThat(pago.cliente().nit()).isEqualTo("CF");
        assertThat(pago.contador()).isEqualTo("CTR-0001");
        assertThat(pago.periodo()).isEqualTo(new PagoMensaje.Periodo(2026, 9));
        assertThat(pago.formaPago()).isEqualTo(FormaPago.TRANSFERENCIA);
        assertThat(pago.detalle()).singleElement()
                .extracting(PagoMensaje.LineaDetalle::rubro)
                .isEqualTo(Rubro.AGUA_POTABLE);
    }

    @Test
    @DisplayName("conserva la hora y la zona del cobro")
    void conservaLaZonaHoraria() throws Exception {
        PagoMensaje pago = mapper.readValue(CANONICO, PagoMensaje.class);

        assertThat(pago.fechaPago()).isEqualTo(OffsetDateTime.parse("2026-10-26T14:32:11-06:00"));
        assertThat(pago.fechaEmision()).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    @DisplayName("lee los montos como BigDecimal exacto, nunca como flotante")
    void leeLosMontosSinPerderPrecision() throws Exception {
        PagoMensaje pago = mapper.readValue(CANONICO, PagoMensaje.class);

        assertThat(pago.total()).isEqualByComparingTo(new BigDecimal("102.37"));
        assertThat(Dinero.aCentavos(pago.total())).isEqualTo(10237);
        assertThat(ValidadorPago.validar(pago)).isEmpty();
    }

    @Test
    @DisplayName("escribe los montos como texto, nunca como numero JSON")
    void escribeLosMontosComoTexto() throws Exception {
        PagoMensaje pago = mapper.readValue(CANONICO, PagoMensaje.class);

        String json = mapper.writeValueAsString(pago);

        // Con comillas. Sin ellas el monto seria un numero de punto flotante
        // para cualquiera que lea la cola con otro lenguaje.
        assertThat(json).contains("\"total\":\"102.37\"");
        assertThat(json).contains("\"monto\":\"102.37\"");
    }

    @Test
    @DisplayName("con la zona de Guatemala, la fecha ida y vuelta conserva el -06:00")
    void conservaElDesfaseIdaYVuelta() throws Exception {
        PagoMensaje pago = enHoraDeGuatemala.readValue(CANONICO, PagoMensaje.class);

        String json = enHoraDeGuatemala.writeValueAsString(pago);

        // Un cobro de las 19:00 reescrito a UTC cae al dia siguiente, y
        // entonces fechaPago y fechaEmision dejan de cuadrar para el ERP.
        assertThat(json).contains("\"fechaPago\":\"2026-10-26T14:32:11-06:00\"");
        assertThat(json).contains("\"fechaEmision\":\"2026-10-26\"");
    }

    @Test
    @DisplayName("sin zona configurada, Jackson reescribe la fecha a UTC desde la lectura")
    void sinZonaConfiguradaLaFechaSeVaAUtc() throws Exception {
        // Documenta por que application.yml fija spring.jackson.time-zone: el
        // ajuste no ocurre al escribir, ocurre ya al leer el mensaje.
        PagoMensaje pago = mapper.readValue(CANONICO, PagoMensaje.class);

        assertThat(mapper.writeValueAsString(pago)).contains("\"fechaPago\":\"2026-10-26T20:32:11Z\"");
    }

    @Test
    @DisplayName("no se rompe si el monolito agrega un campo nuevo")
    void toleraCamposDesconocidos() throws Exception {
        String conCampoExtra = CANONICO.replace(
                "\"pagoId\": 123,",
                "\"pagoId\": 123,\n  \"campoQueTodaviaNoExiste\": \"x\",");

        PagoMensaje pago = mapper.readValue(conCampoExtra, PagoMensaje.class);

        assertThat(pago.pagoId()).isEqualTo(123L);
    }
}
