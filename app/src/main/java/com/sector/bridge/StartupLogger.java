package com.sector.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mirrors System.out/System.err to SSFML_startup_log.txt (in the game folder).
 */
public final class StartupLogger {

    static final String LOG_FILE_NAME = "SSFML_startup_log.txt";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private static volatile TeePrintStream activeTee;

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

            TeePrintStream teeOut = new TeePrintStream(realOut, fos, "INFO");
            TeePrintStream teeErr = new TeePrintStream(realErr, fos, "ERROR");

            System.setOut(teeOut);
            System.setErr(teeErr);
            activeTee = teeOut;

            teeOut.println("SSFML: Startup log created at: " + logFile.getAbsolutePath() + (append ? " (appending)" : ""));
        } catch (IOException e) {
            System.err.println("SSFML: Could not open " + LOG_FILE_NAME + ", continuing without file logging: " + e.getMessage());
        }
    }

    /**
     * Writes a line to the console and log file tagged with an explicit level, rather than the level being inferred
     * from whether this is System.out or System.err. Falls back to a plain System.out.println if the tee was never installed
     * (e.g. install() failed to open the file).
     */
    public static void log(String level, String line) {
        TeePrintStream tee = activeTee;
        if (tee == null) {
            System.out.println(line);
            return;
        }
        tee.printWithLevel(level, line);
    }

    /**
     * A PrintStream that writes everything to the original stream (so console behavior is unchanged)
     * and appends a timestamped copy to the shared log file.
     * Both the out-tee and err-tee share the same underlying FileOutputStream so lines from
     * both interleave in the order they actually happened, instead of ending up in two separate files.
     */
    private static final class TeePrintStream extends PrintStream {
        private final FileOutputStream fileOut;
        private final String defaultLevel;

        TeePrintStream(PrintStream real, FileOutputStream fileOut, String defaultLevel) {
            super(real, true, StandardCharsets.UTF_8);
            this.fileOut = fileOut;
            this.defaultLevel = defaultLevel;
        }

        @Override
        public void println(String line) {
            super.println(line);
            writeToFile(defaultLevel, line);
        }

        @Override
        public void println(Object obj) {
            println(String.valueOf(obj));
        }

        void printWithLevel(String level, String line) {
            super.println(line);
            writeToFile(level, line);
        }

        private void writeToFile(String level, String line) {
            String timestamped = "[" + LocalDateTime.now().format(TIMESTAMP) + "][" + level + "] " + line + System.lineSeparator();
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