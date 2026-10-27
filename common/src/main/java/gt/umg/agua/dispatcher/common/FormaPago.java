package gt.umg.agua.dispatcher.common;

/**
 * Como pago el vecino, en el catalogo que comparten el canonico y el ERP.
 *
 * <p>Son cuatro y no cinco: el monolito tiene `deposito` como metodo aparte,
 * pero ninguno de los dos contratos externos lo distingue de una
 * transferencia, asi que Laravel ya lo colapsa antes de emitir el mensaje.
 *
 * <p>CHEQUE existe aunque el banco lo rechace con 422. Ese rechazo es una
 * respuesta legitima del contrato y el sistema debe saber representarlo.
 */
public enum FormaPago {
    EFECTIVO,
    TARJETA,
    TRANSFERENCIA,
    CHEQUE
}
