package com.tanvir.video;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VideoApplication {

	public static void main(String[] args) throws Exception {
		// Ensure work directory exists before SQLite datasource initializes
		Files.createDirectories(Path.of(System.getProperty("user.dir"), "work"));
		SpringApplication.run(VideoApplication.class, args);
	}

}
