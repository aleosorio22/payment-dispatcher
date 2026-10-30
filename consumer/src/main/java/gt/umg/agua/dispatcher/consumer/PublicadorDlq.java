package gt.umg.agua.dispatcher.consumer;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copia a la cola de descarte el pago que ya no se va a entregar.
 *
 * <p>La cola tambien tiene redrive automatico, pero ese mueve el mensaje sin
 * una sola palabra de explicacion. Por eso el corte propio esta en cinco
 * recepciones y el de la redrive en seis: en la practica el mensaje siempre
 * llega aqui por este camino, con el motivo pegado, y la redrive queda como
 * red de seguridad por si este codigo falla de una forma que nadie previo.
 */
@Component
public class PublicadorDlq {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDlq.class);

    private final SqsTemplate sqs;
    private final ConsumerProps props;

    public PublicadorDlq(SqsTemplate sqs, ConsumerProps props) {
        this.sqs = sqs;
        this.props = props;
    }

    public void publicar(PagoMensaje pago, String motivo, String detalle, int recepcion) {
        sqs.send(opciones -> opciones
                .queue(props.colaDlq())
                .payload(pago)
                // Lo que se necesita para triar sin abrir el cuerpo del mensaje.
                .header("motivo", motivo)
                .header("detalle", detalle)
                .header("llave", pago.llave())
                .header("recepciones", String.valueOf(recepcion)));

        log.error("A la DLQ: llave={} motivo={} detalle={}", pago.llave(), motivo, detalle);
    }
}