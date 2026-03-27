package org.orb.cli.commands;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.orb.cli.Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "add", description = "Clones the repository into the local file system")
public class AddCommand implements Callable<Integer> {
    private final Path localPath;

    @Parameters(paramLabel = "REPO_LINK", description = "Repository URL to clone")
    private String repoLink;

    public AddCommand() {
        this(Path.of("C:\\Users\\User\\Documents\\Orb"));
    }

    AddCommand(Path localPath) {
        this.localPath = localPath;
    }

    @Override
    public Integer call() {
        try {
            Files.createDirectories(localPath);
            String repoName = Utils.extractRepoName(repoLink);
            Path destination = localPath.resolve(repoName);

            if (Files.exists(destination) && Utils.isNotEmptyDirectory(destination)) {
                System.err.println("Error: Destination already exists and is not empty: " + destination);
                System.err.println("Hint: remove it, choose another folder name, or clone a different repository.");
                return 1;
            }

            System.out.println("Cloning repository from " + repoLink + " into " + destination);
            try (Git ignored = Git.cloneRepository()
                    .setURI(repoLink)
                    .setDirectory(new File(destination.toUri()))
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()) {
                System.out.println("Repository cloned successfully into: " + destination);
            }
            return 0;
        } catch (InvalidRemoteException e) {
            System.err.println("Error: The repository URL looks invalid or does not exist.");
            return 1;
        } catch (TransportException e) {
            System.err.println("Error: Could not access remote repository. "
                    + "Check network/authentication and try again.");
            return 1;
        } catch (JGitInternalException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (GitAPIException e) {
            System.err.println("Error: Failed to clone repository: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("Error: Could not write to local path: " + localPath);
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Error: Unexpected failure: " + e.getMessage());
            return 1;
        }
    }
}
