package org.alien4cloud.plugin.k8s.opa.modifier;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "alien4cloud-k8s-opa-plugin")
public class OPAConfiguration {

    private String image;

    private String service_name;
    private String service_url;
    private boolean service_insecure_tls;

    private String discovery_service;
    private String discovery_resource;
    private String discovery_decision;
    private int    discovery_polling_min_delay;
    private int    discovery_polling_max_delay;
}