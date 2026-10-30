package gt.umg.agua.dispatcher.consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;


/**
 * Los clientes de DynamoDB.
 *
 * <p>No se configuran credenciales: la cadena por defecto del SDK encuentra
 * las del rol IAM de la instancia. No hay ninguna llave en el codigo ni en
 * el servidor.
 */
@Configuration
public class AwsConfig {

    /**
     * La region sale de la misma propiedad que usa SQS, para que no puedan
     * quedar apuntando a regiones distintas.
     */
    @Bean
    DynamoDbClient dynamoDbClient(@Value("${spring.cloud.aws.region.static}") String region) {
        return DynamoDbClient.builder().region(Region.of(region)).build();
    }
}