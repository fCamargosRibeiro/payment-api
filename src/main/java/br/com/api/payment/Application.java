package br.com.api.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaRepositories(basePackages = "br.com.api.payment")
public class Application {

    static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
