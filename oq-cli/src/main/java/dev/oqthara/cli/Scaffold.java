package dev.oqthara.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class Scaffold {
    private Scaffold() {
    }

    static int create(PrintStream out, PrintStream err, String[] args) {
        if (args.length == 0) {
            err.println("Missing project name. Try: oq new hello");
            return 2;
        }
        if (args[0].equals("--help") || args[0].equals("-h")) {
            out.println("""
                    Usage: oq new <name>

                    Creates ./<name>/ with a main.oq program and an oq.json project file,
                    then prints the command that runs it. It stops if the directory
                    already exists.""");
            return 0;
        }
        if (args[0].startsWith("-")) {
            err.println("Unknown option: " + args[0]);
            err.println("Try: oq new hello");
            return 2;
        }
        String name = args[0];
        Path dir = Path.of(name);
        if (Files.exists(dir)) {
            err.println("Directory '" + name + "' already exists.");
            return 1;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("main.oq"), """
                    # {name}
                    greeting = "Hello from Oqthara!"

                    print greeting
                    """.replace("{name}", name), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("oq.json"), """
                    {
                      "name": "%s",
                      "version": "0.1.0",
                      "entry": "main.oq"
                    }
                    """.formatted(name), StandardCharsets.UTF_8);
            out.println("Created project " + name + "/");
            out.println("Run it with: oq " + name + "/main.oq");
            return 0;
        } catch (IOException e) {
            err.println("Cannot create project: " + e.getMessage());
            return 1;
        }
    }
}
