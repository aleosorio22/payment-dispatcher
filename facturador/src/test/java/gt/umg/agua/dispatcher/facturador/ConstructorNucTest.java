package gt.umg.agua.dispatcher.facturador;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Esta prueba es el contrato con Digifact. Cada caso sale de algo que su API
 * rechazo de verdad: el establecimiento equivocado, la frase partida en dos,
 * la adenda ausente. Si alguien "limpia" uno de estos detalles, la factura
 * deja de emitirse y conviene enterarse aqui.
 */
class ConstructorNucTest {

    private final ConstructorNuc constructor = new ConstructorNuc(DatosDePrueba.props());
    private final PagoMensaje pago = DatosDePrueba.unPago();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("arma una factura con los datos fiscales del emisor")
    void armaLaCabecera() {
        Nuc nuc = constructor.construir(pago);

        assertThat(nuc.version()).isEqualTo("1.00");
        assertThat(nuc.countryCode()).isEqualTo("GT");
        assertThat(nuc.header().docType()).isEqualTo("FACT");
        assertThat(nuc.header().currency()).isEqualTo("GTQ");
        assertThat(nuc.seller().taxID()).isEqualTo("5888492");
        assertThat(nuc.seller().name()).isEqualTo("PEREZ PEREZ, ISMAR BLADIMIR");
    }

    @Test
    @DisplayName("la fecha de emision es la del cobro, con su desfase")
    void laFechaEsLaDelCobro() {
        Nuc nuc = constructor.construir(pago);

        // No la de hoy ni UTC: la SAT compara contra la fecha del documento, y
        // un cobro de la noche reescrito a UTC cae al dia siguiente.
        assertThat(nuc.header().issuedDateTime()).isEqualTo("2026-10-31T09:15:00-06:00");
    }

    @Test
    @DisplayName("el establecimiento no es el 1 sino el que tiene el emisor en el RTU")
    void usaElEstablecimientoDelRtu() {
        Nuc nuc = constructor.construir(pago);

        // Digifact responde "el establecimiento no existe" si se asume el 1.
        assertThat(nuc.seller().branchInfo().code()).isEqualTo("2");
    }

    @Test
    @DisplayName("las dos mitades de la frase van unidas por el mismo numero")
    void laFraseViajaCompleta() {
        Nuc nuc = constructor.construir(pago);
        List<Nuc.Atributo> frase = nuc.seller().additionlInfo();

        assertThat(frase).extracting(Nuc.Atributo::name)
                .containsExactly("TipoFrase", "Escenario");
        assertThat(frase).extracting(Nuc.Atributo::value)
                .containsExactly("1", "2");
        // El "data" es el numero de frase que agrupa al tipo con su escenario.
        // Si cada uno lleva el suyo, Digifact ve dos frases a medias y
        // contesta "no se encuentran completos los datos de una frase".
        assertThat(frase).extracting(Nuc.Atributo::data)
                .containsExactly("1", "1");
    }

    @Test
    @DisplayName("desglosa el IVA de cada linea y el total cuadra")
    void desglosaElIva() {
        Nuc nuc = constructor.construir(pago);
        Nuc.Item item = nuc.items().getFirst();

        assertThat(item.price()).isEqualTo("50.400000");
        assertThat(item.totals().totalItem()).isEqualTo("50.400000");
        assertThat(item.taxes().tax().getFirst().taxableAmount()).isEqualTo("45.000000");
        assertThat(item.taxes().tax().getFirst().amount()).isEqualTo("5.400000");
        assertThat(nuc.totals().grandTotal().invoiceTotal()).isEqualTo("50.400000");
        assertThat(nuc.totals().totalTaxes().totalTax().getFirst().amount()).isEqualTo("5.400000");
    }

    @Test
    @DisplayName("una boleta con mora lleva una linea por rubro")
    void unaLineaPorRubro() {
        PagoMensaje conMora = new PagoMensaje(
                pago.llave(), pago.pagoId(), pago.folio(), pago.ronda(), pago.correlationId(),
                pago.cliente(), pago.contador(), pago.periodo(), pago.formaPago(),
                pago.fechaPago(), pago.fechaEmision(), new BigDecimal("62.72"), pago.descripcion(),
                List.of(
                        new PagoMensaje.LineaDetalle(
                                gt.umg.agua.dispatcher.common.Rubro.AGUA_POTABLE, "Consumo", new BigDecimal("50.40")),
                        new PagoMensaje.LineaDetalle(
                                gt.umg.agua.dispatcher.common.Rubro.MORA, "Recargo por mora", new BigDecimal("12.32"))));

        Nuc nuc = constructor.construir(conMora);

        assertThat(nuc.items()).hasSize(2);
        assertThat(nuc.items()).extracting(Nuc.Item::number).containsExactly("1", "2");
        // El impuesto total es la suma de los de cada linea, que es lo que la
        // SAT compara; calcularlo sobre el gran total podria diferir un centavo.
        assertThat(nuc.totals().totalTaxes().totalTax().getFirst().amount()).isEqualTo("6.720000");
        assertThat(nuc.totals().grandTotal().invoiceTotal()).isEqualTo("62.720000");
    }

    @Test
    @DisplayName("sin NIT propio, el receptor es consumidor final")
    void elVecinoSinNitEsConsumidorFinal() {
        Nuc nuc = constructor.construir(pago);

        assertThat(nuc.buyer().taxID()).isEqualTo("CF");
        assertThat(nuc.buyer().name()).isEqualTo("CONSUMIDOR FINAL");
    }

    @Test
    @DisplayName("con NIT propio va el nombre del vecino")
    void elVecinoConNitLlevaSuNombre() {
        PagoMensaje conNit = new PagoMensaje(
                pago.llave(), pago.pagoId(), pago.folio(), pago.ronda(), pago.correlationId(),
                new PagoMensaje.Cliente("CLI-0042", "Walter Salguero", "1234567-8", "walter@example.com"),
                pago.contador(), pago.periodo(), pago.formaPago(), pago.fechaPago(),
                pago.fechaEmision(), pago.total(), pago.descripcion(), pago.detalle());

        Nuc nuc = constructor.construir(conNit);

        assertThat(nuc.buyer().taxID()).isEqualTo("1234567-8");
        assertThat(nuc.buyer().name()).isEqualTo("Walter Salguero");
    }

    @Test
    @DisplayName("en pruebas la factura va al buzon configurado, no al del vecino")
    void elCorreoDePruebasGanaAlDelVecino() {
        Nuc nuc = constructor.construir(pago);

        assertThat(nuc.buyer().contact().emailList().email())
                .containsExactly("comatecgt@gmail.com");
    }

    @Test
    @DisplayName("sin buzon de pruebas, la factura le llega al vecino")
    void sinBuzonDePruebasVaAlVecino() {
        Nuc nuc = new ConstructorNuc(DatosDePrueba.props("")).construir(pago);

        assertThat(nuc.buyer().contact().emailList().email())
                .containsExactly("walter@example.com");
    }

    @Test
    @DisplayName("lleva la adenda, que Digifact exige aunque no se use")
    void llevaLaAdenda() {
        Nuc nuc = constructor.construir(pago);
        Nuc.Adenda adenda = nuc.additionalDocumentInfo().additionalInfo().getFirst();

        assertThat(adenda.type()).isEqualTo("ADENDA");
        // Sin este bloque, Digifact responde "No se encuentra el elemento
        // AdditionalDocumentInfo" y no llega ni a validar los datos fiscales.
        assertThat(adenda.aditionalData().data()).isNotEmpty();
        assertThat(adenda.aditionalData().data().getFirst().info())
                .extracting(Nuc.Atributo::value)
                .contains("Recibo REC-0215 periodo 2026-09");
    }

    @Test
    @DisplayName("el JSON respeta los nombres de Digifact, dedazos incluidos")
    void respetaLosNombresDelProveedor() throws Exception {
        String json = mapper.writeValueAsString(constructor.construir(pago));

        // Estos tres estan mal escritos en su contrato. Corregirlos aqui seria
        // dejar de hablar su idioma.
        assertThat(json).contains("\"AdditionlInfo\"");
        assertThat(json).contains("\"AditionalData\"");
        assertThat(json).contains("\"AditionalInfo\"");
        // Y estos tienen que viajar en null, no desaparecer.
        assertThat(json).contains("\"ThirdParties\":null");
        assertThat(json).contains("\"Discounts\":null");
    }
}
