package org.orb.cli.commands;

import picocli.CommandLine.Command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Command(name = "serve", description = "Starts the ORB front-end & server to handle indexing and querying requests")
public class ServeCommand implements Runnable
{
	@Override
	public void run() {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            Path cliRoot = findCliRoot();
            Path workspaceRoot = cliRoot.getParent();

            if (workspaceRoot == null) {
                throw new IOException("Cannot determine workspace root from: " + cliRoot);
            }

            Path serverDir = workspaceRoot.resolve("api-server").toAbsolutePath().normalize();
            Path frontEndDir = workspaceRoot.resolve("front-end").toAbsolutePath().normalize();
            Path gradleWrapper = serverDir.resolve(isWindows ? "gradlew.bat" : "gradlew");

            validateRequiredPath(serverDir, "Missing api-server directory");
            validateRequiredPath(frontEndDir, "Missing front-end directory");
            validateRequiredPath(gradleWrapper, "Missing Gradle wrapper for api-server");
            System.out.println(serverDir.toString());
            System.out.println(frontEndDir.toString());
            ProcessBuilder serverProcessBuilder = new ProcessBuilder("cmd", "/c",gradleWrapper.toString(), "bootRun")
                .directory(serverDir.toFile())
                .redirectErrorStream(true);
            Process serverProcess = serverProcessBuilder.start();
            streamLogs(serverProcess, "[SERVER]");
            ProcessBuilder frontEndProcessBuilder = new ProcessBuilder("cmd", "/c","npm", "run", "dev")
                .directory(frontEndDir.toFile())
                .redirectErrorStream(true);
            Process frontEndProcess = frontEndProcessBuilder.start();
            streamLogs(frontEndProcess, "[FRONT-END]");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[ORB] Shutting down...");
                serverProcess.destroy();
                frontEndProcess.destroy();
            }));

            Thread.currentThread().join();

        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void streamLogs(Process process, String tag) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(tag + " " + line);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static Path findCliRoot() throws IOException {
        Path probe = Paths.get("").toAbsolutePath().normalize();

        while (probe != null) {
            Path gradleWrapper = probe.resolve("gradlew.bat");
            Path settings = probe.resolve("settings.gradle.kts");

            if (Files.exists(gradleWrapper) && Files.exists(settings)) {
                return probe;
            }

            probe = probe.getParent();
        }

        throw new IOException("Could not locate CLI root (expected settings.gradle.kts and gradlew.bat in a parent directory)");
    }

    private static void validateRequiredPath(Path path, String message) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException(message + ": " + path);
        }
    }

}
