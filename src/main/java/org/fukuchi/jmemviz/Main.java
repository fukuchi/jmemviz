package org.fukuchi.jmemviz;

import java.nio.file.Path;
import java.util.Arrays;

/** CLI dispatcher: serve / preprocess. */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (cmd) {
            case "serve"            -> runServe(rest);
            case "preprocess"       -> runPreprocess(rest);
            case "help", "-h", "--help" -> printUsage();
            default -> {
                System.err.println("unknown command: " + cmd);
                printUsage();
                System.exit(2);
            }
        }
    }

    private static void runPreprocess(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: jmemviz preprocess <input.java> [output.java]");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = args.length > 1 ? Path.of(args[1]) : input;
        Preprocessor.process(input, output);
    }

    private static void runServe(String[] args) throws Exception {
        String trace = args.length > 0 ? args[0] : "trace.json";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        JmemvizServer.serve(Path.of(trace), port, true);
        // serve uses a daemon HttpServer, so keep main alive.
        Thread.currentThread().join();
    }

    private static void printUsage() {
        System.out.println("""
                jmemviz — Java heap layout stepper

                usage:
                  jmemviz serve  [trace.json] [port]        # Serve a trace file in the browser
                  jmemviz preprocess <input.java> [output.java]
                                                            # Expand @jmemviz markers in source

                default port: 8765

                See examples/ for sample programs that produce trace files.
                """);
    }
}
