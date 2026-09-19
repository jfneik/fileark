package com.fileark.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FileArkApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileArkApplication.class, args);
    }
}
