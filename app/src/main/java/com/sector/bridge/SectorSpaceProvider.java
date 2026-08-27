package com.sector.bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

public class SectorSpaceProvider implements GameProvider {
    private Arguments arguments;
    //public LocVerifierCFG config = new LocVerifierCFG();
    public Path libsFolder = Paths.get("libs");

    public static int mods_menu = 0;
    //public static ModManagerPane modManagerPane;

    public void preVerify() {

        // Check if either vital piece of info is missing
        if (LocVerifierCFG.getProperty("game_path") == null || LocVerifierCFG.getProperty("game_version") == null) {
            System.out.println("Bridge: Configuration missing. Launching setup UI...");
            LocVerifierApp.main(new String[0]);
        } else {
            System.out.println("Bridge: Game Path and Version verified.");
            ensureModsFolder();
            libChecker();
        }
        // Makes sure the Libs are somewhere easy to read
    }

    @Override
    public void initialize(net.fabricmc.loader.impl.launch.FabricLauncher launcher) {
        this.arguments = new Arguments();

        if (this.gameJarPath != null) {
            // 1. Create a temporary folder to hold extracted files
            java.nio.file.Path extractPath = this.gameJarPath.getParent().resolve("bridge_temp");
            try {
                java.nio.file.Files.createDirectories(extractPath);

                // 2. Open the Game JAR and extract EVERYTHING needed
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(this.gameJarPath.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();

                        // Extract JARs and DLLs
                        if (name.endsWith(".jar") || name.endsWith(".dll")) {
                            java.nio.file.Path target = extractPath.resolve(name);
                            if (!java.nio.file.Files.exists(target)) {
                                java.nio.file.Files.copy(zip.getInputStream(entry), target);
                            }

                            // 3. If it's a JAR, add it to Fabric's ClassPath
                            if (name.endsWith(".jar")) {
                                launcher.addToClassPath(target);
                            }
                        }
                    }
                }

                // 4. Set the Natives Path to our temp folder
                String p = extractPath.toAbsolutePath().toString();
                System.setProperty("org.lwjgl.librarypath", p);
                System.setProperty("java.library.path", p);

                System.out.println("Bridge: Extracted and injected nested dependencies.");

            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }

        try {
            // 1. Get the transformer class from the current classloader
            Class<?> transformerClass = Class.forName("net.fabricmc.loader.impl.game.patch.GameTransformer", true, this.getClass().getClassLoader());

            // 2. Locate the 'patchedClasses' field definition
            java.lang.reflect.Field mapField = transformerClass.getDeclaredField("patchedClasses");
            mapField.setAccessible(true);

            // 3. Since there's no 'patches' field to trigger this,
            // we create a 'phantom' instance of the transformer.
            // This forces the JVM to verify the class state.
            Object phantom = transformerClass.getDeclaredConstructor().newInstance();

            // 4. Inject a fresh HashMap into our phantom
            mapField.set(phantom, new java.util.HashMap<>());

            System.out.println("Bridge: Transformer state locked-in via phantom instance.");
        } catch (Exception e) {
            // If the above fails, it means the class isn't loaded yet.
            // We'll let the launch method handle the 'Surgical' fix we tried earlier.
            System.out.println("Bridge: Waiting for late-stage initialization...");
        }

        // 2. THE FINAL INTEGRATION GOES HERE:
        if (this.gameJarPath != null) {
            java.nio.file.Path libPath = this.gameJarPath.getParent().resolve("natives");
            System.out.println("Bridge: Checking for libs at: " + libPath.toAbsolutePath());

            if (java.nio.file.Files.exists(libPath)) {
                // STEP A: ADD THE JARS
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(libPath)) {
                    stream.filter(p -> p.toString().endsWith(".jar"))
                            .forEach(launcher::addToClassPath);
                    System.out.println("Bridge: Successfully injected game libraries.");
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }

                // STEP B: SET NATIVES
                java.nio.file.Path nativesPath = libPath.resolve("natives");
                if (java.nio.file.Files.exists(nativesPath)) {
                    String pathString = nativesPath.toAbsolutePath().toString();
                    System.setProperty("org.lwjgl.librarypath", pathString);
                    System.setProperty("java.library.path", pathString);
                }

                // STEP C: SET CONTEXT
                Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
            }
        }

    }

    @Override
    public String getEntrypoint() {
        // Returning the main class triggers the actual classloading.
        // Because we 'fixed' the map in initialize(), the NPE will be gone.
        return "game.Main";
    }

/*public static void openModMenu() {
    try {
        // Instead of 'new', we use reflection to define the class at runtime
        Class<?> clazz = Class.forName("com.sector.bridge.ModManagerPane");
        clazz.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
        // This will print the SPECIFIC reason it can't see the class
        e.printStackTrace(); 
    }
}*/



    @Override public String getGameId() { return "sector-space"; }
    @Override public String getGameName() { return "Sector Space"; }
    @Override public String getRawGameVersion() { return LocVerifierCFG.getProperty("game_version"); }
    @Override public String getNormalizedGameVersion() { return LocVerifierCFG.getProperty("game_version"); }
    @Override public boolean isEnabled() { return true; }

    @Override public Path getLaunchDirectory() {
        String jarPath = LocVerifierCFG.getProperty("game_path");

        if (jarPath != null) {
            /* .getParent() turns "C:/Games/Sector Space.jar" into "C:/Games/" */
            return Path.of(jarPath).getParent();
        }
        return Path.of(".");
    }

    public void libChecker() {
        try {
            if (Files.notExists(libsFolder)) {
                Files.createDirectories(libsFolder);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // Prefer valid libraries already present in the local Gradle cache.
        if (isMissingLibraries(libsFolder)) {
            System.out.println("Bridge: Libraries missing from /libs. Scanning Gradle cache...");
            syncLibrariesFromCache(libsFolder);
        }

        // Only use the Dropbox recovery archive if the cache-copy attempt
        // still left an ASM library missing or invalid.
        if (isMissingLibraries(libsFolder)) {
            System.out.println("Bridge: Invalid or missing ASM library detected. Starting recovery...");
            repairInvalidAsmLibraries(libsFolder);
        }
    }
    public void libPopulator(FabricLauncher launcher){
        // 3. Add your Game JAR to the launcher
        launcher.addToClassPath(getLaunchDirectory().resolve("Sector Space.jar"));

        /*  4. Add every JAR in the /libs folder to the Fabric ClassPath */
        try (var stream = Files.list(libsFolder)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("-sources")) // Filter 1
                    .filter(p -> !p.toString().contains("-javadoc")) // Filter 2
                    .forEach(path -> {
                        try {
                            launcher.addToClassPath(path);
                            System.out.println("Bridge: Injected: " + path.getFileName());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }}

    private void syncLibrariesFromCache(Path targetFolder) {
        String userHome = System.getProperty("user.home");
        Path cacheBase = Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1");
        Map<String, Path> bestVersions = new HashMap<>();

        if (Files.notExists(cacheBase)) {
            return;
        }

        try (var stream = Files.walk(cacheBase)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(path -> !path.toString().contains("-sources"))
                    .filter(path -> !path.toString().contains("-javadoc"))
                    .filter(this::isValidLibraryJar)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.contains("mixin") || name.contains("asm-");
                    })
                    .forEach(path -> {
                        String baseName = path.getFileName().toString().split("-[0-9]")[0];

                        Path current = bestVersions.get(baseName);
                        if (current == null
                                || path.getFileName().toString()
                                .compareTo(current.getFileName().toString()) > 0) {
                            bestVersions.put(baseName, path);
                        }
                    });

            for (Path jar : bestVersions.values()) {
                Path destination = targetFolder.resolve(jar.getFileName());

                Files.copy(
                        jar,
                        destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                if (!isValidLibraryJar(destination)) {
                    Files.deleteIfExists(destination);
                    System.err.println("Bridge: Rejected invalid library: " + jar.getFileName());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isValidLibraryJar(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 2 * 1024;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isMissingLibraries(Path libsFolder) {
        if (Files.notExists(libsFolder)) {
            return true;
        }

        try (var stream = Files.list(libsFolder)) {
            List<Path> validJars = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .filter(path -> {
                        try {
                            return Files.size(path) > 2 * 1024;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();

            boolean hasMixin = validJars.stream()
                    .anyMatch(path -> path.getFileName().toString().toLowerCase().contains("mixin"));

            boolean hasAsm = validJars.stream()
                    .anyMatch(path -> path.getFileName().toString().toLowerCase().contains("asm-"));

            return !hasMixin || !hasAsm;
        } catch (IOException e) {
            return true;
        }
    }

    private void repairInvalidAsmLibraries(Path libsFolder) {
        Path repairTempFolder = libsFolder.resolve("asm-repair-temp");

        try {
            Files.createDirectories(repairTempFolder);

            try (var stream = Files.list(libsFolder)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().contains("asm-"))
                        .filter(path -> !isValidLibraryJar(path))
                        .forEach(path -> downloadAndExtractAsmLibrary(path, repairTempFolder));
            }
        } catch (IOException e) {
            System.err.println("Bridge: Could not inspect ASM libraries: " + e.getMessage());
        } finally {
            try {
                deleteRecursively(repairTempFolder);
            } catch (IOException e) {
                System.err.println("Bridge: Could not remove ASM repair files: " + e.getMessage());
            }
        }
    }

    private void downloadAndExtractAsmLibrary(Path invalidJar, Path repairTempFolder) {
        Path archive = repairTempFolder.resolve("asm-recovery.zip");

        try {
            Files.deleteIfExists(invalidJar);

            // Retain a successful download while repairing every invalid ASM library.
            if (Files.notExists(archive) || Files.size(archive) <= 2 * 1024) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(
                                "https://www.dropbox.com/scl/fi/aqvnx7s3uoj4oay2kddsa/asm-9.9.zip?"
                                        + "rlkey=y7bofdk8mp5vv4ribiv7e810n&st=wijygs6n&dl=1"))
                        .header("User-Agent", "SectorSpaceBridge")
                        .GET()
                        .build();

                HttpResponse<InputStream> response = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Nexus download returned HTTP " + response.statusCode());
                }

                try (InputStream body = response.body()) {
                    Files.copy(body, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;

                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()
                            && entry.getName().endsWith(invalidJar.getFileName().toString())) {
                        Files.copy(zip, invalidJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        break;
                    }
                }
            }

            if (!isValidLibraryJar(invalidJar)) {
                Files.deleteIfExists(invalidJar);
                throw new IOException("Recovered ASM JAR is invalid or was not found in the archive");
            }

            System.out.println("Bridge: Repaired ASM library: " + invalidJar.getFileName());
        } catch (Exception e) {
            System.err.println("Bridge: Failed to repair " + invalidJar.getFileName() + ": " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }

        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Path gameJarPath; // Class-level field

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        // Check your config here

        preVerify();

 /*   if (LocVerifierCFG.getProperty("game_path") == null) {
        System.out.println("Configuration missing. Launching setup UI...");
        LocVerifierApp.main(new String[0]);
        
        // After the UI closes, you might need to re-check if they fixed the path
        if (LocVerifierCFG.getProperty("game_path") == null) {
            return false; // Tell Knot the game still isn't here
        }
    } */

        // gameJarPath was never actually assigned here before, so initialize()'s
        // "if (this.gameJarPath != null)" block never ran.
        String jarPath = LocVerifierCFG.getProperty("game_path");
        if (jarPath != null) {
            this.gameJarPath = Path.of(jarPath);

            // Separate process from KnotLauncher -- append to the same startup log
            // instead of starting a second file.
            StartupLogger.install(this.gameJarPath.getParent().toFile(), true);
        }

        // Continue with your normal logic to find the game JAR/Classpath
        return true;
    }


    @Override
    public Collection<BuiltinMod> getBuiltinMods()
    {
        String loaderVersion = net.fabricmc.loader.impl.FabricLoaderImpl.VERSION;
        // This registers the running loader as a dependency satisfy-er
        return Collections.singletonList(
                new BuiltinMod(
                        Collections.emptyList(), // Optional path
                        new BuiltinModMetadata.Builder("fabricloader", loaderVersion)
                                .setName("Fabric Loader")
                                .build()
                )
        );
    }

    @Override
    public void launch(ClassLoader loader) {
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = loader.loadClass("game.Main");
            mainClass.getMethod("main", String[].class).invoke(null, (Object) this.arguments.toArray());
        } catch (Exception e) {
            throw new RuntimeException("Bridge: Launch failed", e);
        }
    }

    @Override
    public Arguments getArguments() { return this.arguments; }

    @Override
    public String[] getLaunchArguments(boolean server) { return this.arguments.toArray(); }

    @Override
    public GameTransformer getEntrypointTransformer()
    {
        return new GameTransformer();
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String gameId) {
        return Collections.emptySet();
    }

/*    @Override
    public java.util.Collection<java.nio.file.Path> getGameContextJars() {
        return java.util.Collections.singleton(java.nio.file.Paths.get("libs/Sector Space.jar"));
    }*/

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        // Run the libChecker to make sure all the libraries are where they should be before Fabric initializes
        libPopulator(launcher);
    }

    public void ensureModsFolder() {
        String jarPath = LocVerifierCFG.getProperty("game_path");
        if (jarPath != null) {
            Path rootFolder = Path.of(jarPath).getParent();
            Path modsFolder = rootFolder.resolve("mods");

            if (Files.notExists(modsFolder)) {
                try {
                    Files.createDirectories(modsFolder);
                    System.out.println("Created mods folder at: " + modsFolder);
                } catch (IOException e) {
                    System.err.println("Failed to create mods folder: " + e.getMessage());
                }
            }
        }
    }

    public static class ModEntry {
        public String name;
        public boolean isEnabled = true;
        public ModEntry(String name) { this.name = name; }
    }

    public static List<ModEntry> foundMods = new ArrayList<>();

    public static void scanMods() {
        foundMods.clear();
        File modFolder = new File(LocVerifierCFG.getProperty("game_path") + File.separator + "/mods"); // Or your absolute path
        if (modFolder.exists() && modFolder.isDirectory()) {
            for (File f : modFolder.listFiles()) {
                if (f.getName().endsWith(".jar")) {
                    foundMods.add(new ModEntry(f.getName()));
                }
            }
        }
    }

}