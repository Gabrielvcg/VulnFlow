package com.vulnflow.agent.config;

public class AgentConfigurationException extends RuntimeException {

    public AgentConfigurationException(String message) {
        super(message);
    }

    public AgentConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
