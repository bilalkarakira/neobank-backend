package com.bilal.neobank;

import org.springframework.boot.SpringApplication;

public class TestNeobankApplication {

	public static void main(String[] args) {
		SpringApplication.from(NeobankApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
