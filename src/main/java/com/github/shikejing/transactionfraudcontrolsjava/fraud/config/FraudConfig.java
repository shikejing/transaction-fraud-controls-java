package com.github.shikejing.transactionfraudcontrolsjava.fraud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires fraud configuration properties.
 */
@Configuration
@EnableConfigurationProperties(FraudProperties.class)
public class FraudConfig {
}
