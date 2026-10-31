package gt.umg.agua.dispatcher.facturador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * El facturador: toma los pagos cobrados y emite la factura electronica.
 *
 * <p>Vive aparte del consumer a proposito. Entregar un pago al banco y
 * facturarlo son dos obligaciones distintas, con dos destinos que fallan de
 * maneras distintas: si Digifact se cae, el banco no tiene por que enterarse.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FacturadorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacturadorApplication.class, args);
    }

    /**
     * Sin credenciales: la cadena por defecto del SDK encuentra las del rol
     * IAM de la instancia. La region sale de la misma propiedad que usa SQS
     * para que no puedan quedar apuntando a lugares distintos.
     */
    @Bean
    DynamoDbClient dynamoDbClient(
            @org.springframework.beans.factory.annotation.Value("${spring.cloud.aws.region.static}")
            String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }
}
