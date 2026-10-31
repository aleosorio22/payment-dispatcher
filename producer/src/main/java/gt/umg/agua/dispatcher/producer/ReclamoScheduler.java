package gt.umg.agua.dispatcher.producer;

import gt.umg.agua.dispatcher.common.PagoMensaje;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El latido del producer: reclamar y encolar, cada 30 segundos.
 *
 * <p>Nada de lo que pase aqui adentro puede tumbar el proceso. Si el monolito
 * no responde o SQS falla, se registra y se vuelve a intentar en el siguiente
 * ciclo; un pago que quedo ENCOLADO sin llegar a la cola se vuelve a entregar
 * solo a los 30 minutos, porque su llave de idempotencia no cambia.
 */
@Component
public class ReclamoScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclamoScheduler.class);

    private final LaravelClient laravel;
    private final SqsTemplate sqs;
    private final ProducerProps props;

    public ReclamoScheduler(LaravelClient laravel, SqsTemplate sqs, ProducerProps props) {
        this.laravel = laravel;
        this.sqs = sqs;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.reclamo.intervalo-ms}", initialDelay = 10000)
    public void ciclo() {
        List<PagoMensaje> pagos;

        try {
            pagos = laravel.reclamar(props.reclamo().limite());
        } catch (Exception e) {
            log.warn("No se pudo reclamar en Laravel: {}", e.getMessage());
            return;
        }

        for (PagoMensaje pago : pagos) {
            // El mismo pago va a dos colas porque son dos obligaciones
            // distintas: entregarlo al banco y al ERP, y facturarlo. Cada
            // servicio tiene su cola, su ritmo y sus fallos; si Digifact esta
            // caido, el banco no se entera.
            encolar(props.colaPagos(), pago, "Encolado");
            encolar(props.colaFacturas(), pago, "A facturar");
        }

        if (!pagos.isEmpty()) {
            log.info("Ciclo terminado: {} pagos encolados", pagos.size());
        }
    }

    private void encolar(String cola, PagoMensaje pago, String aviso) {
        try {
            sqs.send(to -> to.queue(cola)
                    .payload(pago)
                    .header("correlationId", pago.correlationId()));
            log.info("{} llave={} corr={}", aviso, pago.llave(), pago.correlationId());
        } catch (Exception e) {
            // Un pago que falla no debe impedir que los demas se encolen, ni
            // que el mismo pago llegue a la otra cola. El reclamo de los 30
            // minutos lo vuelve a traer.
            log.error("Fallo el envio a {} llave={}: {}", cola, pago.llave(), e.getMessage());
        }
    }
}