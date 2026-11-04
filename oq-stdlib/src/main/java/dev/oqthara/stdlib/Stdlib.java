package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqRange;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Stdlib {
    private Stdlib() {
    }

    public static void install(Interpreter interpreter) {
        installCore(interpreter);
        installText(interpreter);
        installCollections(interpreter);
        installMath(interpreter);
        installFiles(interpreter);
        installJson(interpreter);
        installTime(interpreter);
        installHttp(interpreter);
        installConcurrency(interpreter);
        installProcess(interpreter);
    }

    private static void installCore(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.variadic("print", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.display(args.get(i)));
            }
            host.out().println(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.variadic("write_text", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) sb.append(Values.display(arg));
            host.out().print(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.variadic("write", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) sb.append(Values.display(arg));
            host.out().print(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("input", 0, (host, args, span) -> {
            host.out().flush();
            try {
                String line = host.in().readLine();
                return line == null ? null : line;
            } catch (IOException e) {
                throw new OqError("cannot read input: " + e.getMessage(), span);
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("input", 1, (host, args, span) -> {
            host.out().print(Values.display(args.get(0)));
            host.out().flush();
            try {
                String line = host.in().readLine();
                return line == null ? null : line;
            } catch (IOException e) {
                throw new OqError("cannot read input: " + e.getMessage(), span);
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("text", 1, (host, args, span) -> Values.display(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("repr", 1, (host, args, span) -> Values.repr(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("number", 1, (host, args, span) -> toNumber(args.get(0), span)));
        interpreter.defineBuiltin(NativeFunction.of("type_of", 1, (host, args, span) -> Values.typeName(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("is_nothing", 1, (host, args, span) -> args.get(0) == null));
        interpreter.defineBuiltin(NativeFunction.of("is_number", 1, (host, args, span) -> Values.isNumber(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("is_text", 1, (host, args, span) -> args.get(0) instanceof String));
        interpreter.defineBuiltin(NativeFunction.of("length", 1, (host, args, span) ->
                (long) Values.size(args.get(0), span)));
        interpreter.defineBuiltin(NativeFunction.of("range", 1, (host, args, span) -> {
            long n = Values.asLong(args.get(0));
            return new OqRange(0, Math.max(0, n), false);
        }));
        interpreter.defineBuiltin(NativeFunction.of("range", 2, (host, args, span) ->
                new OqRange(Values.asLong(args.get(0)), Values.asLong(args.get(1)), false)));
        interpreter.defineBuiltin(NativeFunction.of("error", 1, (host, args, span) -> {
            throw new OqError(Values.display(args.get(0)), span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("assert", 1, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed", span,
                        "The value was " + Values.repr(args.get(0)) + " but it must be true.");
            }
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("assert", 2, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed: " + Values.display(args.get(1)), span);
            }
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("args", 0, (host, args, span) -> new ArrayList<Object>(host.programArgs())));
        interpreter.defineBuiltin(NativeFunction.of("exit", 0, (host, args, span) -> {
            throw new ExitSignal(0);
        }));
        interpreter.defineBuiltin(NativeFunction.of("exit", 1, (host, args, span) -> {
            throw new ExitSignal((int) Values.asLong(args.get(0)));
        }));
        interpreter.defineBuiltin(NativeFunction.of("sort_by", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "sort", List.of(args.get(1)), span)));
        interpreter.defineBuiltin(NativeFunction.of("map", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "map", List.of(args.get(1)), span)));
        interpreter.defineBuiltin(NativeFunction.of("filter", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "filter", List.of(args.get(1)), span)));
        interpreter.defineBuiltin(NativeFunction.of("clone", 1, (host, args, span) -> deepCopy(args.get(0))));
    }

    private static void installText(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("format", 1, (host, args, span) -> Values.display(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.variadic("join", 1, (host, args, span) -> {
            Object first = args.get(0);
            Iterable<?> items = Values.iterable(first, span);
            String separator = args.size() > 1 ? Values.display(args.get(1)) : "";
            StringBuilder sb = new StringBuilder();
            boolean firstItem = true;
            for (Object item : items) {
                if (!firstItem) sb.append(separator);
                sb.append(Values.display(item));
                firstItem = false;
            }
            return sb.toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("upper", 1, (host, args, span) ->
                Values.display(args.get(0)).toUpperCase(Locale.ROOT)));
        interpreter.defineBuiltin(NativeFunction.of("lower", 1, (host, args, span) ->
                Values.display(args.get(0)).toLowerCase(Locale.ROOT)));
        interpreter.defineBuiltin(NativeFunction.of("trim", 1, (host, args, span) ->
                Values.display(args.get(0)).strip()));
        interpreter.defineBuiltin(NativeFunction.of("contains", 2, (host, args, span) ->
                Values.contains(args.get(0), args.get(1), span)));
        interpreter.defineBuiltin(NativeFunction.of("split", 2, (host, args, span) -> {
            String text = Values.display(args.get(0));
            String separator = Values.display(args.get(1));
            return new ArrayList<Object>(List.of(text.split(Pattern.quote(separator), -1)));
        }));
        interpreter.defineBuiltin(NativeFunction.of("replace", 3, (host, args, span) ->
                Values.display(args.get(0)).replace(Values.display(args.get(1)), Values.display(args.get(2)))));
        interpreter.defineBuiltin(NativeFunction.of("regex", 2, (host, args, span) -> {
            Pattern pattern = compilePattern(args.get(0), span);
            Matcher matcher = pattern.matcher(Values.display(args.get(1)));
            List<Object> matches = new ArrayList<>();
            while (matcher.find()) {
                if (matcher.groupCount() > 0) {
                    List<Object> groups = new ArrayList<>();
                    for (int i = 0; i <= matcher.groupCount(); i++) {
                        groups.add(matcher.group(i));
                    }
                    matches.add(groups);
                } else {
                    matches.add(matcher.group());
                }
            }
            return matches;
        }));
        interpreter.defineBuiltin(NativeFunction.of("matches", 2, (host, args, span) ->
                compilePattern(args.get(0), span).matcher(Values.display(args.get(1))).find()));
    }

    private static void installCollections(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("keys", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("keys() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.keySet());
        }));
        interpreter.defineBuiltin(NativeFunction.of("values", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("values() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.values());
        }));
        interpreter.defineBuiltin(NativeFunction.of("push", 2, (host, args, span) -> {
            if (!(args.get(0) instanceof List<?> raw)) {
                throw new OqError("push() needs a list, got " + Values.typeName(args.get(0)), span);
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            list.add(args.get(1));
            return list;
        }));
        interpreter.defineBuiltin(NativeFunction.of("pop", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "pop", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("sum", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "sum", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("sort", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "sort", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("reverse", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "reverse", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("unique", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "unique", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("set", 1, (host, args, span) -> {
            if (args.get(0) instanceof Set<?> set) return set;
            Set<Object> result = new LinkedHashSet<>();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("list", 1, (host, args, span) -> {
            if (args.get(0) instanceof List<?> list) return list;
            List<Object> result = new ArrayList<>();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("zip", 2, (host, args, span) -> {
            List<?> a = (List<?>) Values.iterable(args.get(0), span);
            List<?> b = (List<?>) Values.iterable(args.get(1), span);
            List<Object> result = new ArrayList<>();
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                result.add(new ArrayList<Object>(List.of(a.get(i), b.get(i))));
            }
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("range_list", 2, (host, args, span) ->
                new OqRange(Values.asLong(args.get(0)), Values.asLong(args.get(1)), false).toList()));
    }

    private static void installMath(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("abs", 1, (host, args, span) -> {
            Object value = args.get(0);
            if (value instanceof Long l) return Math.abs(l);
            if (value instanceof Double d) return Math.abs(d);
            throw new OqError("abs() needs a number, got " + Values.typeName(value), span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("floor", 1, (host, args, span) ->
                (long) Math.floor(Values.asDouble(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("ceil", 1, (host, args, span) ->
                (long) Math.ceil(Values.asDouble(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("round", 1, (host, args, span) ->
                Math.round(Values.asDouble(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("round", 2, (host, args, span) -> {
            double value = Values.asDouble(args.get(0));
            long places = Values.asLong(args.get(1));
            if (places < 0 || places > 15) {
                throw new OqError("round() places must be between 0 and 15, got " + places, span);
            }
            double factor = Math.pow(10, places);
            double rounded = Math.round(value * factor) / factor;
            if (places == 0) return (long) rounded;
            return rounded;
        }));
        interpreter.defineBuiltin(NativeFunction.of("sqrt", 1, (host, args, span) ->
                Math.sqrt(Values.asDouble(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("pow", 2, (host, args, span) ->
                Values.power(args.get(0), args.get(1), span)));
        interpreter.defineBuiltin(NativeFunction.of("min", 2, (host, args, span) ->
                Values.compare(args.get(0), args.get(1), span) <= 0 ? args.get(0) : args.get(1)));
        interpreter.defineBuiltin(NativeFunction.of("max", 2, (host, args, span) ->
                Values.compare(args.get(0), args.get(1), span) >= 0 ? args.get(0) : args.get(1)));
        interpreter.defineBuiltin(NativeFunction.of("clamp", 3, (host, args, span) -> {
            Object value = args.get(0);
            if (Values.compare(value, args.get(1), span) < 0) return args.get(1);
            if (Values.compare(value, args.get(2), span) > 0) return args.get(2);
            return value;
        }));
        interpreter.defineBuiltin(NativeFunction.of("random", 0, (host, args, span) ->
                ThreadLocalRandom.current().nextDouble()));
        interpreter.defineBuiltin(NativeFunction.of("random", 2, (host, args, span) -> {
            long from = Values.asLong(args.get(0));
            long to = Values.asLong(args.get(1));
            if (to <= from) return from;
            return ThreadLocalRandom.current().nextLong(from, to);
        }));
        interpreter.defineBuiltin(NativeFunction.of("random_item", 1, (host, args, span) -> {
            List<?> items = (List<?>) Values.iterable(args.get(0), span);
            if (items.isEmpty()) return null;
            return items.get(ThreadLocalRandom.current().nextInt(items.size()));
        }));
    }

    private static void installFiles(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("read", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new OqError("cannot read file '" + path + "'", span,
                        "Check that the file exists and is readable. (" + e.getMessage() + ")");
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("read_lines", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            try {
                return new ArrayList<Object>(Files.readAllLines(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new OqError("cannot read file '" + path + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("save", 2, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, Values.display(args.get(1)), StandardCharsets.UTF_8);
                return null;
            } catch (IOException e) {
                throw new OqError("cannot write file '" + path + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("append", 2, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, Values.display(args.get(1)), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                return null;
            } catch (IOException e) {
                throw new OqError("cannot append to file '" + path + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("exists", 1, (host, args, span) ->
                Files.exists(resolve(host, args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("list_files", 1, (host, args, span) -> {
            Path dir = resolve(host, args.get(0));
            try (var stream = Files.list(dir)) {
                List<Object> names = new ArrayList<>();
                stream.sorted().forEach(p -> names.add(p.getFileName().toString()));
                return names;
            } catch (IOException e) {
                throw new OqError("cannot list directory '" + dir + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("delete", 1, (host, args, span) -> {
            try {
                return Files.deleteIfExists(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot delete '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("make_dir", 1, (host, args, span) -> {
            try {
                Files.createDirectories(resolve(host, args.get(0)));
                return null;
            } catch (IOException e) {
                throw new OqError("cannot create directory '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("file_size", 1, (host, args, span) -> {
            try {
                return Files.size(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot read size of '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("path_join", 2, (host, args, span) ->
                Path.of(Values.display(args.get(0))).resolve(Values.display(args.get(1))).toString()));
        interpreter.defineBuiltin(NativeFunction.of("path_name", 1, (host, args, span) -> {
            Path path = Path.of(Values.display(args.get(0)));
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("path_parent", 1, (host, args, span) -> {
            Path parent = Path.of(Values.display(args.get(0))).getParent();
            return parent == null ? "" : parent.toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("cwd", 0, (host, args, span) ->
                host.workingDirectory().toAbsolutePath().toString()));
    }

    private static void installJson(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("json_parse", 1, (host, args, span) ->
                Json.parse(Values.display(args.get(0)), span)));
        interpreter.defineBuiltin(NativeFunction.of("json", 1, (host, args, span) -> Json.write(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("json_write", 1, (host, args, span) -> Json.write(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("json_pretty", 1, (host, args, span) ->
                Json.writePretty(args.get(0))));
    }

    private static void installTime(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("now", 0, (host, args, span) ->
                java.time.Instant.now().toString()));
        interpreter.defineBuiltin(NativeFunction.of("today", 0, (host, args, span) ->
                LocalDate.now().toString()));
        interpreter.defineBuiltin(NativeFunction.of("datetime", 0, (host, args, span) ->
                LocalDateTime.now().withNano(0).toString()));
        interpreter.defineBuiltin(NativeFunction.of("timestamp", 0, (host, args, span) ->
                System.currentTimeMillis()));
        interpreter.defineBuiltin(NativeFunction.of("sleep", 1, (host, args, span) -> {
            long millis = (long) (Values.asDouble(args.get(0)) * 1000);
            try {
                Thread.sleep(Math.max(0, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
    }

    private static void installHttp(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("http_get", 1, (host, args, span) ->
                http("GET", Values.display(args.get(0)), null, span)));
        interpreter.defineBuiltin(NativeFunction.of("http_post", 2, (host, args, span) ->
                http("POST", Values.display(args.get(0)), Values.display(args.get(1)), span)));
    }

    private static Object http(String method, String url, String body, Span span) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "oqthara/0.1");
            if (method.equals("POST")) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                builder.header("Content-Type", "application/json");
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) response.statusCode());
            result.put("body", response.body());
            result.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
            return new OqData("Response", result);
        } catch (IllegalArgumentException e) {
            throw new OqError("invalid URL '" + url + "'", span, e.getMessage());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OqError("HTTP request failed: " + e.getMessage(), span,
                    "Check the URL and your network connection.");
        }
    }

    private static void installConcurrency(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("spawn", 1, (host, args, span) -> {
            Object callback = args.get(0);
            java.util.concurrent.CompletableFuture<Object> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> host.call(callback, List.of(), span),
                            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            return new dev.oqthara.runtime.OqTask(future);
        }));
        interpreter.defineBuiltin(NativeFunction.of("await", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("await() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.await(span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("done", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("done() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.isDone();
        }));
        interpreter.defineBuiltin(NativeFunction.of("par_map", 2, (host, args, span) -> {
            List<?> items = (List<?>) Values.iterable(args.get(0), span);
            Object callback = args.get(1);
            List<java.util.concurrent.CompletableFuture<Object>> futures = new ArrayList<>();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (Object item : items) {
                    futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> host.call(callback, List.of(item), span), executor));
                }
            }
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof OqError oq) throw oq;
                    throw new OqError("parallel task failed: " + cause, span);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OqError("interrupted while mapping in parallel", span);
                }
            }
            return results;
        }));
    }

    private static void installProcess(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("env", 1, (host, args, span) ->
                System.getenv(Values.display(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("run_command", 1, (host, args, span) -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(List.of(Values.display(args.get(0))));
                builder.directory(host.workingDirectory().toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = process.waitFor();
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("code", (long) code);
                result.put("output", output);
                return new OqData("Result", result);
            } catch (IOException e) {
                throw new OqError("cannot run command: " + e.getMessage(), span);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OqError("command interrupted", span);
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("cpu_count", 0, (host, args, span) ->
                (long) Runtime.getRuntime().availableProcessors()));
        interpreter.defineBuiltin(NativeFunction.of("memory_mb", 0, (host, args, span) ->
                Runtime.getRuntime().totalMemory() / (1024 * 1024)));
    }

    private static Path resolve(OqHost host, Object value) {
        Path path = Path.of(Values.display(value));
        return path.isAbsolute() ? path : Path.of("").toAbsolutePath().resolve(path);
    }

    private static Pattern compilePattern(Object value, Span span) {
        try {
            return Pattern.compile(Values.display(value));
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new OqError("invalid regular expression: " + e.getDescription(), span,
                    "Check the pattern syntax.");
        }
    }

    private static Object invokeMethod(OqHost host, Object receiver, String name, List<Object> args, Span span) {
        if (!(host instanceof Interpreter interpreter) || interpreter.methods() == null) {
            throw new OqError("method lookup is not available", span);
        }
        dev.oqthara.runtime.OqCallable method = interpreter.methods().resolve(receiver, name, span);
        if (method == null) {
            throw new OqError(Values.typeName(receiver) + " has no method '" + name + "'", span);
        }
        return host.call(method, args, span);
    }

    private static Object toNumber(Object value, Span span) {
        if (value instanceof Long || value instanceof Double) return value;
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value instanceof String text) {
            String trimmed = text.strip();
            try {
                if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
                    return Double.parseDouble(trimmed);
                }
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                throw new OqError("cannot read \"" + text + "\" as a number", span,
                        "Text must look like 42 or 3.14 to be converted to a number.");
            }
        }
        throw new OqError("cannot convert " + Values.typeName(value) + " to a number", span);
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(deepCopy(k), deepCopy(v)));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            for (Object item : set) copy.add(deepCopy(item));
            return copy;
        }
        if (value instanceof OqData data) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            data.fields().forEach((k, v) -> fields.put(k, deepCopy(v)));
            return new OqData(data.typeName(), fields);
        }
        return value;
    }

    public static final class ExitSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int code;

        public ExitSignal(int code) {
            super(null, null, false, false);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    interface CallableMarker extends OqCallable {
    }
}
