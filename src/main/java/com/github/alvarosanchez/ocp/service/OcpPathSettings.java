package com.github.alvarosanchez.ocp.service;

public final class OcpPathSettings {

    public static final String CONFIG_DIR_PROPERTY = "ocp.config.dir";
    public static final String CACHE_DIR_PROPERTY = "ocp.cache.dir";
    public static final String OPENCODE_CONFIG_DIR_PROPERTY = "ocp.opencode.config.dir";
    public static final String WORKING_DIR_PROPERTY = "ocp.working.dir";

    public static final String CONFIG_DIR_ENV = "OCP_CONFIG_DIR";
    public static final String CACHE_DIR_ENV = "OCP_CACHE_DIR";
    public static final String OPENCODE_CONFIG_DIR_ENV = "OCP_OPENCODE_DIR";
    public static final String WORKING_DIR_ENV = "OCP_WORKING_DIR";

    private OcpPathSettings() {
    }

    public static String configuredPath(String propertyName, String envName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return null;
    }
}
