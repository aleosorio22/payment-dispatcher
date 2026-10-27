package gt.umg.agua.dispatcher.common;

/**
 * Cada sistema externo al que hay que entregarle el pago.
 *
 * <p>El estado se lleva por destino, no por pago: el banco puede aceptar un
 * cobro que el ERP rechaza, y reintentar uno no debe reenviar el otro.
 */
public enum Destino {
    BANCO,
    ERP
}
