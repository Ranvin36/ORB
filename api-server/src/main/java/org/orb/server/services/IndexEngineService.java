package org.orb.server.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.neo4j.driver.Driver;
import org.orb.server.models.GraphInMemory;
import org.orb.server.services.scanning.MetadataScanner;
import org.springframework.stereotype.Service;
import org.treesitter.TSException;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

@Service
public class IndexEngineService {
    private final GraphInMemory graphInMemory;
    private final MetadataScanner metadataScanner;

    /**
     * Creates a new indexing service with an empty in-memory graph.
     */
    public IndexEngineService(Driver driver) {
        this.graphInMemory = new GraphInMemory(driver);
        this.metadataScanner = new MetadataScanner();
    }

    /**
     * Locates the repository directory under the user's Documents folder.
     * Returns an Optional containing the Path if the directory exists and contains
     * at least one entry.
     * Returns Optional.empty() if not found or on invalid input.
     *
     * @param repositoryName repository folder name to search under Documents/orb
     * @return optional path to the repository when found and non-empty
     * @throws IOException if directory access fails while checking repository
     *                     contents
     */
    public Optional<Path> locateRepositoryInFileSystem(String repositoryName) throws IOException {
        if (repositoryName == null || repositoryName.isBlank()) {
            return Optional.empty();
        }

        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return Optional.empty();
        }

        Path repoPath = Path.of(userHome, "Documents", "orb", repositoryName);

        if (Files.exists(repoPath) && Files.isDirectory(repoPath)) {
            // Use DirectoryStream to avoid leaving an open stream
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(repoPath)) {
                if (ds.iterator().hasNext()) {
                    return Optional.of(repoPath);
                } else {
                    // directory exists but is empty -> treat as not found (matches original
                    // behavior)
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Parses a single Java file using tree-sitter and orchestrates pass-1 scanning or pass-2 graph building.
     * Reads the file as UTF-8, parses it into a concrete syntax tree.
     * For pass-1 (SCANNING): extracts package, imports, and declarations via MetadataScanner.
     * For pass-2 (BUILDING): resolves method invocations via GraphBuilder using pass-1 metadata.
     *
     * @param path   Java source file path
     * @param parser configured tree-sitter Java parser
     * @param stage  indexing stage (SCANNING or BUILDING)
     */
    private void parseFile(Path path, TSParser parser, IndexingStage stage) {
        try {
            // Read the content in the file
            String fileContents = Files.readString(path);
            byte[] fileBytes = fileContents.getBytes(StandardCharsets.UTF_8);
            // Parse the file to a tree
            TSTree tsTree = parser.parseString(null, fileContents);
            TSNode rootNode = tsTree.getRootNode();
            if(stage == IndexingStage.SCANNING){
                this.metadataScanner.scanRepository(String.valueOf(path), rootNode, fileBytes, this.graphInMemory);
                return;
            }
            GraphBuilder graphBuilder = new GraphBuilder(metadataScanner);
            // Go through the tree to extract symbols
            graphBuilder.buildGraph(rootNode,fileBytes,null,null,new HashMap<>(), this.graphInMemory);
        } catch (IOException | TSException e) {
            System.err.println("Error reading file: " + path + " - " + e.getMessage());
        }
    }

    /**
     * Orchestrates a two-pass indexing of the repository.
     * Pass 1 (SCANNING): collects package, imports, class/method declarations, and variable declarations.
     * Pass 2 (BUILDING): resolves method invocations using pass-1 metadata.
     *
     * @param repoPath repository root path to index
     * @throws IOException if directory walking fails or file I/O errors occur
     */
    public void parseRepository(Path repoPath) throws IOException {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        List<Path> javaFiles = new ArrayList<>();
        // Collect all Java files in the repository
        try (var stream = Files.walk(repoPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        
        // Pass 1: Scan for declarations and variable types
        for (Path javaFile : javaFiles) {
            parseFile(javaFile, parser, IndexingStage.SCANNING);
        }

        // Pass 2: Build invocation graph using pass-1 metadata
        for (Path javaFile : javaFiles) {
            parseFile(javaFile, parser, IndexingStage.BUILDING);
        }
    }

    /**
     * Initiates the full indexing pipeline for a given repository name.
     * Locates the repository under Documents/orb, clears any previous graph state,
     * triggers parseRepository to extract all classes/methods/invocations via two-pass indexing,
     * then writes the final graph to JSON output.
     *
     * @param repoName repository folder name to index
     * @return optional repository path when indexing starts successfully; empty otherwise
     * @throws IOException if repository lookup or file I/O fails during indexing
     */
    public Optional<Path> startIndexing(String repoName) throws IOException {
        Optional<Path> repo = locateRepositoryInFileSystem(repoName);
        if (repo.isPresent()) {

            System.out.println("Starting indexing for: " + repo.get().toString());
            // Clear the graph before indexing to ensure no previous nodes exist
            this.graphInMemory.resetGraph();
            this.metadataScanner.reset();
            // Start indexing the repository
            parseRepository(repo.get());
//            this.graphInMemory.pushToNeo4J();
            this.graphInMemory.writeToJson();
            return repo;
        } else {
            System.out.println("Cannot start indexing; repository not found: " + repoName);
        }
        return Optional.empty();

    }

}
