package org.orb.cli.commands;

import org.neo4j.driver.Driver;
import org.orb.cli.configs.Neo4jConfig;
import org.orb.cli.services.IndexEngineService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "index", description = "Indexes a repository in ORB")
public class IndexCommand implements Callable<Integer> {

    @Parameters(paramLabel = "REPO_NAME", description = "Name of the repository to index")
    private String repoName;

    @Override
    public Integer call() {
        String home = System.getenv("USERPROFILE");
        String orbPath = Paths.get(String.valueOf(Path.of(home).resolve("documents")), "orb").toString();
        Path repoPath = Paths.get(orbPath, repoName);

        if (Files.notExists(repoPath)) {
            System.err.println("Error: Repository not found in " + orbPath);
            System.err.println("Hint: Make sure to add the repository using 'orb add <REPO_LINK>' before indexing.");
            return 1;
        } else {
            try (Stream<Path> stream = Files.list(repoPath)) {
                if (stream.findAny().isEmpty()) {
                    System.err.println("Error: Repository directory is empty: " + repoPath);
                    System.err.println("Hint: Make sure the repository was cloned successfully and contains files.");
                    return 1;
                }
            } catch (IOException e) {
                System.err.println("Error: Unable to access repository directory: " + repoPath);
                e.printStackTrace();
                return 1;
            }
            try {
                IndexEngineService indexEngineService = new IndexEngineService();
                Optional<Path> indexedRepo = indexEngineService.startIndexing(repoName);
                if (indexedRepo.isPresent()) {
                    System.out.println("Indexing completed successfully for repository: " + repoName);
                    return 0; // Success
                } else {
                    System.err.println("Indexing failed for repository: " + repoName);
                    return 1; // Failure
                }
            } catch (Exception e) {
                System.err.println("Error during indexing: " + e.getMessage());
                e.printStackTrace();
                return 1; // Failure
            } finally {
                System.out.println("Indexing completed for repository: " + repoName);
            }
        }
    }
}
