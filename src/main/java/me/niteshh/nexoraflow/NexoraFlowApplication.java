package me.niteshh.nexoraflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NexoraFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexoraFlowApplication.class, args);
    }

}
