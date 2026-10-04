package com.bilal.neobank.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled; individual jobs are switched on or off by their own properties. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
