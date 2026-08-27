package com.sector.bridge;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;

public class SectorSpaceProvider implements GameProvider {
    private Arguments arguments;
    private Path gameJarPath;

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

            Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
        }
    }

    @Override
    public String getEntrypoint() {
        return "game.Main";
    }

    @Override
    public String getGameId() { return "sector-space"; }

    @Override
    public String getGameName() { return "Sector Space"; }

    @Override
    public String getRawGameVersion() { return LocVerifierCFG.getProperty("game_version"); }

    @Override
    public String getNormalizedGameVersion() { return LocVerifierCFG.getProperty("game_version"); }

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
    public GameTransformer getEntrypointTransformer() {
        return new GameTransformer();
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String gameId) {
        return Collections.emptySet();
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return false;
    }

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
                    stream.filter(p -> p.toString().endsWith(".jar"))
                            .forEach(launcher::addToClassPath);
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}