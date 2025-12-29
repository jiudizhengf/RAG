package org.example.rag;

import org.example.rag.config.DotenvInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RagApplication.class);
        app.addInitializers(new DotenvInitializer());
        app.run(args);
    }
}
