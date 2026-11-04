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
