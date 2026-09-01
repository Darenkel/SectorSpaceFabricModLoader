package com.sector.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class KnotLauncher {

    public static void main(String[] args) {
        String savedPath = LocVerifierCFG.getProperty("game_path");
        String bridgeJarPath;

        try {
            bridgeJarPath = new File(KnotLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            bridgeJarPath = new File(KnotLauncher.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getAbsolutePath();
        }

        // Mirror all SSFML console output to SSFML_startup_log.txt.
        File logTargetFolder = (savedPath != null) ? new File(savedPath).getParentFile() : new File(System.getProperty("user.dir"));
        StartupLogger.install(logTargetFolder);

        if (savedPath == null || !new File(savedPath).exists()) {
            File bridgeFolder = new File(bridgeJarPath).getParentFile();

            if (LocVerifierApp.tryAutoDetect(bridgeFolder)) {
                savedPath = LocVerifierCFG.getProperty("game_path");
            } else {
                System.out.println("SSFML: Game not found. Opening Setup UI...");
                LocVerifierApp.main(new String[0]);

                savedPath = LocVerifierCFG.getProperty("game_path");
                if (savedPath == null || !new File(savedPath).exists()) {
                    System.out.println("SSFML: Setup was not completed. Exiting.");
                    return;
                }
            }
        }

        System.out.println("SSFML: Game found at: " + savedPath);
        ensureFabricLibs(savedPath);
        launch(savedPath, bridgeJarPath);
    }


    /**
     * Builds the Fabric/Knot launch command using:
     * - the game JAR
     * - extracted runtime libs
     * - mods/ directly, after syncing enable/disable state onto the files there
     */
    public static void launch(String gameJarPath, String bridgeJarPath) {
        File gameFolder = new File(gameJarPath).getParentFile();
        File modsFolder = new File(gameFolder, "mods");

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ModLoader mLoader = new ModLoader();

        try {
            // Syncs mod_list.cfg against mods/ and renames each jar in place to match its true/false state (.jar <-> .jar.disabled).
            mLoader.applyModState(gameFolder);

            List<String> cpParts = new ArrayList<>();
            cpParts.add(new File(bridgeJarPath).getAbsolutePath());
            cpParts.add(new File(gameJarPath).getAbsolutePath());

            File libsFolder = new File(gameFolder, "libs");
            if (libsFolder.exists()) {
                File[] libJars = libsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (libJars != null) {
                    for (File jar : libJars) {
                        if (!cpParts.contains(jar.getAbsolutePath())) {
                            cpParts.add(jar.getAbsolutePath());
                        }
                    }
                }
            }

            String finalCp = String.join(File.pathSeparator, cpParts);

            List<String> pbCommand = new ArrayList<>();
            pbCommand.add(javaBin);
            pbCommand.add("-Djava.library.path=" + new File(gameFolder, "natives").getAbsolutePath());
            pbCommand.add("-Dfabric.gameJarPath=" + gameJarPath);
            pbCommand.add("-Dfabric.modsFolder=" + modsFolder.getAbsolutePath());
            pbCommand.add("-cp");
            pbCommand.add(finalCp);
            pbCommand.add("net.fabricmc.loader.impl.launch.knot.KnotClient");

            ProcessBuilder pb = new ProcessBuilder(pbCommand);
            pb.directory(gameFolder);

            pb.redirectErrorStream(true);

            System.out.println("SSFML: Launching Fabric with enabled mods in: " + modsFolder.getAbsolutePath());
            Process gameProcess = pb.start();
            System.out.println("SSFML: Game process started (PID " + gameProcess.pid() + ").");

            Thread outputPump = getThread(gameProcess);
            outputPump.start();

            // The parent process now has to stay alive for the pump thread above to keep reading -
            // once this process exits, so does its ability to see anything more the child prints.
            int exitCode = gameProcess.waitFor();
            System.out.println("SSFML: Game process exited with code " + exitCode + ".");
            System.exit(exitCode);

        } catch (ModLoader.LaunchAbortedException e) {
            System.out.println("SSFML: " + e.getMessage() + " Not launching the game.");
        } catch (Exception e) {
            System.err.println("SSFML: Failed to launch game process: " + e);
            e.printStackTrace();
        }
    }

    private static Thread getThread(Process gameProcess) {
        Thread outputPump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(gameProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    StartupLogger.log(classifyGameLine(line), "Game: " + line);
                }
            } catch (IOException e) {
                System.err.println("SSFML: Lost the game process's output stream: " + e.getMessage());
            }
        }, "SSFML-game-output-pump");
        outputPump.setDaemon(true);
        return outputPump;
    }

    private static final Pattern FABRIC_LEVEL_PATTERN = Pattern.compile("\\[\\d{2}:\\d{2}:\\d{2}\\]\\s*\\[(INFO|WARN|ERROR|DEBUG|TRACE|FATAL)\\]");

    /**
     * Best-effort classification of a single line forwarded from the game process. Since redirectErrorStream(true) merges stdout/stderr before this ever sees it,
     * there's no stream-based signal left to judge severity from, only the text itself.
     * <p>
     * In priority order:
     * - Fabric Loader's own bracketed level tag ("[09:24:44] [WARN] [FabricLoader/Mixin]: Text"),
     *   trusted directly since Fabric already knows the real severity of its own lines.
     * - The game engine's own "* Error:"/"Caused by:" prefixes and raw stack trace frames ("at text").
     * - A line explicitly labeled "Warning:" by the engine itself, or "WARNING:" by the JVM's own
     *   native-access notices - checked BEFORE the broader Exception/Error substring fallback below,
     *   so a line like "Warning: Failed to verify ... FileNotFoundException" is trusted as the WARN
     *   it's explicitly labeled as, not bumped to ERROR just because it happens to mention an
     *   exception type in passing.
     * - Anything else containing the word "Exception" or "Error" as a last-resort fallback, for
     *   crash text that isn't explicitly prefixed at all (e.g. a bare stack trace's first line).
     * <p>
     * This is a heuristic, a line that happens to contain the word "Exception" or "Error" in an otherwise benign context
     * would still get classified as ERROR. Given the alternative is everything defaulting to INFO, false positives here are the 'safer'' failure mode.
     */
    private static String classifyGameLine(String line) {
        Matcher fabricLevel = FABRIC_LEVEL_PATTERN.matcher(line);
        if (fabricLevel.find()) {
            return fabricLevel.group(1);
        }

        String trimmed = line.trim();

        if (trimmed.startsWith("* Error:")
                || trimmed.startsWith("Caused by:")
                || trimmed.startsWith("at ")) {
            return "ERROR";
        }

        if (trimmed.regionMatches(true, 0, "warning:", 0, "warning:".length())) {
            return "WARN";
        }

        if (trimmed.contains("Exception") || trimmed.contains("Error")) {
            return "ERROR";
        }

        return "INFO";
    }

    /**
     * Ensures the local Fabric dependency set exists in the project or local game folder.
     */
    private static void ensureFabricLibs(String gameJarPath) {
        File gameFolder = new File(gameJarPath).getParentFile();
        File gameJar = new File(gameJarPath);
        File libsDir = new File(gameFolder, "libs");

        if (!libsDir.exists() && !libsDir.mkdirs()) {
            System.err.println("SSFML: Could not create libraries directory: "
                    + libsDir.getAbsolutePath());
            return;
        }

        if (!libsDir.isDirectory()) {
            System.err.println("SSFML: Libraries path is not a directory: "
                    + libsDir.getAbsolutePath());
            return;
        }

        sweepStaleDownloads(libsDir);
        unpackInternalLibs(gameJar, libsDir);

        String[] requiredLibs = {
                "fabric-loader-0.18.4.jar",
                "sponge-mixin-0.15.3+mixin.0.8.7.jar",
                "asm-9.9.jar",
                "asm-analysis-9.9.jar",
                "asm-commons-9.9.jar",
                "asm-tree-9.9.jar",
                "asm-util-9.9.jar"};

        List<String> failedLibs = new ArrayList<>();

        for (String libName : requiredLibs) {
            File target = new File(libsDir, libName);

            // If the dependency already exists and is a valid JAR, nothing needs to be done.
            if (isValidJar(target)) {
                continue;
            }

            // Remove any incomplete or corrupt JAR before attempting to obtain it.
            if (target.exists()) {
                System.out.println("SSFML: Invalid or corrupt dependency found: " + libName);

                if (!target.delete()) {
                    System.err.println("SSFML: Could not remove invalid dependency: "
                            + target.getAbsolutePath());
                }
            }

            System.out.println("SSFML: Checking for dependency: " + libName);

            boolean obtained = false;

            // First try to extract the library from the loader's own JAR.
            try (InputStream is = KnotLauncher.class.getResourceAsStream("/" + libName)) {
                if (is != null) {
                    System.out.println("SSFML: Extracting " + libName + " from resources...");
                    java.nio.file.Files.copy(is, target.toPath());

                    if (isValidJar(target)) {
                        obtained = true;
                        System.out.println("SSFML: " + libName + " extracted from resources successfully.");
                    } else {
                        System.err.println("SSFML: Extracted " + libName + " is invalid or corrupt.");

                        if (!target.delete()) {
                            System.err.println("SSFML: Could not remove invalid extracted dependency: "
                                    + target.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("SSFML: Error extracting " + libName + ": " + e.getMessage());

                if (target.exists() && !target.delete()) {
                    System.err.println("SSFML: Could not remove incomplete dependency: "
                            + target.getAbsolutePath());
                }
            }

            // Checks primary URL then secondary URL, if both don't work print an error.
            if (!obtained) {
                List<String> urls = getUrls(libName);

                System.out.println("SSFML: " + libName + " not in local resources. Attempting download...");

                for (String url : urls) {
                    if (downloadLib(url, target)) {
                        obtained = true;
                        break;
                    }

                    System.out.println("SSFML: Retrying " + libName + " from a different source...");
                }
            }

            if (obtained) {
                stripSignatures(target);
            } else {
                List<String> urls = getUrls(libName);
                failedLibs.add(libName + " (tried: " + String.join(", ", urls) + ")");

                if (target.exists() && !target.delete()) {
                    System.err.println("SSFML: Could not remove invalid dependency: "
                            + target.getAbsolutePath());
                }
            }
        }

        if (!failedLibs.isEmpty()) {
            writeLibErrorReport(gameFolder, failedLibs);
        } else {
            System.out.println("SSFML: All required libraries verified.");
        }
    }

    /**
     * Removes any leftover .download temp files from a previous run that got killed
     * mid-download, so they don't just sit in libs/ indefinitely.
     */
    private static void sweepStaleDownloads(File libsDir) {
        File[] staleDownloads = libsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".download"));
        if (staleDownloads == null || staleDownloads.length == 0) {
            return;
        }

        int removed = 0;
        for (File stale : staleDownloads) {
            if (stale.delete()) {
                removed++;
            } else {
                System.err.println("SSFML: Could not remove stale download artifact: " + stale.getAbsolutePath());
            }
        }

        if (removed > 0) {
            System.out.println("SSFML: Swept " + removed + " leftover .download file(s) from a previous interrupted run.");
        }
    }

    /**
     * Checks whether a file is a valid JAR.
     */
    static boolean isValidJar(File file) {
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return false;
        }

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
            // A valid dependency JAR should contain at least one entry.
            if (jar.size() == 0) {
                return false;
            }

            // Make sure the archive can actually enumerate its contents.
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            return entries.hasMoreElements();
        } catch (Exception e) {
            return false;
        }
    }

    // Lib backup Urls.
    private static List<String> getUrls(String libName) {
        List<String> urls = new ArrayList<>();
        if (libName.contains("fabric-loader")) {
            urls.add("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.18.4/fabric-loader-0.18.4.jar");
            urls.add("https://repo1.maven.org/maven2/net/fabricmc/fabric-loader/0.18.4/fabric-loader-0.18.4.jar");
        } else if (libName.contains("sponge-mixin")) {
            urls.add("https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.15.3%2Bmixin.0.8.7/sponge-mixin-0.15.3%2Bmixin.0.8.7.jar");
            urls.add("https://repo1.maven.org/maven2/net/fabricmc/sponge-mixin/0.15.3%2Bmixin.0.8.7/sponge-mixin-0.15.3%2Bmixin.0.8.7.jar");
        } else if (libName.contains("asm-9.9")) {
            urls.add("https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm/9.9/asm-9.9.jar");
            urls.add("https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.jar");
        } else if (libName.contains("asm-analysis")) {
            urls.add("https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.jar");
            urls.add("https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.jar");
        } else if (libName.contains("asm-commons")) {
            urls.add("https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.jar");
            urls.add("https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.jar");
        } else if (libName.contains("asm-tree")) {
            urls.add("https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar");
            urls.add("https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar");
        } else if (libName.contains("asm-util")) {
            urls.add("https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-util/9.9/asm-util-9.9.jar");
            urls.add("https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.9/asm-util-9.9.jar");
        }
        return urls;
    }

    /**
     * Writes a plain-text summary of any dependencies that couldn't be obtained.
     */
    private static void writeLibErrorReport(File gameFolder, List<String> failedLibs) {
        File reportFile = new File(gameFolder, "libs_download_errors.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("SSFML could not obtain the following required libraries:\n\n");
        for (String entry : failedLibs) {
            sb.append(" - ").append(entry).append("\n");
        }
        sb.append("\nThe game will likely fail to launch until these are placed manually in:\n");
        sb.append(new File(gameFolder, "libs").getAbsolutePath()).append("\n");
        sb.append("\nCheck your internet connection and try again, or download the jar(s) above manually.\n");

        try {
            Files.writeString(reportFile.toPath(), sb.toString());
            System.err.println("SSFML: Some dependencies failed to download. See " + reportFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("SSFML: failed to write lib error report: " + e.getMessage());
        }
    }

    /**
     * Downloads a Fabric/ASM dependency into the target folder.
     * Returns true on success, false on any failure.
     */
    private static boolean downloadLib(String urlString, File destination) {
        File tempFile = new File(destination.getAbsolutePath() + ".download");

        try {
            System.out.println("SSFML: Downloading: " + destination.getName() + "...");
            URL website = URI.create(urlString).toURL();

            HttpURLConnection connection = (HttpURLConnection) website.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            // 1. Tell Java to follow the server's redirect to the actual file
            connection.setInstanceFollowRedirects(true);

            // 2. Check if the server actually says "OK" (Status 200)
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                // This stops the code from saving a 10KB error page as a JAR
                throw new Exception("SSFML: Server returned error code: " + status);
            }

            try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }

            // Validate the completed download before replacing the real JAR.
            if (!isValidJar(tempFile)) {
                throw new Exception("SSFML: Downloaded file is not a valid JAR");
            }

            Files.move(
                    tempFile.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            System.out.println("SSFML: Download complete.");
            return true;
        } catch (Exception e) {
            System.err.println("SSFML: Failed to download " + destination.getName()
                    + " from " + urlString + ": " + e.getMessage());

            // Remove any incomplete download so it cannot be mistaken for a valid dependency.
            if (tempFile.exists() && !tempFile.delete()) {
                System.err.println("SSFML: Could not remove incomplete download: "
                        + tempFile.getAbsolutePath());
            }

            return false;
        }
    }

    /**
     * Removes signature metadata from a JAR if it causes conflict with Fabric.
     */
    private static void stripSignatures(File jarFile) {
        if (!needsStripping(jarFile)) {
            System.out.println("SSFML: " + jarFile.getName() + " has nothing to strip, skipping.");
            return;
        }

        File tempFile = new File(jarFile.getAbsolutePath() + ".tmp");

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(jarFile));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tempFile))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();

                // Skip signature files in META-INF (.SF, .RSA, .DSA)
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue;
                }

                // Skip the fabric.mod.json ONLY if this is the fabric-loader jar
                // This prevents the "Mods share ID" conflict
                if (jarFile.getName().contains("fabric-loader") && name.equals("fabric.mod.json")) {
                    System.out.println("SSFML: Stripping fabric.mod.json from "
                            + jarFile.getName() + " to prevent conflict.");
                    continue;
                }

                zout.putNextEntry(new ZipEntry(name));

                byte[] buffer = new byte[4096];
                int len;
                while ((len = zin.read(buffer)) > 0) {
                    zout.write(buffer, 0, len);
                }

                zout.closeEntry();
            }
        } catch (IOException e) {
            System.err.println("SSFML: Failed to strip signatures from "
                    + jarFile.getName() + ": " + e.getMessage());

            if (tempFile.exists() && !tempFile.delete()) {
                System.err.println("SSFML: Could not remove temporary file: "
                        + tempFile.getAbsolutePath());
            }

            return;
        }

        // Replace the original JAR with the unsigned one.
        try {
            Files.move(
                    tempFile.toPath(),
                    jarFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            System.out.println("SSFML: Stripped signatures from " + jarFile.getName());
        } catch (IOException e) {
            System.err.println("SSFML: Could not replace " + jarFile.getName()
                    + " with the stripped JAR: " + e.getMessage());

            if (tempFile.exists() && !tempFile.delete()) {
                System.err.println("SSFML: Could not remove temporary file: "
                        + tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * Checks whether a jar actually has anything for stripSignatures() to remove,
     * so a normal launch doesn't rewrite jars that don't need it.
     */
    private static boolean needsStripping(File jarFile) {
        boolean isFabricLoader = jarFile.getName().contains("fabric-loader");

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(jarFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    return true;
                }

                if (isFabricLoader && name.equals("fabric.mod.json")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return true;
        }

        return false;
    }

    /**
     * Extracts embedded runtime JARs from the game archive into a working folder.
     * Skips any entry that's already present and passes isValidJar(), so a regular
     * launch only touches disk for jars that are missing or corrupt.
     */
    private static void unpackInternalLibs(File gameJar, File outputDir) {
        // 1. Ensure the libs folder actually exists first
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            System.err.println("SSFML: Could not create libraries directory: "
                    + outputDir.getAbsolutePath());
            return;
        }

        if (!outputDir.isDirectory()) {
            System.err.println("SSFML: Library output path is not a directory: "
                    + outputDir.getAbsolutePath());
            return;
        }

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(gameJar))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();

                // 2. Only extract the JARs we need
                if (name.endsWith(".jar")) {
                    File outputFile = new File(outputDir, name);

                    // 3. Extract the file using a standard buffer
                    if (isValidJar(outputFile)) {
                        zin.closeEntry();
                        continue;
                    }

                    if (outputFile.exists() && !outputFile.delete()) {
                        System.err.println("SSFML: Could not remove stale/corrupt internal lib: "
                                + outputFile.getAbsolutePath());
                    }

                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zin.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    System.out.println("SSFML: Successfully extracted internal lib: " + name);
                }
                zin.closeEntry();
            }
        } catch (IOException e) {
            System.err.println("SSFML: Failed to unpack internal libs from: " + gameJar.getName());
        }
    }

}