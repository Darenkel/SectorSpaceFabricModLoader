package com.sector.bridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

        if (savedPath == null || !new File(savedPath).exists()) {
            System.out.println("Game not found. Opening Setup UI...");
            LocVerifierApp.main(new String[0]);
        } else {
            System.out.println("Game found at: " + savedPath);
            ensureFabricLibs(savedPath);
            launch(savedPath, bridgeJarPath);
        }
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
            pb.inheritIO();

            System.out.println("Launching Fabric with mods/ (enabled mods only): " + modsFolder.getAbsolutePath());
            pb.start();
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Ensures the local Fabric dependency set exists in the project or local game folder.
     */
    private static void ensureFabricLibs(String gameJarPath) {
        File gameFolder = new File(gameJarPath).getParentFile();
        File gameJar = new File(gameJarPath);
        File libsFolder = new File(System.getProperty("user.dir"), "libs");

        unpackInternalLibs(gameJar, libsFolder);

        File libsDir = new File(gameFolder, "libs");
        if (!libsDir.exists()) libsDir.mkdirs();

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
            if (!target.exists()) {
                System.out.println("Checking for dependency: " + libName);

                try (InputStream is = KnotLauncher.class.getResourceAsStream("/" + libName)) {
                    if (is != null) {
                        System.out.println("Extracting " + libName + " from resources...");
                        java.nio.file.Files.copy(is, target.toPath());
                    } else {
                        System.out.println(libName + " not in JAR. Attempting download...");
                    }

                    // Checks primary URL then secondary URL, if both don't work print an error.
                    List<String> urls = getUrls(libName);

                    if (!target.exists()) {
                        System.out.println(libName + " not in JAR. Attempting download...");
                        boolean downloaded = false;
                        for (String url : urls) {
                            downloaded = downloadLib(url, target);
                            if (downloaded) {
                                break;
                            }
                            System.out.println("Retrying " + libName + " from a different source...");
                        }
                        if (downloaded) {
                            stripSignatures(target);
                        } else {
                            failedLibs.add(libName + " (tried: " + String.join(", ", urls) + ")");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error handling " + libName);
                    e.printStackTrace();
                    failedLibs.add(libName + " (exception: " + e.getMessage() + ")");
                }
            }
        }

        if (!failedLibs.isEmpty()) {
            writeLibErrorReport(gameFolder, failedLibs);
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
        try {
            System.out.println("Downloading: " + destination.getName() + "...");
            URL website = new URL(urlString);

            HttpURLConnection connection = (HttpURLConnection) website.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            // 1. Tell Java to follow the server's redirect to the actual file
            connection.setInstanceFollowRedirects(true);

            // 2. Check if the server actually says "OK" (Status 200)
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                // This stops the code from saving a 10KB error page as a JAR
                throw new Exception("Server returned error code: " + status);
            }

            try (ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(destination)) {
                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }
            System.out.println("Download complete.");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to download " + destination.getName() + " from " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes signature metadata from a JAR if it causes conflict with Fabric.
     */
    private static void stripSignatures(File jarFile) {
        File tempFile = new File(jarFile.getAbsolutePath() + ".tmp");
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(jarFile));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tempFile))) {

            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                // Skip signature files in META-INF (.SF, .RSA, .DSA)
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue;
                }
                // Skip the fabric.mod.json ONLY if this is the fabric-loader jar
                // This prevents the "Mods share ID"
                if (jarFile.getName().contains("fabric-loader") && name.equals("fabric.mod.json")) {
                    System.out.println("Stripping fabric.mod.json from " + jarFile.getName() + " to prevent conflict.");
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
            e.printStackTrace();
        }

        // Replace the original JAR with the unsigned one
        jarFile.delete();
        tempFile.renameTo(jarFile);
    }

    /**
     * Extracts embedded runtime JARs from the game archive into a working folder.
     */
    private static void unpackInternalLibs(File gameJar, File outputDir) {
        // 1. Ensure the libs folder actually exists first
        if (!outputDir.exists()) outputDir.mkdirs();

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(gameJar))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();

                // 2. Only extract the JARs we need
                if (name.endsWith(".jar")) {
                    File outputFile = new File(outputDir, name);

                    // 3. Extract the file using a standard buffer
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zin.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    System.out.println("Successfully extracted: " + name);
                }
                zin.closeEntry();
            }
        } catch (IOException e) {
            System.err.println("Failed to unpack internal libs from: " + gameJar.getName());
            e.printStackTrace();
        }
    }

}