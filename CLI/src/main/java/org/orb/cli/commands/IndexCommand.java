package org.orb.cli.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.orb.cli.Models.IndexRequest;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Command;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@Command(name = "index", description = "Indexes a repository in ORB")
public class IndexCommand implements Runnable {

    @Parameters(paramLabel = "REPO_NAME",description = "Name of the repository to index")
    private String repoName;


    @Override
    public void run() {
        String home = System.getenv("USERPROFILE");
        String orbPath = Paths.get(String.valueOf(Path.of(home).resolve("documents")), "orb").toString();
        Path repoPath = Paths.get(orbPath, repoName);
        if(Files.notExists(repoPath)){
            System.err.println("Error: Repository not found in " + orbPath);
            System.err.println("Hint: Make sure to add the repository using 'orb add <REPO_LINK>' before indexing.");
            return;
        }
        else{
            try (Stream<Path> stream = Files.list(repoPath)) {
                if (stream.findAny().isEmpty()) {
                    System.err.println("Error: Repository directory is empty: " + repoPath);
                    System.err.println("Hint: Make sure the repository was cloned successfully and contains files.");
                    return;
                }
            } catch (IOException e) {
                System.err.println("Error: Unable to access repository directory: " + repoPath);
                e.printStackTrace();
                return;
            }

            HttpClient httpClient = HttpClient.newBuilder().build();
            ObjectMapper objectMapper = new ObjectMapper();
            IndexRequest body = new IndexRequest(repoName);
            String requestBody = null;
            try {
                requestBody = objectMapper.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/indexing/start"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("Indexing request sent for: " + repoName);
                System.out.println("Request body: " + requestBody);
                System.out.println("Response status: " + response.statusCode());
                System.out.println("Response body: " + response.body());
            } catch (IOException e) {
                System.err.println("Error: Failed to call indexing service. Is it running at http://localhost:8080?");
                e.printStackTrace();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Error: Request was interrupted.");
            }
        }
    }
}

