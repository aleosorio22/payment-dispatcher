package gt.umg.agua.dispatcher.consumer;

/** Que hacer con un pago despues de hablar con un destino. */
public enum Clasificacion {

    /** Quedo registrado alla. No se vuelve a intentar nunca. */
    ENTREGADO,

    /** No se pudo ahora pero puede salir bien despues: 429, 5xx, timeout. */
    REINTENTABLE,

    /**
     * El destino lo rechazo por lo que dice el pago, no por su propio estado.
     * Reintentar da exactamente lo mismo, asi que se va a la DLQ.
     */
    DEFINITIVO
}