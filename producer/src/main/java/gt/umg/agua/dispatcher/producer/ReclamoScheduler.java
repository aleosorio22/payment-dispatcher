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
            try {
                sqs.send(to -> to.queue(props.colaPagos())
                        .payload(pago)
                        .header("correlationId", pago.correlationId()));
                log.info("Encolado llave={} corr={}", pago.llave(), pago.correlationId());
            } catch (Exception e) {
                // Un pago que falla no debe impedir que los demas se encolen.
                log.error("Fallo el envio a SQS llave={}: {}", pago.llave(), e.getMessage());
            }
        }

        if (!pagos.isEmpty()) {
            log.info("Ciclo terminado: {} pagos encolados", pagos.size());
        }
    }
}