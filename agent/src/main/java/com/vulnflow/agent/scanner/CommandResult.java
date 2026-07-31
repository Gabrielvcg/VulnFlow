package com.vulnflow.agent.scanner;

public record CommandResult(int exitCode, boolean timedOut, String stdout, String stderr) {
}
