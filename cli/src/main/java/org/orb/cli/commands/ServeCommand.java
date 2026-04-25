package org.orb.cli.commands;

import picocli.CommandLine.Command;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Command(name = "serve", description = "Starts the ORB front-end & server to handle indexing and querying requests")
public class ServeCommand implements Runnable
{
	@Override
	public void run() {
        try {
            // Check if the user's os is windows
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            Path cliRoot = findCliRoot();
            Path workspaceRoot = cliRoot.getParent();

            if (workspaceRoot == null) {
                throw new IOException("Cannot determine workspace root from: " + cliRoot);
            }
            //  Get inside client & server directories
            Path serverDir = workspaceRoot.resolve("api-server").toAbsolutePath().normalize();
            Path frontEndDir = workspaceRoot.resolve("front-end").toAbsolutePath().normalize();
            Path gradleWrapper = serverDir.resolve(isWindows ? "gradlew.bat" : "gradlew");

            //  Check if folder paths are valid/exists
            validateRequiredPath(serverDir, "Missing api-server directory");
            validateRequiredPath(frontEndDir, "Missing front-end directory");
            validateRequiredPath(gradleWrapper, "Missing Gradle wrapper for api-server");

            //  Create process builder for the backend
            ProcessBuilder serverProcessBuilder = new ProcessBuilder("cmd", "/c",gradleWrapper.toString(), "bootRun")
                .directory(serverDir.toFile())
                .redirectErrorStream(true);
            Process serverProcess = serverProcessBuilder.start();
            streamLogs(serverProcess, "[SERVER]");
            //  Create process builder for the front-end
            ProcessBuilder frontEndProcessBuilder = new ProcessBuilder("cmd", "/c","npm", "run", "dev")
                .directory(frontEndDir.toFile())
                .redirectErrorStream(true);
            Process frontEndProcess = frontEndProcessBuilder.start();
            streamLogs(frontEndProcess, "[FRONT-END]");

            //  Check if port is occupied by frontend and open in browser
            URI frontEndUri = URI.create("http://localhost:3000");
            waitForFrontendReady(frontEndProcess, frontEndUri, Duration.ofMinutes(2));
            openBrowser(frontEndUri);

            // Kill the processes after stopping the running threads
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[ORB] Shutting down...");
                //  Kill the entire tree including the sub descendants created by the main process.
                killTree(serverProcess);
                killTree(frontEndProcess);
            }));

            Thread.currentThread().join();

            } catch (IOException | InterruptedException e) {
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
                    System.err.println(tag + " log stream ended with an error: " + e.getMessage());
            }
        }).start();
    }

    private static void waitForFrontendReady(Process frontEndProcess, URI uri, Duration timeout) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (!frontEndProcess.isAlive()) {
                throw new IOException("Front-end process exited before it became ready");
            }

            if (isReachable(uri)) {
                System.out.println("[ORB] Front-end is ready at " + uri);
                return;
            }

            TimeUnit.SECONDS.sleep(1);
        }

        throw new IOException("Timed out waiting for front-end to start at " + uri);
    }

    private static boolean isReachable(URI uri) {
        HttpURLConnection connection = null;
        try {
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void openBrowser(URI uri) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            System.out.println("[ORB] Desktop browsing is not supported. Open this URL manually: " + uri);
            return;
        }

        Desktop.getDesktop().browse(uri);
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

    private void killTree(Process process) {
        //  Convert process object to ProcessHandler which gives more metadata
        ProcessHandle handle = process.toHandle();
        //  Iterates through each sub processes and stops them from executing before killing the main process to avoid orphan processes
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        // Stops/Kills the main process
        handle.destroyForcibly();
    }

    private static void validateRequiredPath(Path path, String message) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException(message + ": " + path);
        }
    }

}
