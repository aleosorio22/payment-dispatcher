package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.common.ValidadorPago;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * La puerta de entrada: saca los pagos de la cola y decide que hacer con cada
 * uno.
 *
 * <p>Hay exactamente tres salidas y el mensaje toma una sola:
 *
 * <ul>
 *   <li>todo entregado: el metodo termina y SQS borra el mensaje;
 *   <li>algo que puede componerse: se lanza una excepcion y el mensaje vuelve
 *       a la cola cuando se vence el visibility timeout;
 *   <li>nada mas que hacer: se copia a la DLQ con el motivo y el metodo
 *       termina, con lo que el mensaje sale de la cola principal.
 * </ul>
 */
@Component
public class ListenerPagos {

    private static final Logger log = LoggerFactory.getLogger(ListenerPagos.class);

    private final Despachador despachador;
    private final PublicadorDlq dlq;
    private final ConsumerProps props;

    public ListenerPagos(Despachador despachador, PublicadorDlq dlq, ConsumerProps props) {
        this.despachador = despachador;
        this.dlq = dlq;
        this.props = props;
    }

    @SqsListener(queueNames = "${app.cola-pagos}")
    public void recibir(Message<PagoMensaje> mensaje) {
        PagoMensaje pago = mensaje.getPayload();
        int recepcion = recepcionDe(mensaje);

        // Lo toma el patron de log: cada linea dice de que pago habla.
        MDC.put("llave", pago.llave());
        MDC.put("corr", pago.correlationId());
        try {
            atender(pago, recepcion);
        } finally {
            MDC.clear();
        }
    }

    private void atender(PagoMensaje pago, int recepcion) {
        Optional<String> queja = ValidadorPago.validar(pago);
        if (queja.isPresent()) {
            // Un mensaje mal armado no mejora por reintentarlo.
            dlq.publicar(pago, "MENSAJE_INVALIDO", queja.get(), recepcion);
            return;
        }

        Despacho despacho = despachador.despachar(pago, recepcion);

        if (despacho.todoEntregado()) {
            log.info("Entregado: {}", despacho.resumen());
            return;
        }

        if (despacho.hayQueVolverAIntentar() && recepcion < props.reintentos().maxRecepciones()) {
            throw new ReintentarDespues(
                    "recepcion %d de %d: %s".formatted(
                            recepcion, props.reintentos().maxRecepciones(), despacho.resumen()));
        }

        String motivo = despacho.hayQueVolverAIntentar() ? "REINTENTOS_AGOTADOS" : "RECHAZO_DEFINITIVO";
        // Primero el estado y despues la DLQ: si lo de abajo falla, el mensaje
        // vuelve a la cola y la tabla ya dice la verdad.
        despachador.darPorPerdido(pago, despacho, recepcion, motivo);
        dlq.publicar(pago, motivo, despacho.resumen(), recepcion);
    }

    private int recepcionDe(Message<PagoMensaje> mensaje) {
        Object veces = mensaje.getHeaders()
                .get(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT);
        return veces == null ? 1 : Integer.parseInt(veces.toString());
    }

    /**
     * Se lanza a proposito para que SQS devuelva el mensaje a la cola. No es
     * un error de programacion, asi que no carga el stack trace: en el log
     * solo estorbaria.
     */
    static class ReintentarDespues extends RuntimeException {

        ReintentarDespues(String mensaje) {
            super(mensaje);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}