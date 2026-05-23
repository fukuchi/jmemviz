package org.fukuchi.jmemviz;

import java.nio.file.Path;
import java.util.Arrays;

/** CLI dispatcher: demo / record / serve / record-and-serve. */
public final class Main {

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "record-and-serve" : args[0];
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);

        switch (cmd) {
            case "demo"             -> JmemvizDemo.run();
            case "record"           -> runRecord(rest);
            case "serve"            -> runServe(rest);
            case "record-and-serve" -> runRecordAndServe(rest);
            case "help", "-h", "--help" -> printUsage();
            default -> {
                System.err.println("unknown command: " + cmd);
                printUsage();
                System.exit(2);
            }
        }
    }

    private static void runRecord(String[] args) {
        String out = args.length > 0 ? args[0] : "trace.json";
        RecordDemo.run(out);
    }

    private static void runServe(String[] args) throws Exception {
        String trace = args.length > 0 ? args[0] : "trace.json";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        JmemvizServer.serve(Path.of(trace), port, true);
        // serve uses a daemon HttpServer, so keep main alive.
        Thread.currentThread().join();
    }

    private static void runRecordAndServe(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "trace.json";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        RecordDemo.run(out);
        JmemvizServer.serve(Path.of(out), port, true);
        Thread.currentThread().join();
    }

    private static void printUsage() {
        System.out.println("""
                jmemviz — Java heap layout stepper

                usage:
                  jmemviz demo                        # CLI output (static layout explanation)
                  jmemviz record [out.json]           # Write trace only
                  jmemviz serve  [trace.json] [port]  # Serve an existing trace
                  jmemviz record-and-serve [out.json] [port]  # record → serve → browser open

                default port: 8765
                """);
    }
}
