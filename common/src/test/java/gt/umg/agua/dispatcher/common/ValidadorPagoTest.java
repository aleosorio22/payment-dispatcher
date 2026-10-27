package gt.umg.agua.dispatcher.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ValidadorPagoTest {

    @Test
    @DisplayName("acepta un pago bien formado")
    void aceptaUnPagoSano() {
        assertThat(ValidadorPago.validar(pagoDe("102.37", linea(Rubro.AGUA_POTABLE, "Consumo 2026-09", "102.37"))))
                .isEmpty();
    }

    @Test
    @DisplayName("rechaza un total que no es positivo")
    void rechazaTotalInvalido() {
        assertThat(ValidadorPago.validar(pagoDe("0.00", linea(Rubro.AGUA_POTABLE, "Consumo", "0.00"))))
                .hasValue("Total invalido");
    }

    @Test
    @DisplayName("rechaza un pago sin lineas de detalle")
    void rechazaPagoSinDetalle() {
        assertThat(ValidadorPago.validar(pagoDe("50.00", new PagoMensaje.LineaDetalle[0])))
                .hasValue("Pago sin detalle");
    }

    @Test
    @DisplayName("rechaza un detalle que no suma el total, que es lo que el ERP devolveria como 422")
    void rechazaDetalleQueNoCuadra() {
        var pago = pagoDe("100.00",
                linea(Rubro.AGUA_POTABLE, "Consumo", "60.00"),
                linea(Rubro.MORA, "Mora", "30.00"));

        assertThat(ValidadorPago.validar(pago))
                .hasValue("Detalle (90.00) no cuadra con total (100.00)");
    }

    @Test
    @DisplayName("acepta un detalle que cuadra aunque las escalas difieran")
    void comparaPorValorYNoPorEscala() {
        var pago = pagoDe("150.7", linea(Rubro.AGUA_POTABLE, "Consumo", "150.70"));

        assertThat(ValidadorPago.validar(pago)).isEmpty();
    }

    @Test
    @DisplayName("exige descripcion cuando el rubro es OTROS")
    void rechazaOtrosSinDescripcion() {
        assertThat(ValidadorPago.validar(pagoDe("25.00", linea(Rubro.OTROS, "  ", "25.00"))))
                .hasValue("Linea OTROS sin descripcion");
    }

    private static PagoMensaje pagoDe(String total, PagoMensaje.LineaDetalle... detalle) {
        return new PagoMensaje(
                "EQA-000123",
                123L,
                "REC-0001",
                1,
                "7f1c2a9e-0000-4000-8000-000000000001",
                new PagoMensaje.Cliente("CLI-0001", "Walter Salguero", "CF", "walter@example.com"),
                "CTR-0001",
                new PagoMensaje.Periodo(2026, 9),
                FormaPago.EFECTIVO,
                OffsetDateTime.parse("2026-10-26T14:32:11-06:00"),
                LocalDate.parse("2026-10-26"),
                new BigDecimal(total),
                "Servicio de agua 2026-09",
                List.of(detalle));
    }

    private static PagoMensaje.LineaDetalle linea(Rubro rubro, String descripcion, String monto) {
        return new PagoMensaje.LineaDetalle(rubro, descripcion, new BigDecimal(monto));
    }
}
