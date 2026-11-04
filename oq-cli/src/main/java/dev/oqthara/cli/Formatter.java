package dev.oqthara.cli;

import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class Formatter {
    private Formatter() {
    }

    static int formatFiles(PrintStream out, PrintStream err, String[] args) {
        if (args.length == 0) {
            err.println("Missing file. Try: oq fmt hello.oq");
            return 2;
        }
        int status = 0;
        for (String name : args) {
            String path = name.endsWith(".oq") ? name : name + ".oq";
            try {
                String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                Parser.parse(path, source);
                String formatted = format(source);
                if (!formatted.equals(source)) {
                    Files.writeString(Path.of(path), formatted, StandardCharsets.UTF_8);
                    out.println("formatted " + path);
                } else {
                    out.println("unchanged " + path);
                }
            } catch (java.io.IOException e) {
                err.println("Cannot read '" + path + "': " + e.getMessage());
                status = 2;
            } catch (OqError e) {
                Details.render(err, e, null, false);
                status = 1;
            }
        }
        return status;
    }

    static String format(String source) {
        List<String> lines = new ArrayList<>();
        for (String line : source.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            while (trimmed.contains("\t")) {
                trimmed = trimmed.replace("\t", "  ");
            }
            if (trimmed.isBlank()) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            int depth = 0;
            String stripped = trimmed.stripLeading();
            if (stripped.startsWith("end") || stripped.startsWith("else")) {
                depth = 1;
            }
            int indent = Math.max(0, indentOf(trimmed) - depth * 2);
            StringBuilder sb = new StringBuilder();
            boolean inString = false;
            char quote = 0;
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (inString) {
                    sb.append(c);
                    if (c == '\\' && i + 1 < stripped.length()) {
                        sb.append(stripped.charAt(++i));
                    } else if (c == quote) {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                    sb.append(c);
                    continue;
                }
                sb.append(c);
            }
            lines.add(" ".repeat(indent) + sb);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines) + "\n";
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }
}
