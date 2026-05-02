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

            // Load Neo4j configuration from orb.properties
            String neo4jUri;
            String neo4jUsername;
            String neo4jPassword;

            try (InputStream in = IndexCommand.class.getClassLoader().getResourceAsStream("orb.properties")) {
                if (in == null) {
                    System.err.println("Error: orb.properties not found on classpath. Please provide orb.properties with Neo4j connection details.");
                    return 1;
                }
                Properties props = new Properties();
                props.load(in);
                neo4jUri = props.getProperty("neo4j.uri");
                neo4jUsername = props.getProperty("neo4j.authentication.username");
                neo4jPassword = props.getProperty("neo4j.authentication.password");

                if (neo4jUri == null || neo4jUri.isBlank() ||
                    neo4jUsername == null || neo4jUsername.isBlank() ||
                    neo4jPassword == null || neo4jPassword.isBlank()) {
                    System.err.println("Error: Missing or empty Neo4j connection properties in orb.properties (neo4j.uri, neo4j.authentication.username, neo4j.authentication.password)");
                    return 1;
                }
            } catch (IOException e) {
                System.err.println("Error: Failed to read orb.properties: " + e.getMessage());
                return 1;
            }

            Neo4jConfig neo4jConfig = new Neo4jConfig(neo4jUri, neo4jUsername, neo4jPassword);
            Driver driver = null;
            try {
                driver = neo4jConfig.neo4jDriver();
                IndexEngineService indexEngineService = new IndexEngineService(driver);
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
                neo4jConfig.closeDriver(driver);
            }
        }
    }
}
