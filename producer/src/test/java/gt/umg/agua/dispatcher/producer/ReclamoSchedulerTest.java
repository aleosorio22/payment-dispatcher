package gt.umg.agua.dispatcher.producer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gt.umg.agua.dispatcher.common.FormaPago;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import gt.umg.agua.dispatcher.common.Rubro;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El ciclo no puede morirse nunca: si lo hace, los pagos dejan de encolarse
 * en silencio y nadie se entera hasta que la oficina reclama.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ReclamoSchedulerTest {

    private LaravelClient laravel;
    private SqsTemplate sqs;
    private ReclamoScheduler scheduler;

    @BeforeEach
    void preparar() {
        laravel = mock(LaravelClient.class);
        sqs = mock(SqsTemplate.class);
        scheduler = new ReclamoScheduler(laravel, sqs, props());
    }

    @Test
    @DisplayName("cada pago va a las dos colas: la de envio y la de facturacion")
    void encolaCadaPagoEnLasDosColas() {
        when(laravel.reclamar(50)).thenReturn(List.of(pago("EQA-000001"), pago("EQA-000002")));

        scheduler.ciclo();

        // Dos pagos por dos obligaciones distintas: entregarlos al banco y al
        // ERP, y facturarlos. Cada servicio lee de su propia cola.
        verify(sqs, times(4)).send(any(Consumer.class));
    }

    @Test
    @DisplayName("si el monolito no responde, no revienta y no toca la cola")
    void sobreviveAlMonolitoCaido() {
        when(laravel.reclamar(50)).thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> scheduler.ciclo()).doesNotThrowAnyException();

        verify(sqs, never()).send(any(Consumer.class));
    }

    @Test
    @DisplayName("si un pago falla al encolarse, los demas igual se encolan")
    void unPagoFallidoNoDetieneAlResto() {
        when(laravel.reclamar(50)).thenReturn(List.of(pago("EQA-000001"), pago("EQA-000002")));
        when(sqs.send(any(Consumer.class)))
                .thenThrow(new RuntimeException("sin red"))
                .thenReturn(null);

        assertThatCode(() -> scheduler.ciclo()).doesNotThrowAnyException();

        // El primer envio revienta y los otros tres salen igual: ni el resto
        // de los pagos ni la otra cola pagan por un fallo aislado.
        verify(sqs, times(4)).send(any(Consumer.class));
    }

    private static ProducerProps props() {
        return new ProducerProps(
                new ProducerProps.Laravel("http://monolito.test/api/integracion", "token-de-prueba"),
                "pagos-envio",
                "facturas-emitir",
                new ProducerProps.Reclamo(30_000, 50));
    }

    private static PagoMensaje pago(String llave) {
        return new PagoMensaje(
                llave,
                1L,
                "REC-0001",
                1,
                "corr-" + llave,
                new PagoMensaje.Cliente("CLI-0001", "Walter Salguero", "CF", "walter@example.com"),
                "CTR-0001",
                new PagoMensaje.Periodo(2026, 9),
                FormaPago.EFECTIVO,
                OffsetDateTime.parse("2026-10-26T14:32:11-06:00"),
                LocalDate.parse("2026-10-26"),
                new BigDecimal("102.37"),
                "Servicio de agua 2026-09",
                List.of(new PagoMensaje.LineaDetalle(Rubro.AGUA_POTABLE, "Consumo 2026-09",
                        new BigDecimal("102.37"))));
    }
}