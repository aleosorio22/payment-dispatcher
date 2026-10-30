package gt.umg.agua.dispatcher.consumer;

/**
 * Recortes para los limites de longitud de los contratos.
 *
 * <p>Un nombre de 160 caracteres le saca un 422 al banco, y un 422 es
 * definitivo: el pago se iria a la DLQ por algo que no vale perderlo.
 */
final class Textos {

    private Textos() {
    }

    static String recortar(String texto, int maximo) {
        if (texto == null || texto.length() <= maximo) {
            return texto;
        }
        return texto.substring(0, maximo);
    }
}