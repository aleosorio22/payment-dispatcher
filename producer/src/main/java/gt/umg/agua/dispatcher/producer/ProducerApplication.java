package gt.umg.agua.dispatcher.producer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * El producer: cada 30 segundos le pide pagos al monolito y los publica en SQS.
 *
 * <p>No expone ninguna API propia. El puerto HTTP existe solo para que
 * systemd pueda preguntarle si sigue vivo por /actuator/health.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ProducerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }
}