package gt.umg.agua.dispatcher.facturador;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.common.ValidadorPago;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Saca los pagos de la cola y los convierte en facturas electronicas.
 *
 * <p>Es a proposito mas simple que el listener del consumer. Alla habia dos
 * destinos que podian fallar de maneras distintas y un monolito esperando
 * noticias; aqui hay un solo destino y dos desenlaces: o la SAT autoriza el
 * documento o lo rechaza, y un rechazo fiscal no mejora por insistir.
 *
 * <p>Los reintentos los pone la cola: si Digifact no contesta, el metodo
 * lanza, el mensaje vuelve y mas tarde se intenta de nuevo. A la sexta
 * entrega, SQS lo mueve solo a la cola de descarte.
 */
@Component
public class ListenerFacturas {

    private static final Logger log = LoggerFactory.getLogger(ListenerFacturas.class);

    private final ConstructorNuc constructor;
    private final ClienteDigifact digifact;
    private final RepositorioFacturas facturas;

    public ListenerFacturas(ConstructorNuc constructor, ClienteDigifact digifact,
            RepositorioFacturas facturas) {
        this.constructor = constructor;
        this.digifact = digifact;
        this.facturas = facturas;
    }

    @SqsListener(queueNames = "${app.cola-facturas}")
    public void recibir(PagoMensaje pago) {
        MDC.put("llave", pago.llave());
        MDC.put("corr", pago.correlationId());
        try {
            facturar(pago);
        } finally {
            MDC.clear();
        }
    }

    private void facturar(PagoMensaje pago) {
        Optional<String> queja = ValidadorPago.validar(pago);
        if (queja.isPresent()) {
            // Un mensaje mal armado no mejora por reintentarlo, y una factura
            // con datos equivocados hay que anularla despues.
            log.error("No se factura, el mensaje viene mal: {}", queja.get());
            return;
        }

        if (facturas.yaFacturado(pago.llave())) {
            // Una factura emitida dos veces no se borra: se anula con otro
            // documento y queda el rastro en la SAT. Mejor no emitirla.
            log.info("Ya tenia factura, no se emite otra");
            return;
        }

        Certificacion certificacion = digifact.certificar(constructor.construir(pago));
        facturas.guardar(pago, certificacion);

        if (certificacion.certificada()) {
            log.info("Certificada autorizacion={} serie={} numero={}",
                    certificacion.numeroAutorizacion(), certificacion.serie(), certificacion.correlativo());
        } else {
            // Queda escrita como RECHAZADA y el mensaje se borra. Insistir con
            // un documento que la SAT no acepta solo gasta cuota; esto lo
            // tiene que ver una persona.
            log.error("Rechazada por Digifact, codigo={}: {}",
                    certificacion.codigo(), certificacion.mensaje());
        }
    }
}
