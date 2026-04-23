package org.orb.cli.commands;

import picocli.CommandLine.Command;

@Command(name = "serve", description = "Starts the ORB front-end & server to handle indexing and querying requests")
public class ServeCommand implements Runnable
{
	@Override
	public void run() {
        System.out.println("Starting ORB server...");
    }

}
