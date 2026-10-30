package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.Destino;
import java.time.Duration;

/**
 * Lo que respondio un destino, ya interpretado y sin rastro del contrato que
 * lo produjo: de aqui en adelante el banco y el ERP se tratan igual.
 *
 * @param codigo     codigo de negocio del destino, o cadena vacia si no vino
 * @param referencia receiptId del banco o numeroComprobante del ERP
 * @param partida    numero de partida contable; solo lo devuelve el ERP
 * @param espera     lo que el destino pidio esperar; ZERO si no pidio nada
 */
public record Resultado(
        Destino destino,
        Clasificacion clasificacion,
        int httpStatus,
        String codigo,
        String mensaje,
        String referencia,
        String partida,
        Duration espera) {

    public static Resultado entregado(Destino destino, int httpStatus, String referencia) {
        return new Resultado(destino, Clasificacion.ENTREGADO, httpStatus, "", "", referencia, "", Duration.ZERO);
    }

    /**
     * El destino dice que ya lo tenia. Cuenta como entregado: es justo lo que
     * compra la llave de idempotencia.
     */
    public static Resultado duplicado(Destino destino, String codigo, String mensaje) {
        return new Resultado(destino, Clasificacion.ENTREGADO, 409, codigo, mensaje, "", "", Duration.ZERO);
    }

    public static Resultado reintentable(
            Destino destino, int httpStatus, String codigo, String mensaje, Duration espera) {
        return new Resultado(destino, Clasificacion.REINTENTABLE, httpStatus, codigo, mensaje, "", "", espera);
    }

    public static Resultado definitivo(Destino destino, int httpStatus, String codigo, String mensaje) {
        return new Resultado(destino, Clasificacion.DEFINITIVO, httpStatus, codigo, mensaje, "", "", Duration.ZERO);
    }

    /**
     * No hubo respuesta que leer: se vencio el tiempo o se corto la conexion.
     * El pago pudo haber entrado igual, y el 409 del reintento lo aclarara.
     */
    public static Resultado sinRespuesta(Destino destino, String mensaje) {
        return new Resultado(destino, Clasificacion.REINTENTABLE, 0, "SIN_RESPUESTA", mensaje, "", "", Duration.ZERO);
    }

    /**
     * Este destino ya lo tenia de una vuelta anterior, asi que no se le volvio
     * a preguntar. No se guarda en la tabla de estado: la fila que ya esta
     * tiene la referencia buena y esta no la tiene.
     */
    public static Resultado yaEstaba(Destino destino) {
        return new Resultado(destino, Clasificacion.ENTREGADO, 0, "YA_ENTREGADO", "", "", "", Duration.ZERO);
    }

    /**
     * El mismo resultado con el numero de partida que devolvio el ERP. Es el
     * asiento donde quedo registrado el pago, y la municipalidad lo necesita
     * para cuadrar sus libros.
     */
    public Resultado conPartida(String partida) {
        return new Resultado(
                destino, clasificacion, httpStatus, codigo, mensaje, referencia, partida, espera);
    }

    public boolean fueEntregado() {
        return clasificacion == Clasificacion.ENTREGADO;
    }

}