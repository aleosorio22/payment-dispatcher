package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.common.Rubro;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Un pago valido para las pruebas del consumer, igual al que emite el monolito. */
final class PagosDePrueba {

    private PagosDePrueba() {
    }

    static PagoMensaje unPago() {
        return new PagoMensaje(
                "EQD-000210",
                210L,
                "REC-0210",
                1,
                "7f1c2a9e-4b3d-4a1e-9c2f-0a1b2c3d4e5f",
                new PagoMensaje.Cliente("CLI-0042", "Walter Salguero", "CF", "walter@example.com"),
                "CTR-0001",
                new PagoMensaje.Periodo(2026, 9),
                FormaPago.TRANSFERENCIA,
                OffsetDateTime.parse("2026-10-28T17:40:12-06:00"),
                LocalDate.of(2026, 10, 28),
                new BigDecimal("100.80"),
                "Servicio de agua 2026-09",
                List.of(new PagoMensaje.LineaDetalle(
                        Rubro.AGUA_POTABLE, "Consumo 2026-09", new BigDecimal("100.80"))));
    }

    static ConsumerProps props() {
        return new ConsumerProps(
                "EQUIPO_D",
                "pagos-envio",
                "pagos-envio-dlq",
                "pago_envio_log",
                "pago_envio_estado",
                new ConsumerProps.Reintentos(3, 1000, 10000, 5),
                new ConsumerProps.Simulacion("AUTO", "AUTO"));
    }
}