package gt.umg.agua.dispatcher.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * El consumer: lee los pagos de SQS, los entrega al banco y al ERP municipal,
 * y deja el rastro de cada intento en DynamoDB.
 *
 * <p>Los clientes HTTP de los dos destinos no se escriben: los genera
 * openapi-generator a partir de los contratos del inge, y viven en los
 * paquetes que declara @EnableFeignClients.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = {
        "gt.umg.agua.dispatcher.banco.api",
        "gt.umg.agua.dispatcher.erp.api"})
public class ConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}