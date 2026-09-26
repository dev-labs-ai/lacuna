package com.lacuna;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LacunaApplication {

    public static void main(String[] args) {
        SpringApplication.run(LacunaApplication.class, args);
    }

}
