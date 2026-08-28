package com.sector.bridge;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

public class SectorSpaceProvider implements GameProvider {
    private Arguments arguments;
    private Path gameJarPath;
    private GameTransformer entrypointTransformer;
    private FabricLauncher cachedLauncher;
    private static final String SOURCE_MARKER_FILE = ".source_marker";


    public void preVerify() {
        // Check if either vital piece of info is missing
        if (LocVerifierCFG.getProperty("game_path") == null || LocVerifierCFG.getProperty("game_version") == null) {
            System.out.println("Bridge: Configuration missing. Launching setup UI...");
            LocVerifierApp.main(new String[0]);
        } else {
            System.out.println("Bridge: Game Path and Version verified.");
        }
    }

    @Override
    public void initialize(net.fabricmc.loader.impl.launch.FabricLauncher launcher) {
        this.cachedLauncher = launcher;
        this.arguments = new Arguments();

        if (this.gameJarPath != null) {
            java.nio.file.Path extractPath = this.gameJarPath.getParent().resolve("extracted_dependencies");

            try {
                java.nio.file.Files.createDirectories(extractPath);

                String currentMarker = buildSourceMarker(this.gameJarPath.toFile());
                java.nio.file.Path markerPath = extractPath.resolve(SOURCE_MARKER_FILE);
                String previousMarker = java.nio.file.Files.exists(markerPath)
                        ? java.nio.file.Files.readString(markerPath).trim()
                        : null;

                if (!currentMarker.equals(previousMarker)) {
                    if (previousMarker != null) {
                        System.out.println("Bridge: Game jar has changed since last extraction, refreshing extracted_dependencies...");
                    }
                    deleteTempDirectory(extractPath);
                    java.nio.file.Files.createDirectories(extractPath);
                }

                // 2. Open the Game JAR and extract EVERYTHING needed
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(this.gameJarPath.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();

                        // Extract JARs and DLLs
                        if (name.endsWith(".jar") || name.endsWith(".dll")) {
                            java.nio.file.Path target = extractPath.resolve(name);
                            boolean needsExtraction = name.endsWith(".jar")
                                    ? !KnotLauncher.isValidJar(target.toFile())
                                    : !java.nio.file.Files.exists(target);

                            if (needsExtraction) {
                                if (java.nio.file.Files.exists(target)) {
                                    java.nio.file.Files.delete(target);
                                }
                                try (java.io.InputStream input = zip.getInputStream(entry)) {
                                    java.nio.file.Files.copy(input, target);
                                }
                            }

                            // 3. If it's a JAR, add it to Fabric's ClassPath
                            if (name.endsWith(".jar")) {
                                launcher.addToClassPath(target);
                            }
                        }
                    }
                }

                // Record what we extracted from, so the next launch can tell if it's stale.
                java.nio.file.Files.writeString(markerPath, currentMarker);

                // 4. Set the Natives Path to our extracted_dependencies folder
                String p = extractPath.toAbsolutePath().toString();
                System.setProperty("org.lwjgl.librarypath", p);
                System.setProperty("java.library.path", p);

                System.out.println("Bridge: Extracted and injected nested dependencies.");

            } catch (java.io.IOException e) {
                System.err.println("Bridge: Failed to extract nested dependencies: " + e);
                e.printStackTrace();
            }

            Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
        }
    }

    private static String buildSourceMarker(File gameJar) throws java.io.IOException {
        java.nio.file.attribute.FileTime lastModified = java.nio.file.Files.getLastModifiedTime(gameJar.toPath());
        long size = java.nio.file.Files.size(gameJar.toPath());
        return lastModified.toMillis() + ":" + size;
    }

    private static void deleteTempDirectory(java.nio.file.Path directory) throws java.io.IOException {
        if (!java.nio.file.Files.exists(directory)) {
            return;
        }

        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (java.io.IOException e) {
                            System.err.println("Bridge: Failed to delete " + path + ": " + e.getMessage());
                        }
                    });
        }
    }

    @Override
    public String getEntrypoint() { return "game.Main"; }

    @Override
    public String getGameId() { return "sector-space"; }

    @Override
    public String getGameName() { return "Sector Space"; }

    @Override
    public String getRawGameVersion() { return LocVerifierCFG.getProperty("game_version"); }

    @Override
    public String getNormalizedGameVersion() { return LocVerifierCFG.getNormalizedGameVersion(); }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public Path getLaunchDirectory() {
        String jarPath = LocVerifierCFG.getProperty("game_path");

        if (jarPath != null) {
            /* .getParent() turns "C:/Games/Sector Space.jar" into "C:/Games/" */
            return Path.of(jarPath).getParent();
        }
        return Path.of(".");
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        // Check your config here
        preVerify();

        String jarPath = LocVerifierCFG.getProperty("game_path");
        if (jarPath != null) {
            this.gameJarPath = Path.of(jarPath);

            // Separate process from KnotLauncher, append to the same startup log instead of creating a second file.
            StartupLogger.install(this.gameJarPath.getParent().toFile(), true);
        }

        // Continue with the normal logic to find the game JAR/Classpath
        return true;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        String loaderVersion = net.fabricmc.loader.impl.FabricLoaderImpl.VERSION;

        List<BuiltinMod> mods = new java.util.ArrayList<>();

        mods.add(new BuiltinMod(
                Collections.emptyList(),
                new BuiltinModMetadata.Builder("fabricloader", loaderVersion)
                        .setName("Fabric Loader")
                        .build()
        ));

        // Registers the game itself as a dependency-satisfying mod, so a fabric.mod.json
        // can declare a "sector-space" version requirement in its "depends" block.
        mods.add(new BuiltinMod(
                Collections.emptyList(),
                new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
                        .setName(getGameName())
                        .build()
        ));

        return mods;
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
    public GameTransformer getEntrypointTransformer() {
        if (this.entrypointTransformer == null) {
            this.entrypointTransformer = new GameTransformer();
            if (this.gameJarPath != null && this.cachedLauncher != null) {
                this.entrypointTransformer.locateEntrypoints(this.cachedLauncher, java.util.List.of(this.gameJarPath));
            }
        }
        return this.entrypointTransformer;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String gameId) { return Collections.emptySet(); }

    @Override
    public boolean requiresUrlClassLoader() { return false; }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        String jarPath = LocVerifierCFG.getProperty("game_path");
        if (jarPath != null) {
            launcher.addToClassPath(Path.of(jarPath));
        }

        if (this.gameJarPath != null) {
            Path libsDir = this.gameJarPath.getParent().resolve("libs");
            if (java.nio.file.Files.exists(libsDir)) {
                try (var stream = java.nio.file.Files.list(libsDir)) {
                    long added = stream.filter(p -> p.toString().endsWith(".jar"))
                            .peek(launcher::addToClassPath)
                            .count();
                    System.out.println("Bridge: Added " + added + " lib jar(s) from " + libsDir + " to the classpath.");
                } catch (java.io.IOException e) {
                    System.err.println("Bridge: Failed to list libs directory: " + libsDir.toAbsolutePath() + ": " + e);
                    e.printStackTrace();
                }
            }
        }
    }

}