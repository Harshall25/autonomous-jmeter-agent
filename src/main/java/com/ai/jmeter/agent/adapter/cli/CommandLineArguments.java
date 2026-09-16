package com.ai.jmeter.agent.adapter.cli;

import java.util.Arrays;
import java.util.Optional;

/** Reads {@code --name=value} arguments, shared by the runners that take them. */
final class CommandLineArguments {

    private CommandLineArguments() {
    }

    /**
     * @param args   the raw command line
     * @param prefix the argument including its trailing {@code =}
     * @return the first non-blank value supplied for it
     */
    static Optional<String> value(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
