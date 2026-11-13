package me.niteshh.nexoraflow.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nexoraFlowOpenApi(NexoraProperties properties) {
        return new OpenAPI().info(new Info()
                .title("NexoraFlow API")
                .version("1.0.0")
                .description("""
                        Employee leave, expense and approval workflow automation for the fictional company
                        Nexora Technologies. All business decisions are deterministic, rule-based and explainable
                        (business rules version %s). Actors are identified by employee ID in request bodies;
                        authentication is intentionally out of scope.
                        """.formatted(properties.businessRulesVersion())));
    }
}
