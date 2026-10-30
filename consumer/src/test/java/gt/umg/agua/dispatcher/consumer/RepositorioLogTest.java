package gt.umg.agua.dispatcher.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gt.umg.agua.dispatcher.common.Destino;
import gt.umg.agua.dispatcher.common.PagoMensaje;
import java.time.Instant;
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
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

@ExtendWith(MockitoExtension.class)
class RepositorioLogTest {

    @Mock
    private DynamoDbClient dynamo;

    @Captor
    private ArgumentCaptor<PutItemRequest> peticion;

    private final PagoMensaje pago = PagosDePrueba.unPago();

    private RepositorioLog repositorio() {
        return new RepositorioLog(dynamo, PagosDePrueba.props());
    }

    @Test
    @DisplayName("anota el intento con su clave, su destino y lo que respondio")
    void anotaElIntento() {
        repositorio().registrar(pago, Resultado.entregado(Destino.BANCO, 201, "BNK-20261028-8F3K2Q"), 1, 1);

        verify(dynamo).putItem(peticion.capture());
        Map<String, AttributeValue> fila = peticion.getValue().item();

        assertThat(peticion.getValue().tableName()).isEqualTo("pago_envio_log");
        assertThat(fila.get("llave").s()).isEqualTo("EQD-000210");
        assertThat(fila.get("destino").s()).isEqualTo("BANCO");
        assertThat(fila.get("clasificacion").s()).isEqualTo("ENTREGADO");
        assertThat(fila.get("http_status").n()).isEqualTo("201");
        assertThat(fila.get("referencia").s()).isEqualTo("BNK-20261028-8F3K2Q");
        assertThat(fila.get("pago_id").n()).isEqualTo("210");
        assertThat(fila.get("ronda").n()).isEqualTo("1");
    }

    @Test
    @DisplayName("la clave de rango lleva la hora y el destino, para que dos intentos no se pisen")
    void laClaveDeRangoDistingueLosDestinos() {
        repositorio().registrar(pago, Resultado.entregado(Destino.ERP, 201, "ERP-20261028-Q7W2ZX"), 1, 1);

        verify(dynamo).putItem(peticion.capture());

        assertThat(peticion.getValue().item().get("ts").s())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z#ERP");
    }

    @Test
    @DisplayName("no guarda atributos vacios")
    void omiteLoQueNoVino() {
        // Un 201 no trae codigo de negocio ni mensaje de error.
        repositorio().registrar(pago, Resultado.entregado(Destino.BANCO, 201, ""), 1, 1);

        verify(dynamo).putItem(peticion.capture());
        Map<String, AttributeValue> fila = peticion.getValue().item();

        assertThat(fila).doesNotContainKeys("codigo", "mensaje", "referencia");
        assertThat(fila).containsKey("correlation_id");
    }

    @Test
    @DisplayName("si DynamoDB no responde, el pago no se cae por eso")
    void elFalloDeLaBitacoraNoTumbaElEnvio() {
        when(dynamo.putItem(any(PutItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("sin cuota").build());

        // Sin excepcion: la bitacora es para auditar, no para decidir.
        repositorio().registrar(pago, Resultado.entregado(Destino.BANCO, 201, "BNK-1"), 1, 1);
    }

    @Test
    @DisplayName("la marca de tiempo ordena igual como texto que en el tiempo")
    void laMarcaOrdenaComoTexto() {
        // Es el punto de los nueve digitos fijos: con un ISO normal, el
        // segundo con fraccion quedaria ANTES del segundo exacto.
        String exacto = Atributos.marcaDe(Instant.parse("2026-10-28T23:40:12Z"));
        String conFraccion = Atributos.marcaDe(Instant.parse("2026-10-28T23:40:12.100Z"));

        assertThat(exacto).isLessThan(conFraccion);
        assertThat(exacto).isEqualTo("2026-10-28T23:40:12.000000000Z");
    }
}