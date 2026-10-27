package gt.umg.agua.dispatcher.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * El pago tal como viaja por el sistema: lo emite Laravel, lo publica el
 * producer en SQS y lo traduce el consumer a los formatos del banco y del ERP.
 *
 * <p>A proposito no se parece a ninguno de los dos contratos externos. Cada
 * destino pide los campos con otros nombres y otras unidades; si el canonico
 * copiara a uno de ellos, el otro quedaria siempre a medias.
 *
 * <p>Los montos son {@link BigDecimal} porque llegan como texto con dos
 * decimales ("150.75"). En ningun punto del camino un quetzal pasa por un
 * {@code double}.
 *
 * @param llave         identificador estable del envio; es la llave de idempotencia
 * @param pagoId        id del pago en el monolito
 * @param folio         numero de recibo que el vecino tiene en la mano
 * @param ronda         numero de intento completo; cambia si se reenvia el pago
 * @param correlationId identificador para seguir un pago en los logs de las tres piezas
 * @param cliente       datos del contribuyente
 * @param contador      codigo del contador al que pertenece la lectura cobrada
 * @param periodo       periodo mensual que cubre la boleta
 * @param formaPago     como pago el vecino, en el catalogo del ERP
 * @param fechaPago     instante real del cobro, con zona horaria
 * @param fechaEmision  fecha de valor del recibo; es la fecha de emision del documento de origen
 * @param total         monto cobrado
 * @param descripcion   glosa corta del cobro, maximo 140 caracteres
 * @param detalle       desglose por rubro; su suma debe dar exactamente el total
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PagoMensaje(
        String llave,
        long pagoId,
        String folio,
        int ronda,
        String correlationId,
        Cliente cliente,
        String contador,
        Periodo periodo,
        FormaPago formaPago,
        OffsetDateTime fechaPago,
        LocalDate fechaEmision,
        BigDecimal total,
        String descripcion,
        List<LineaDetalle> detalle) {

    /**
     * @param nit numero de identificacion tributaria, o "CF" si el vecino no lo dio
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cliente(String codigo, String nombre, String nit, String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Periodo(int anio, int mes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineaDetalle(Rubro rubro, String descripcion, BigDecimal monto) {}
}
