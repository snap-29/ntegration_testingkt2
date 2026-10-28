package ru.ithub.eshop;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Управляет только процессами, которые запустил этот набор тестов. */
final class TestStand {
    private static final List<Process> processes = new ArrayList<>();
    private static Path root;
    static void start() throws Exception {
        if (Boolean.getBoolean("shop.external")) return;
        root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("pom.xml"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Укажите папку проекта как Working directory");
        }
        Files.createDirectories(root.resolve("target/stand"));
        Runtime.getRuntime().addShutdownHook(new Thread(TestStand::stop));
        try {
            for (int port : new int[]{5180, 5181}) {
                try (var socket = new java.net.ServerSocket()) { socket.bind(new InetSocketAddress("localhost", port)); }
                catch (Exception e) { throw new IllegalStateException("Порт " + port + " занят. Для готового стенда задайте -Dshop.external=true", e); }
            }
            Path source = root.resolve("upstream");
            if (!Files.exists(source)) {
                command("git", "clone", "https://github.com/dotnet-architecture/eShopOnWeb.git", "upstream");
                command("git", "-C", "upstream", "checkout", "4da8212117e87d808d4bbc7da6286fd2147ce606");
            }
            Files.writeString(source.resolve("global.json"), "{\"sdk\":{\"version\":\"9.0.320\",\"rollForward\":\"latestFeature\"}}");
            for (String project : List.of("Web", "PublicApi")) {
                command("dotnet", "build", "upstream/src/"+project+"/"+project+".csproj", "--nologo");
            }
            launch("Web", 5180);
            launch("PublicApi", 5181);
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                for (String url : List.of("http://localhost:5180/", "http://localhost:5181/api/catalog-items")) {
                    boolean ready = false;
                    for (int i=0; i<60; i++) {
                        if (processes.stream().anyMatch(p -> !p.isAlive())) break;
                        try {
                            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode()==200) { ready=true; break; }
                        } catch (java.io.IOException e) { /* приложение еще запускается */ }
                        Thread.sleep(1000);
                    }
                    if (!ready) throw new IllegalStateException("Стенд не запустился: "+url+". См. target/stand");
                }
            }
        } catch (Exception e) { stop(); throw e; }
    }
    private static void command(String... args) throws Exception {
        var p = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("target/stand/setup.log").toFile())).start();
        processes.add(p);
        if (!p.waitFor(5, TimeUnit.MINUTES)) throw new IllegalStateException("Таймаут подготовки стенда; target/stand/setup.log");
        processes.remove(p);
        if (p.exitValue()!=0) throw new IllegalStateException("Ошибка " + args[0] + ". См. target/stand/setup.log");
    }
    private static void launch(String project, int port) throws Exception {
        var pb = new ProcessBuilder("dotnet", "run", "--no-build", "--no-launch-profile", "--project", "upstream/src/"+project+"/"+project+".csproj").directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("target/stand/"+project+".log").toFile());
        pb.environment().put("ASPNETCORE_ENVIRONMENT", "Development");
        pb.environment().put("UseOnlyInMemoryDatabase", "true");
        pb.environment().put("ASPNETCORE_URLS", "http://localhost:"+port);
        // Согласованный адрес по умолчанию. Старый дефект воспроизводится явно.
        pb.environment().put("baseUrls__apiBase", Boolean.getBoolean("shop.reproduceDefect") ? "https://localhost:5099/api/" : "http://localhost:5181/api/");
        processes.add(pb.start());
    }
    static synchronized void stop() {
        for (var process : processes) {
            var children = process.descendants().toList();
            children.reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        processes.clear();
    }
}
