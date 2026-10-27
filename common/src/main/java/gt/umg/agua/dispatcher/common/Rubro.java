package gt.umg.agua.dispatcher.common;

/**
 * Concepto de cada linea del detalle, en el catalogo del ERP municipal.
 *
 * <p>Hoy el monolito solo emite AGUA_POTABLE: un pago puede ser un abono
 * parcial de la boleta, y partirlo en cuota base y excedente haria que el
 * desglose no sumara el total. Los demas rubros existen porque el contrato
 * del ERP los admite y la oficina los va a necesitar mas adelante.
 */
public enum Rubro {
    AGUA_POTABLE,
    RECONEXION,
    MORA,
    NUEVA_CONEXION,
    OTROS
}
