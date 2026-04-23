package org.orb.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import org.orb.cli.commands.AddCommand;
import org.orb.cli.commands.IndexCommand;
import org.orb.cli.commands.ServeCommand;

@Command(
        name = "orb",
        description = "ORB Cli - Code Intelligence Platform",
        mixinStandardHelpOptions = true,
        subcommands = {AddCommand.class, IndexCommand.class, ServeCommand.class}
)
public class OrbCLI {
    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new OrbCLI());
        if (args.length == 0) {
            commandLine.usage(System.out);
            return;
        }

        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

}
