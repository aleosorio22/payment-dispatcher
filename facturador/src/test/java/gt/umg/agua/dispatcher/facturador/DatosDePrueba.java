package gt.umg.agua.dispatcher.facturador;

import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.common.Rubro;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Un pago y una configuracion validos, iguales a los de produccion. */
final class DatosDePrueba {

    private DatosDePrueba() {
    }

    static PagoMensaje unPago() {
        return new PagoMensaje(
                "EQD-000215",
                215L,
                "REC-0215",
                1,
                "7f1c2a9e-4b3d-4a1e-9c2f-0a1b2c3d4e5f",
                new PagoMensaje.Cliente("CLI-0042", "Walter Salguero", "CF", "walter@example.com"),
                "CTR-0001",
                new PagoMensaje.Periodo(2026, 9),
                FormaPago.EFECTIVO,
                OffsetDateTime.parse("2026-10-31T09:15:00-06:00"),
                LocalDate.of(2026, 10, 31),
                new BigDecimal("50.40"),
                "Servicio de agua 2026-09",
                List.of(new PagoMensaje.LineaDetalle(
                        Rubro.AGUA_POTABLE, "Servicio de agua potable periodo 2026-09",
                        new BigDecimal("50.40"))));
    }

    /** Los datos reales del contribuyente de pruebas, tal como estan en el RTU. */
    static FacturadorProps props() {
        return props("comatecgt@gmail.com");
    }

    static FacturadorProps props(String correoReceptor) {
        return new FacturadorProps(
                "facturas-emitir",
                "factura_emitida",
                correoReceptor,
                new FacturadorProps.Digifact(
                        "https://testnucgt.digifact.com/api",
                        "GT.000005888492.TEST_RENEOSORIO",
                        "secreto"),
                new FacturadorProps.Emisor(
                        "5888492",
                        "PEREZ PEREZ, ISMAR BLADIMIR",
                        "emisor@example.com",
                        "GEN",
                        "1",
                        "2",
                        "2",
                        "PEREZ PEREZ, ISMAR BLADIMIR",
                        new FacturadorProps.Direccion(
                                "CIUDAD", "01001", "GUATEMALA", "GUATEMALA", "GT")));
    }
}
