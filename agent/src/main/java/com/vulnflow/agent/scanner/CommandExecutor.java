package com.vulnflow.agent.scanner;

import java.time.Duration;
import java.util.List;

public interface CommandExecutor {

    CommandResult execute(List<String> arguments, Duration timeout, int captureLimitBytes);
}
