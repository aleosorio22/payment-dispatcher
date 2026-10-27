package gt.umg.agua.dispatcher.common;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Revisa el mensaje antes de que salga hacia el banco o el ERP.
 *
 * <p>Son las mismas reglas que los contratos externos aplican del otro lado.
 * Comprobarlas aqui convierte un 422 remoto —que cuesta una llamada de red y
 * llega con un mensaje ajeno— en un error local, inmediato y en nuestros
 * terminos. Un pago que no pasa por aqui es un defecto del monolito, no una
 * falla transitoria: no tiene sentido reintentarlo.
 */
public final class ValidadorPago {

    private ValidadorPago() {
    }

    /**
     * @return el motivo del rechazo, o vacio si el mensaje esta sano
     */
    public static Optional<String> validar(PagoMensaje pago) {
        if (pago.total() == null || pago.total().signum() <= 0) {
            return Optional.of("Total invalido");
        }

        if (pago.detalle() == null || pago.detalle().isEmpty()) {
            return Optional.of("Pago sin detalle");
        }

        BigDecimal suma = pago.detalle().stream()
                .map(PagoMensaje.LineaDetalle::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // compareTo y no equals: 150.70 y 150.7 son el mismo dinero aunque
        // BigDecimal los considere objetos distintos por la escala.
        if (suma.compareTo(pago.total()) != 0) {
            return Optional.of("Detalle (" + suma + ") no cuadra con total (" + pago.total() + ")");
        }

        boolean otrosSinDescripcion = pago.detalle().stream()
                .anyMatch(linea -> linea.rubro() == Rubro.OTROS
                        && (linea.descripcion() == null || linea.descripcion().isBlank()));

        if (otrosSinDescripcion) {
            return Optional.of("Linea OTROS sin descripcion");
        }

        return Optional.empty();
    }
}
