package gt.umg.agua.dispatcher.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ExtendWith(MockitoExtension.class)
class RepositorioEstadoTest {

    @Mock
    private DynamoDbClient dynamo;

    @Captor
    private ArgumentCaptor<PutItemRequest> escritura;

    @Captor
    private ArgumentCaptor<GetItemRequest> lectura;

    private final PagoMensaje pago = PagosDePrueba.unPago();

    private RepositorioEstado repositorio() {
        return new RepositorioEstado(dynamo, PagosDePrueba.props());
    }

    @Test
    @DisplayName("un pago aceptado queda ENTREGADO con su referencia")
    void guardaElEntregado() {
        repositorio().guardar(pago, Resultado.entregado(Destino.BANCO, 201, "BNK-20261028-8F3K2Q"), 1);

        verify(dynamo).putItem(escritura.capture());
        Map<String, AttributeValue> fila = escritura.getValue().item();

        assertThat(escritura.getValue().tableName()).isEqualTo("pago_envio_estado");
        assertThat(fila.get("llave").s()).isEqualTo("EQD-000210");
        assertThat(fila.get("destino").s()).isEqualTo("BANCO");
        assertThat(fila.get("estado").s()).isEqualTo("ENTREGADO");
        assertThat(fila.get("referencia").s()).isEqualTo("BNK-20261028-8F3K2Q");
        // Para encontrar el pago en la consola sin cruzar contra la base.
        assertThat(fila.get("folio").s()).isEqualTo("REC-0210");
        assertThat(fila.get("cliente").s()).isEqualTo("CLI-0042");
    }

    @Test
    @DisplayName("guarda la partida contable que devolvio el ERP")
    void guardaLaPartidaDelErp() {
        repositorio().guardar(pago,
                Resultado.entregado(Destino.ERP, 201, "ERP-20261030-Q7W2ZX")
                        .conPartida("PDA-2026-000873"),
                1);

        verify(dynamo).putItem(escritura.capture());

        // Sin esto el campo partida_erp del monolito quedaria vacio para
        // siempre: el ERP solo lo dice una vez, al contabilizar.
        assertThat(escritura.getValue().item().get("partida").s()).isEqualTo("PDA-2026-000873");
    }

    @Test
    @DisplayName("un duplicado tambien queda ENTREGADO: es lo que compra la llave de idempotencia")
    void elDuplicadoQuedaEntregado() {
        repositorio().guardar(pago,
                Resultado.duplicado(Destino.ERP, "DOCUMENTO_DUPLICADO", "ya fue contabilizado"), 2);

        verify(dynamo).putItem(escritura.capture());
        Map<String, AttributeValue> fila = escritura.getValue().item();

        assertThat(fila.get("estado").s()).isEqualTo("ENTREGADO");
        assertThat(fila.get("codigo").s()).isEqualTo("DOCUMENTO_DUPLICADO");
        assertThat(fila.get("recepciones").n()).isEqualTo("2");
    }

    @Test
    @DisplayName("un fallo pasajero queda REINTENTANDO, no ERROR")
    void elFalloPasajeroQuedaReintentando() {
        repositorio().guardar(pago,
                Resultado.reintentable(Destino.BANCO, 503, "SERVICE_UNAVAILABLE", "no disponible",
                        Duration.ofSeconds(3)),
                1);

        verify(dynamo).putItem(escritura.capture());

        // La diferencia importa: lo que queda REINTENTANDO vuelve, lo que
        // queda en ERROR ya no.
        assertThat(escritura.getValue().item().get("estado").s()).isEqualTo("REINTENTANDO");
    }

    @Test
    @DisplayName("un rechazo por los datos del pago queda en ERROR")
    void elRechazoDefinitivoQuedaEnError() {
        repositorio().guardar(pago,
                Resultado.definitivo(Destino.BANCO, 422, "UNSUPPORTED_CHANNEL", "canal no soportado"), 1);

        verify(dynamo).putItem(escritura.capture());

        assertThat(escritura.getValue().item().get("estado").s()).isEqualTo("ERROR");
        assertThat(escritura.getValue().item().get("codigo").s()).isEqualTo("UNSUPPORTED_CHANNEL");
    }

    @Test
    @DisplayName("si no se puede guardar el veredicto, el mensaje debe volver a la cola")
    void elFalloAlGuardarSePropaga() {
        when(dynamo.putItem(any(PutItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("sin cuota").build());

        // Dar por entregado un pago cuyo estado nadie anoto es como no
        // haberlo entregado.
        assertThatThrownBy(() -> repositorio()
                .guardar(pago, Resultado.entregado(Destino.BANCO, 201, "BNK-1"), 1))
                .isInstanceOf(DynamoDbException.class);
    }

    @Test
    @DisplayName("reconoce al destino que ya tiene el pago y lee de forma consistente")
    void reconoceLoQueYaEstaEntregado() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "llave", AttributeValue.builder().s("EQD-000210").build(),
                        "destino", AttributeValue.builder().s("BANCO").build(),
                        "estado", AttributeValue.builder().s("ENTREGADO").build(),
                        "referencia", AttributeValue.builder().s("BNK-20261028-8F3K2Q").build()))
                .build());

        assertThat(repositorio().reconfirmar("EQD-000210", Destino.BANCO)).isTrue();

        verify(dynamo).getItem(lectura.capture());
        // Sin lectura consistente se podria leer una copia atrasada y
        // reenviarle al banco un pago que ya tiene.
        assertThat(lectura.getValue().consistentRead()).isTrue();
        assertThat(lectura.getValue().key().get("destino").s()).isEqualTo("BANCO");
    }

    @Test
    @DisplayName("reescribe la fila para que el stream vuelva a avisarle al monolito")
    void reescribeLaFilaParaDespertarElStream() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of(
                        "llave", AttributeValue.builder().s("EQD-000210").build(),
                        "destino", AttributeValue.builder().s("ERP").build(),
                        "estado", AttributeValue.builder().s("ENTREGADO").build(),
                        "referencia", AttributeValue.builder().s("ERP-20261028-Q7W2ZX").build(),
                        "partida", AttributeValue.builder().s("PDA-2026-000873").build()))
                .build());

        repositorio().reconfirmar("EQD-000210", Destino.ERP);

        verify(dynamo).putItem(escritura.capture());
        Map<String, AttributeValue> fila = escritura.getValue().item();

        // Si no se escribiera nada, el stream no disparara y el pago quedaria
        // ENCOLADO para siempre en el monolito aunque el ERP ya lo tenga.
        assertThat(fila.get("estado").s()).isEqualTo("ENTREGADO");
        // Y se reescribe entera: el recibo y la partida no se pierden.
        assertThat(fila.get("referencia").s()).isEqualTo("ERP-20261028-Q7W2ZX");
        assertThat(fila.get("partida").s()).isEqualTo("PDA-2026-000873");
        assertThat(fila).containsKey("actualizado_en");
    }

    @Test
    @DisplayName("lo que esta REINTENTANDO no cuenta como entregado")
    void loQueFaltaNoCuentaComoEntregado() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("estado", AttributeValue.builder().s("REINTENTANDO").build()))
                .build());

        assertThat(repositorio().reconfirmar("EQD-000210", Destino.ERP)).isFalse();
        verify(dynamo, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    @DisplayName("sin fila, o sin poder consultar, se asume que falta entregar")
    void anteLaDudaSeReenvia() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertThat(repositorio().reconfirmar("EQD-000211", Destino.BANCO)).isFalse();

        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("sin cuota").build());
        // Un reenvio es inofensivo por la llave de idempotencia; darlo por
        // entregado sin saberlo, no.
        assertThat(repositorio().reconfirmar("EQD-000211", Destino.BANCO)).isFalse();
    }
}
