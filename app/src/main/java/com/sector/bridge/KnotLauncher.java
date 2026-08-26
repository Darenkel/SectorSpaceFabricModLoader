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
import java.nio.file.StandardCopyOption;
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
     * - only enabled mods from the staged folder
     */
    public static void launch(String gameJarPath, String bridgeJarPath) {
        File gameFolder = new File(gameJarPath).getParentFile();
        File modsFolder = new File(gameFolder, "mods");
        File stagingFolder = new File(gameFolder, "sectorspacebridge_enabled_mods");

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ModLoader mLoader = new ModLoader();

        try {
            if (stagingFolder.exists()) {
                deleteRecursively(stagingFolder);
            }
            Files.createDirectories(stagingFolder.toPath());

            List<File> enabledMods = mLoader.getEnabledModFiles(gameFolder);
            for (File mod : enabledMods) {
                File target = new File(stagingFolder, mod.getName());
                stageEnabledMod(mod, target);
            }

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

            if (stagingFolder.exists()) {
                File[] stagedJars = stagingFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (stagedJars != null) {
                    for (File jar : stagedJars) {
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
            pbCommand.add("-Dfabric.addMods=" + stagingFolder.getAbsolutePath());
            pbCommand.add("-cp");
            pbCommand.add(finalCp);
            pbCommand.add("net.fabricmc.loader.impl.launch.knot.KnotClient");

            ProcessBuilder pb = new ProcessBuilder(pbCommand);
            pb.directory(gameFolder);
            pb.inheritIO();

            System.out.println("Launching Fabric using staged enabled mods: " + stagingFolder.getAbsolutePath());
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
                    String baseUrl = "";
                    if (libName.contains("fabric-loader")) {
                        baseUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.18.4/fabric-loader-0.18.4.jar";
                    } else if (libName.contains("sponge-mixin")) {
                        baseUrl = "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.15.3%2Bmixin.0.8.7/sponge-mixin-0.15.3%2Bmixin.0.8.7.jar";
                    } else if (libName.contains("asm-9.9")) {
                        baseUrl = "https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm/9.9/asm-9.9.jar";
                    } else if (libName.contains("asm-analysis")) {
                        baseUrl = "https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.jar";
                    } else if (libName.contains("asm-commons")) {
                        baseUrl = "https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.jar";
                    } else if (libName.contains("asm-tree")) {
                        baseUrl = "https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar";
                    } else if (libName.contains("asm-util")) {
                        baseUrl = "https://repository.ow2.org/nexus/service/local/repositories/releases/content/org/ow2/asm/asm-util/9.9/asm-util-9.9.jar";
                    }
                    if (!target.exists()) {
                        System.out.println(libName + " not in JAR. Attempting download...");
                        downloadLib(baseUrl, target);
                    }
                } catch (Exception e) {
                    System.err.println("Error handling " + libName);
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Downloads a Fabric/ASM dependency into the target folder.
     */
    private static void downloadLib(String urlString, File destination) {
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
        } catch (Exception e) {
            System.err.println("Failed to download " + destination.getName());
            e.printStackTrace();
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
                // 2. NEW: Skip the fabric.mod.json ONLY if this is the fabric-loader jar
                // This prevents the "Mods share ID" conflict we saw earlier
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

    /**
     * Deletes a folder tree recursively.
     */
    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    /**
     * Stages an enabled mod into a temporary folder as a real JAR file.
     * If a hard link is unsupported, this falls back to a normal file copy.
     */
    private static void stageEnabledMod(File src, File dst) throws IOException {
        if (Files.exists(dst.toPath())) {
            Files.delete(dst.toPath());
        }

        try {
            Files.createLink(dst.toPath(), src.toPath());
        } catch (UnsupportedOperationException | IOException ex) {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

