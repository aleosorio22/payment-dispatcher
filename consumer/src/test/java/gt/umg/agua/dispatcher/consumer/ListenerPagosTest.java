package gt.umg.agua.dispatcher.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class ListenerPagosTest {

    @Mock
    private Despachador despachador;

    @Mock
    private PublicadorDlq dlq;

    @Captor
    private ArgumentCaptor<String> motivo;

    private final PagoMensaje pago = PagosDePrueba.unPago();

    private ListenerPagos listener;

    @BeforeEach
    void armarElListener() {
        listener = new ListenerPagos(despachador, dlq, PagosDePrueba.props());
    }

    private Message<PagoMensaje> mensaje(int recepcion) {
        return MessageBuilder.withPayload(pago)
                .setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT,
                        String.valueOf(recepcion))
                .build();
    }

    private Despacho entregado() {
        return new Despacho(List.of(
                Resultado.entregado(Destino.BANCO, 201, "BNK-1"),
                Resultado.entregado(Destino.ERP, 201, "ERP-1")));
    }

    private Despacho conFalloPasajero() {
        return new Despacho(List.of(
                Resultado.entregado(Destino.BANCO, 201, "BNK-1"),
                Resultado.reintentable(Destino.ERP, 503, "SERVICE_UNAVAILABLE", "no disponible",
                        Duration.ZERO)));
    }

    @Test
    @DisplayName("si todo se entrego, el metodo termina y SQS borra el mensaje")
    void borraElMensajeCuandoTodoSeEntrego() {
        when(despachador.despachar(pago, 1)).thenReturn(entregado());

        assertThatCode(() -> listener.recibir(mensaje(1))).doesNotThrowAnyException();

        verify(dlq, never()).publicar(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("si hay algo que puede componerse, lanza para que el mensaje vuelva a la cola")
    void devuelveElMensajeALaCola() {
        when(despachador.despachar(pago, 2)).thenReturn(conFalloPasajero());

        assertThatThrownBy(() -> listener.recibir(mensaje(2)))
                .isInstanceOf(ListenerPagos.ReintentarDespues.class)
                .hasMessageContaining("recepcion 2 de 5");

        verify(dlq, never()).publicar(any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("en la quinta recepcion deja de insistir y lo manda a la DLQ")
    void mandaALaDlqCuandoSeAgotanLasRecepciones() {
        when(despachador.despachar(pago, 5)).thenReturn(conFalloPasajero());

        assertThatCode(() -> listener.recibir(mensaje(5))).doesNotThrowAnyException();

        // Primero cerrar el estado, despues copiar: si la copia falla, el
        // mensaje vuelve y la tabla ya dice la verdad.
        verify(despachador).darPorPerdido(eq(pago), any(), eq(5), eq("REINTENTOS_AGOTADOS"));
        verify(dlq).publicar(eq(pago), motivo.capture(), anyString(), eq(5));
        assertThat(motivo.getValue()).isEqualTo("REINTENTOS_AGOTADOS");
    }

    @Test
    @DisplayName("un rechazo definitivo se va a la DLQ sin gastar las cinco recepciones")
    void elRechazoDefinitivoNoEsperaALasCincoRecepciones() {
        Despacho rechazado = new Despacho(List.of(
                Resultado.definitivo(Destino.BANCO, 422, "UNSUPPORTED_CHANNEL", "canal no soportado"),
                Resultado.entregado(Destino.ERP, 201, "ERP-1")));
        when(despachador.despachar(pago, 1)).thenReturn(rechazado);

        assertThatCode(() -> listener.recibir(mensaje(1))).doesNotThrowAnyException();

        verify(dlq).publicar(eq(pago), motivo.capture(), anyString(), eq(1));
        assertThat(motivo.getValue()).isEqualTo("RECHAZO_DEFINITIVO");
    }

    @Test
    @DisplayName("sin el header de recepciones, se asume la primera")
    void sinElHeaderSeAsumeLaPrimera() {
        when(despachador.despachar(pago, 1)).thenReturn(entregado());

        assertThatCode(() -> listener.recibir(MessageBuilder.withPayload(pago).build()))
                .doesNotThrowAnyException();

        verify(despachador).despachar(pago, 1);
    }
}