package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;

/** Un destino al que hay que entregarle el pago. */
public interface Gateway {

    Destino destino();

    /**
     * Entrega el pago y devuelve lo que paso, ya interpretado.
     *
     * <p>No lanza: un fallo del destino es un {@link Resultado}, no una
     * excepcion. Asi el que despacha decide con una sola rama de codigo y no
     * con un try por destino.
     */
    Resultado enviar(PagoMensaje pago);
}