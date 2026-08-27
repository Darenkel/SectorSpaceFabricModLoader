package com.sector.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mirrors System.out/System.err to SSFML_startup_log.txt (in the game folder).
 */
public final class StartupLogger {

    private static final String LOG_FILE_NAME = "SSFML_startup_log.txt";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private StartupLogger() {
    }

    /**
     * Installs the tee. Safe to call more than once (only installs once).
     * If the log file can't be opened for some reason, prints a single warning to the
     * real stderr rather than failing the whole launch over a logging problem.
     */
    public static synchronized void install(File gameFolder) {
        install(gameFolder, false);
    }

    /**
     * Installs the tee. Pass append=true when a separate process (SectorSpaceProvider, running in the child JVM Knot spawns)
     * needs to keep writing to the same log file the launcher process already opened for this launch, instead of starting a second one.
     */
    public static synchronized void install(File gameFolder, boolean append) {
        if (System.out instanceof TeePrintStream) {
            return;
        }

        File logFile = new File(gameFolder, LOG_FILE_NAME);

        try {
            FileOutputStream fos = new FileOutputStream(logFile, append);

            PrintStream realOut = System.out;
            PrintStream realErr = System.err;

            TeePrintStream teeOut = new TeePrintStream(realOut, fos, false);
            TeePrintStream teeErr = new TeePrintStream(realErr, fos, true);

            System.setOut(teeOut);
            System.setErr(teeErr);

            teeOut.println("SSFML: Startup log opened at " + logFile.getAbsolutePath() + (append ? " (appending)" : ""));
        } catch (IOException e) {
            System.err.println("SSFML: Could not open " + LOG_FILE_NAME + ", continuing without file logging: " + e.getMessage());
        }
    }

    /**
     * A PrintStream that writes everything to the original stream (so console behavior is unchanged)
     * and appends a timestamped copy to the shared log file.
     * Both the out-tee and err-tee share the same underlying FileOutputStream so lines from
     * both interleave in the order they actually happened, instead of ending up in two separate files.
     */
    private static final class TeePrintStream extends PrintStream {
        private final FileOutputStream fileOut;
        private final boolean isErrorStream;

        TeePrintStream(PrintStream real, FileOutputStream fileOut, boolean isErrorStream) {
            super(real, true, StandardCharsets.UTF_8);
            this.fileOut = fileOut;
            this.isErrorStream = isErrorStream;
        }

        @Override
        public void println(String line) {
            super.println(line);
            writeToFile(line);
        }

        @Override
        public void println(Object obj) {
            println(String.valueOf(obj));
        }

        private void writeToFile(String line) {
            String prefix = isErrorStream ? "[ERROR]" : "[INFO]";
            String timestamped = "[" + LocalDateTime.now().format(TIMESTAMP) + "]" + prefix + " " + line + System.lineSeparator();
            try {
                synchronized (fileOut) {
                    fileOut.write(timestamped.getBytes(StandardCharsets.UTF_8));
                    fileOut.flush();
                }
            } catch (IOException e) {
                // Don't let a logging failure cascade into breaking the actual launch.
                super.println("SSFML: failed writing to " + LOG_FILE_NAME + ": " + e.getMessage());
            }
        }
    }
}