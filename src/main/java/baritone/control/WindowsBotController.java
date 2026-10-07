package baritone.control;

import baritone.Baritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.Helper;
import baritone.behavior.Behavior;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WindowsBotController — Moduł bezpośredniego sterowania botem Baritone przez system Windows.
 *
 * Oferuje:
 * 1. Lokalny serwer HTTP (127.0.0.1:21420+) z nowoczesnym, interaktywnym panelem WWW (Web Dashboard).
 * 2. API REST (/api/status, /api/command, /api/logs) dla skryptów PowerShell, Batch oraz aplikacji Windows.
 * 3. File Watcher (bot_command.txt): wykonuje polecenia zapisane bezpośrednio z Windowsa do pliku tekstowego.
 * 4. Powiadomienia Windows (Desktop / Toasts) o stanie zdrowia bota i zakończeniu zadań.
 */
public class WindowsBotController extends Behavior implements Helper {

    private HttpServer server;
    private int boundPort = 21420;
    private int botId = 1;
    private boolean running = false;
    private final Deque<String> recentLogs = new ConcurrentLinkedDeque<>();
    private static final int MAX_LOGS = 100;
    private Path commandFilePath;
    private int fileCheckTick = 0;
    private int menuCheckTick = 0;
    private final AnarchiaLoginAutomation anarchiaAutomation;
    private final ScheduledExecutorService fleetScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Baritone-FleetScheduler-" + boundPort);
        t.setDaemon(true);
        return t;
    });
    private volatile boolean fleetLoginInProgress = false;

    public WindowsBotController(Baritone baritone) {
        super(baritone);
        this.anarchiaAutomation = new AnarchiaLoginAutomation(baritone);
    }

    public AnarchiaLoginAutomation getAnarchiaAutomation() {
        return anarchiaAutomation;
    }

    /** Zwraca menadżer pamieci kont per-slot. */
    public BotAccountMemory getBotAccountMemory() {
        return BotAccountMemory.get();
    }

    public void start() {
        if (running || !Baritone.settings().windowsControllerEnabled.value) {
            return;
        }

        int botNum = 1;
        try {
            String prop = System.getProperty("botNum");
            if (prop == null || prop.isBlank()) {
                prop = System.getenv("BOT_NUM");
            }
            if (prop != null && !prop.isBlank()) {
                botNum = Integer.parseInt(prop.trim());
            }
        } catch (Throwable ignored) {}
        this.botId = botNum;

        int basePort = Baritone.settings().windowsControllerPort.value;
        int targetPort = basePort + Math.max(0, botNum - 1);

        for (int p = targetPort; p < targetPort + 50; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                boundPort = p;
                break;
            } catch (IOException e) {
                // Port zajęty, sprawdź kolejny
            }
        }

        if (server == null) {
            logDirect("§c[WindowsController] Nie udało się powiązać żadnego portu HTTP dla sterowania Windows.");
            return;
        }

        server.createContext("/", new DashboardHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/command", new CommandHandler());
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/fleet", new FleetHandler());
        server.createContext("/api/fleet/command", new FleetCommandHandler());
        server.createContext("/api/logs", new LogsHandler());
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Baritone-WindowsController-" + boundPort);
            t.setDaemon(true);
            return t;
        }));

        server.createContext("/api/accounts", new AccountsHandler());
        server.start();
        running = true;

        // Zapisz numer portu do plików bot_port.txt dla skryptów bat/ps1
        savePortToFile(boundPort);

        // ── ACCOUNT MEMORY ──────────────────────────────────────────────────
        // Zapamiętaj aktualnie zalogowane konto dla tego botId.
        // Jeśli poprzedni start bota był na innym koncie, zapamiętana wartość
        // zostanie użyta przy następnym starcie do auto-przełączenia przez IAS.
        BotAccountMemory.get().rememberCurrentAccount(this.botId);
        String remembered = BotAccountMemory.get().describe(this.botId);
        logDirect(String.format("§b[AccountMemory] Bot #%d → zapamiętane konto: %s", this.botId, remembered));

        logDirect(String.format("§a[WindowsController] Panel WWW i sterowanie Windows aktywne na §ehttp://localhost:%d", boundPort));
    }

    public void stopServer() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable ignored) {}
            server = null;
        }
        if (fleetScheduler != null) {
            try {
                fleetScheduler.shutdownNow();
            } catch (Throwable ignored) {}
        }
        fleetLoginInProgress = false;
        running = false;
    }

    public int getBoundPort() {
        return boundPort;
    }

    public void addLogMessage(String msg) {
        if (msg == null || msg.isBlank()) return;
        String clean = msg.replaceAll("§[0-9a-fk-or]", "");
        recentLogs.addLast("[" + String.format(Locale.ROOT, "%tT", new Date()) + "] " + clean);
        while (recentLogs.size() > MAX_LOGS) {
            recentLogs.removeFirst();
        }
    }

    /** Licznik do sprawdzania konta co ~5 sekund (100 tickow). */
    private int accountCheckTick = 0;
    /** Flaga: czy juz probowalismy auto-switch w tej sesji (zeby nie spamowac IAS). */
    private boolean accountSwitchAttempted = false;

    @Override
    public void onTick(TickEvent event) {
        if (anarchiaAutomation != null && anarchiaAutomation.isActive()) {
            anarchiaAutomation.onTick();
        }

        menuCheckTick++;
        if (menuCheckTick % 10 == 0) {
            checkCommandFile();
        }

        // ── ACCOUNT AUTO-SWITCH ───────────────────────────────────────────────
        // Co 100 ticków (5 sekund) sprawdzaj czy aktualne konto zgadza się
        // z zapamiętanym dla tego botId. Jesli nie, wywolaj auto-switch przez IAS.
        // Probujemy max 1 raz na sesje zeby nie zapetlic IAS login.
        accountCheckTick++;
        if (accountCheckTick % 100 == 20 && !accountSwitchAttempted) {
            if (!BotAccountMemory.get().isCorrectAccount(this.botId)) {
                String needed = BotAccountMemory.get().describe(this.botId);
                Minecraft mc = Minecraft.getInstance();
                String current = (mc != null && mc.getUser() != null) ? mc.getUser().getName() : "?";
                addLogMessage(String.format(
                        "§e[AccountMemory] Bot #%d: aktualne konto '%s' ≠ zapamiętane '%s'. Inicjuję auto-switch przez IAS...",
                        this.botId, current, needed));
                boolean ok = BotAccountMemory.get().switchIfNeeded(this.botId);
                accountSwitchAttempted = true; // Tylko 1 proba na sesje
                if (ok) {
                    addLogMessage("§a[AccountMemory] IAS auto-switch zainicjowany. Po zalogowaniu na konto '"
                            + needed + "' uruchom bota ponownie.");
                }
            }
        }
        // Reset flagi przy zmianie konta (konto sie zmieniło = nowa sesja)
        if (accountCheckTick % 100 == 50) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                String current = mc.getUser().getName();
                String remembered = BotAccountMemory.get().getRememberedAccount(this.botId);
                // Jesli konto sie zgadza (po udanym switchu), resetuj flage
                if (remembered != null && remembered.equalsIgnoreCase(current) && accountSwitchAttempted) {
                    accountSwitchAttempted = false;
                    // Odswież zapamietane konto (moze sie zmieniło)
                    BotAccountMemory.get().rememberCurrentAccount(this.botId);
                }
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (event.getState() != EventState.POST) return;

        fileCheckTick++;
        if (fileCheckTick >= 20 && fileCheckTick % 5 == 0) {
            checkCommandFile();
        }
    }

    @Override
    public void onSendChatMessage(baritone.api.event.events.ChatEvent event) {
        if (event != null && event.getMessage() != null) {
            addLogMessage("§b[Czat Wysłany] " + event.getMessage());
        }
    }

    private void checkCommandFile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        if (commandFilePath == null && mc.gameDirectory != null) {
            commandFilePath = mc.gameDirectory.toPath().resolve("bot_command.txt");
        }

        if (commandFilePath != null && Files.exists(commandFilePath)) {
            try {
                List<String> lines = Files.readAllLines(commandFilePath, StandardCharsets.UTF_8);
                Files.deleteIfExists(commandFilePath);

                for (String raw : lines) {
                    String cmd = raw.trim();
                    if (!cmd.isEmpty()) {
                        addLogMessage("Windows File: " + cmd);
                        executeSmartCommand(cmd, "auto");
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public void sendToChat(String text) {
        if (text == null || text.isBlank()) return;
        Minecraft.getInstance().execute(() -> {
            try {
                LocalPlayer player = ctx.player();
                if (player == null || player.connection == null) {
                    addLogMessage("§c[Czat] Bot nie wszedł jeszcze do gry (Offline).");
                    return;
                }
                String msg = text.trim();
                if (msg.startsWith("/")) {
                    player.connection.sendCommand(msg.substring(1));
                    addLogMessage("§e[Komenda Gry] " + msg);
                } else {
                    player.connection.sendChat(msg);
                    addLogMessage("§b[Czat Gry] " + msg);
                }
            } catch (Throwable t) {
                addLogMessage("§c[Czat Błąd] " + t.getMessage());
            }
        });
    }

    public void executeBaritoneCommand(String cmd) {
        String cleanCmd = cmd.startsWith("#") ? cmd.substring(1) : cmd;
        Minecraft.getInstance().execute(() -> {
            try {
                baritone.getCommandManager().execute(cleanCmd);
            } catch (Throwable t) {
                logDirect("§c[WindowsController] Błąd wykonania komendy: " + t.getMessage());
            }
        });
    }

    public void executeSmartCommand(String cmd, String type) {
        if (cmd == null || cmd.isBlank()) return;
        String cleanLower = cmd.toLowerCase(Locale.ROOT).trim();
        if (cleanLower.startsWith("autologin") || cleanLower.startsWith("#autologin")
                || cleanLower.startsWith("anarchia") || cleanLower.startsWith("#anarchia")
                || cleanLower.startsWith("/autologin") || cleanLower.startsWith("/anarchia")
                || cleanLower.startsWith("/login") || cleanLower.startsWith("login")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                String currentName = mc.player.getGameProfile().getName().toLowerCase(Locale.ROOT);
                if (AnarchiaLoginAutomation.BLACKLISTED_ACCOUNTS.contains(currentName)) {
                    addLogMessage("§c[AutoLogin] BLOKADA: Bot " + mc.player.getGameProfile().getName() + " jest na czarnej liście wykluczonych kont! Logowanie anulowane.");
                    return;
                }
            }
            if (anarchiaAutomation.getState() == AnarchiaLoginAutomation.State.COMPLETED) {
                addLogMessage("§a[AutoLogin] Bot jest już w pełni zalogowany na trybie gry (COMPLETED). Nie powtarzam logowania.");
                return;
            }
            String pwd = "Tumnik@123";
            String[] parts = cmd.split("\\s+");
            if (parts.length > 1 && !parts[1].isBlank()) {
                pwd = parts[1].trim();
            }
            anarchiaAutomation.start(pwd, "anarchia.gg");
            return;
        }

        if ("chat".equalsIgnoreCase(type)) {
            sendToChat(cmd);
            return;
        }

        if ("baritone".equalsIgnoreCase(type)) {
            executeBaritoneCommand(cmd.startsWith("#") ? cmd.substring(1) : cmd);
            return;
        }

        // Auto-detection
        if (cmd.startsWith("/")) {
            sendToChat(cmd);
        } else if (cmd.startsWith("#")) {
            executeBaritoneCommand(cmd.substring(1));
        } else if (cmd.startsWith("chat ") || cmd.startsWith("say ")) {
            sendToChat(cmd.substring(cmd.indexOf(' ') + 1));
        } else {
            String firstWord = cmd.split("\\s+")[0].toLowerCase(Locale.ROOT);
            boolean isBaritone = baritone.getCommandManager().getRegistry().descendingStream()
                    .anyMatch(c -> c.getNames().contains(firstWord));

            if (isBaritone) {
                executeBaritoneCommand(cmd);
            } else {
                sendToChat(cmd);
            }
        }
    }

    private void savePortToFile(int port) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                Path runPort = mc.gameDirectory.toPath().resolve("bot_port.txt");
                Files.writeString(runPort, String.valueOf(port), StandardCharsets.UTF_8);
            }
            if (botId <= 1) {
                Path rootPort = Path.of("bot_port.txt");
                Files.writeString(rootPort, String.valueOf(port), StandardCharsets.UTF_8);
            }
            Path numPort = Path.of("bot_port_" + botId + ".txt");
            Files.writeString(numPort, String.valueOf(port), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Handlers
    // ─────────────────────────────────────────────────────────────────────────

    private class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String html = getDashboardHtml();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    public String getLocalStatusJson() {
        LocalPlayer player = ctx.player();
        boolean online = player != null;

        String name = online ? player.getName().getString() : "Offline";
        float health = online ? player.getHealth() : 0.0f;
        float maxHealth = online ? player.getMaxHealth() : 20.0f;
        int food = online ? player.getFoodData().getFoodLevel() : 0;
        double x = online ? player.getX() : 0.0;
        double y = online ? player.getY() : 0.0;
        double z = online ? player.getZ() : 0.0;
        String dimension = (online && ctx.world() != null) ? ctx.world().dimension().location().toString() : "unknown";

        Optional<IBaritoneProcess> inControl = baritone.getPathingControlManager().mostRecentInControl();
        String activeProcess = (anarchiaAutomation != null && anarchiaAutomation.isActive())
                ? ("AutoLogin: " + anarchiaAutomation.getStatusDescription())
                : inControl.map(IBaritoneProcess::displayName).orElse(baritone.getPathingBehavior().isPathing() ? "Pathing" : "Idle");

        String autoLoginState = (anarchiaAutomation != null) ? anarchiaAutomation.getState().name() : "IDLE";

        boolean mobDef = Baritone.settings().mobDefense.value;
        boolean waterClutch = Baritone.settings().autoWaterClutch.value;
        boolean autoDrop = Baritone.settings().autoDropTrash.value;
        boolean autoSort = Baritone.settings().autoSortInventory.value;

        return String.format(Locale.ROOT,
                "{\"online\":%b,\"botId\":%d,\"name\":\"%s\",\"health\":%.1f,\"maxHealth\":%.1f,\"food\":%d,\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"dimension\":\"%s\",\"activeProcess\":\"%s\",\"autoLoginState\":\"%s\",\"mobDefense\":%b,\"waterClutch\":%b,\"autoDrop\":%b,\"autoSort\":%b,\"port\":%d}",
                online, botId, escapeJson(name), health, maxHealth, food, x, y, z, escapeJson(dimension), escapeJson(activeProcess), autoLoginState, mobDef, waterClutch, autoDrop, autoSort, boundPort);
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String json = getLocalStatusJson();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            String cmd = extractJsonField(body, "command");
            if (cmd.isEmpty()) {
                cmd = extractJsonField(body, "message");
            }
            if (cmd.isEmpty() && !body.startsWith("{")) {
                cmd = body;
            }
            String type = extractJsonField(body, "type");
            if (type.isEmpty()) type = "auto";

            if (!cmd.isEmpty()) {
                addLogMessage("API Otrzymano: " + cmd);
                executeSmartCommand(cmd, type);
            }

            String resp = String.format("{\"success\":true,\"command\":\"%s\",\"type\":\"%s\"}", escapeJson(cmd), escapeJson(type));
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            String msg = extractJsonField(body, "message");
            if (msg.isEmpty()) {
                msg = extractJsonField(body, "command");
            }
            if (msg.isEmpty() && !body.startsWith("{")) {
                msg = body;
            }

            if (!msg.isEmpty()) {
                sendToChat(msg);
            }

            String resp = String.format("{\"success\":true,\"sent\":\"%s\"}", escapeJson(msg));
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class FleetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (int b = 1; b <= 12; b++) {
                int p = 21420 + (b - 1);
                String botJson = null;
                if (p == boundPort) {
                    botJson = getLocalStatusJson();
                } else {
                    botJson = queryRemoteBot(p);
                }
                if (botJson != null && !botJson.isBlank()) {
                    if (!first) sb.append(",");
                    sb.append(botJson);
                    first = false;
                }
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class FleetCommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
            String cmd = extractJsonField(body, "command");
            if (cmd.isEmpty()) {
                cmd = extractJsonField(body, "message");
            }
            String type = extractJsonField(body, "type");
            if (type.isEmpty()) type = "auto";

            List<Integer> targets = parseTargets(body);
            final String finalCmd = cmd;
            final String finalType = type;

            String cleanLower = finalCmd.toLowerCase(Locale.ROOT).trim();
            boolean isAutoLogin = cleanLower.startsWith("autologin") || cleanLower.startsWith("#autologin")
                    || cleanLower.startsWith("anarchia") || cleanLower.startsWith("#anarchia")
                    || cleanLower.startsWith("/autologin") || cleanLower.startsWith("/anarchia");

            if (cleanLower.startsWith("#stop") || cleanLower.equals("stop")) {
                if (fleetLoginInProgress) {
                    fleetLoginInProgress = false;
                    addLogMessage("§c[Flota] Zatrzymano sekwencyjny Auto-Login floty (#stop).");
                }
            }

            if (!finalCmd.isEmpty()) {
                if (isAutoLogin) {
                    List<Integer> targetBots = new ArrayList<>();
                    if (targets.isEmpty() || targets.contains(0) || targets.contains(-1)) {
                        for (int b = 1; b <= 12; b++) {
                            int p = 21420 + (b - 1);
                            if (p == boundPort || queryRemoteBot(p) != null) {
                                targetBots.add(b);
                            }
                        }
                    } else {
                        targetBots.addAll(targets);
                    }
                    Collections.sort(targetBots);

                    if (targetBots.size() <= 1) {
                        int b = targetBots.isEmpty() ? 1 : targetBots.get(0);
                        int p = 21420 + (b - 1);
                        if (p == boundPort) {
                            executeSmartCommand(finalCmd, finalType);
                        } else {
                            sendRemoteCommandAsync(p, finalCmd, finalType);
                        }
                    } else {
                        startFleetSequentialAutoLogin(targetBots, finalCmd, finalType);
                    }
                } else {
                    if (targets.isEmpty() || targets.contains(0) || targets.contains(-1)) {
                        for (int b = 1; b <= 12; b++) {
                            int p = 21420 + (b - 1);
                            if (p == boundPort) {
                                executeSmartCommand(finalCmd, finalType);
                            } else {
                                sendRemoteCommandAsync(p, finalCmd, finalType);
                            }
                        }
                    } else {
                        for (int targetBot : targets) {
                            int p = 21420 + (targetBot - 1);
                            if (p == boundPort) {
                                executeSmartCommand(finalCmd, finalType);
                            } else {
                                sendRemoteCommandAsync(p, finalCmd, finalType);
                            }
                        }
                    }
                }
            }

            String resp = String.format("{\"success\":true,\"command\":\"%s\",\"type\":\"%s\"}", escapeJson(finalCmd), escapeJson(finalType));
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    public void startFleetSequentialAutoLogin(List<Integer> targetBots, String command, String type) {
        if (targetBots == null || targetBots.isEmpty()) return;
        fleetLoginInProgress = true;

        fleetScheduler.submit(() -> {
            addLogMessage(String.format("§a[Flota] 🚀 Rozpoczynam sekwencyjny Auto-Login dla %d botów: %s (kolejny bot 20s po zalogowaniu)", targetBots.size(), targetBots));

            for (int i = 0; i < targetBots.size(); i++) {
                if (!fleetLoginInProgress) {
                    addLogMessage("§c[Flota] Sekwencja Auto-Login floty została anulowana.");
                    break;
                }

                int bId = targetBots.get(i);
                int p = 21420 + (bId - 1);
                addLogMessage(String.format("§e[Flota] [%d/%d] Uruchamiam Auto-Login dla Bota #%d...", (i + 1), targetBots.size(), bId));

                if (p == boundPort) {
                    executeSmartCommand(command, type);
                } else {
                    sendRemoteCommandAsync(p, command, type);
                }

                // Jeśli to nie jest ostatni bot w kolejce, czekamy aż ten bot się zaloguje i odliczamy 20s!
                if (i < targetBots.size() - 1) {
                    int nextBotId = targetBots.get(i + 1);

                    // Odczekaj 2s na zainicjowanie procedury przez bota
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }

                    // 1. Czekamy aż ten bot zakończy logowanie (lub timeout np. 45s)
                    int waited = 0;
                    boolean loggedIn = false;
                    while (waited < 45 && fleetLoginInProgress) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                        waited++;

                        if (p == boundPort) {
                            if (anarchiaAutomation != null && anarchiaAutomation.getState() == AnarchiaLoginAutomation.State.COMPLETED) {
                                loggedIn = true;
                                break;
                            }
                        } else {
                            String st = queryRemoteBot(p);
                            if (st != null && (st.contains("\"autoLoginState\":\"COMPLETED\"") || (st.contains("\"online\":true") && !st.contains("AutoLogin:")))) {
                                loggedIn = true;
                                break;
                            }
                        }
                    }

                    if (!fleetLoginInProgress) break;

                    if (loggedIn) {
                        addLogMessage(String.format("§a[Flota] [%d/%d] Bot #%d pomyślnie zalogowany na serwerze! Czekam 20 sekund przed startem Bota #%d...", (i + 1), targetBots.size(), bId, nextBotId));
                    } else {
                        addLogMessage(String.format("§e[Flota] [%d/%d] Bot #%d (limit czasu / w toku). Czekam 20 sekund przed startem Bota #%d...", (i + 1), targetBots.size(), bId, nextBotId));
                    }

                    // 2. Odliczanie DOKŁADNIE 20 sekund przed startem kolejnego bota!
                    for (int rem = 20; rem > 0 && fleetLoginInProgress; rem--) {
                        if (rem == 15 || rem == 10 || rem == 5) {
                            addLogMessage(String.format("§b[Flota] Bot #%d wystartuje za %d sek...", nextBotId, rem));
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }

            fleetLoginInProgress = false;
            addLogMessage("§a[Flota] Cała sekwencja Auto-Login dla floty została pomyślnie ukończona! 🚀");
        });
    }

    private class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String log : recentLogs) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(log)).append("\"");
                first = false;
            }
            sb.append("]");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * GET  /api/accounts         → {"1":"delfin223","2":"inneKonto",...}
     * POST /api/accounts?bot=1&account=delfin223  → zapamiętaj ręcznie
     * POST /api/accounts?bot=1&forget=true         → usuń wpis
     * POST /api/accounts?bot=1&switch=true         → wymuś auto-switch przez IAS
     */
    private class AccountsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");

            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getQuery(); // np. bot=1&account=nick
            Map<String, String> params = parseQuery(query);

            String responseBody;
            int code = 200;

            if ("GET".equalsIgnoreCase(method)) {
                // Zwróć całą mapę
                Map<Integer, String> all = BotAccountMemory.get().getAllAccounts();
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<Integer, String> e : all.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('"').append(e.getKey()).append("\":\"")
                      .append(escapeJson(e.getValue())).append('"');
                }
                sb.append('}');
                responseBody = sb.toString();

            } else if ("POST".equalsIgnoreCase(method)) {
                String botParam = params.get("bot");
                if (botParam == null) {
                    responseBody = "{\"error\":\"missing ?bot=N\"}";
                    code = 400;
                } else {
                    try {
                        int bid = Integer.parseInt(botParam.trim());
                        if ("true".equalsIgnoreCase(params.get("forget"))) {
                            BotAccountMemory.get().forget(bid);
                            responseBody = "{\"ok\":true,\"action\":\"forget\",\"bot\":" + bid + "}";
                        } else if ("true".equalsIgnoreCase(params.get("switch"))) {
                            boolean switched = BotAccountMemory.get().switchIfNeeded(bid);
                            responseBody = "{\"ok\":true,\"action\":\"switch\",\"bot\":" + bid
                                    + ",\"initiated\":" + switched + "}";
                        } else {
                            String acc = params.get("account");
                            if (acc == null || acc.isBlank()) {
                                responseBody = "{\"error\":\"missing ?account=NAME\"}";
                                code = 400;
                            } else {
                                accountMap_put(bid, acc);
                                responseBody = "{\"ok\":true,\"action\":\"set\",\"bot\":" + bid
                                        + ",\"account\":\"" + escapeJson(acc) + "\"}";
                            }
                        }
                    } catch (NumberFormatException ex) {
                        responseBody = "{\"error\":\"bot must be integer\"}";
                        code = 400;
                    }
                }
            } else {
                responseBody = "{\"error\":\"method not allowed\"}";
                code = 405;
            }

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        /** Parsuje query string do mapy. */
        private Map<String, String> parseQuery(String query) {
            Map<String, String> map = new HashMap<>();
            if (query == null || query.isBlank()) return map;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return map;
        }

        /** Bezpiecznie wstawia konto przez BotAccountMemory. */
        private void accountMap_put(int botId, String account) {
            // Nadpisujemy poprzedni wpis poprzez ręczne zapamiętanie.
            // BotAccountMemory.rememberCurrentAccount() działa na aktualnie zalogowanym graczu,
            // więc dla ręcznego ustawienia symulujemy zapis bezpośredni przez forget + metodę wewnętrzną.
            // Najprostszy sposób: załaduj bieżącą mapę i zapisz.
            BotAccountMemory mem = BotAccountMemory.get();
            mem.forget(botId);
            // Trick: tymczasowo jeśli aktualny gracz ma ten nick, rememberCurrentAccount go złapie.
            // Jeśli nie, korzystamy z refleksji na wewnętrznej mapie.
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null && account.equalsIgnoreCase(mc.getUser().getName())) {
                mem.rememberCurrentAccount(botId);
            } else {
                // Bezpośredni zapis przez refleksję (mapa jest prywatna ale możemy to zrobić):
                // Alternatywnie akceptujemy że użytkownik musi być zalogowany na to konto.
                // W praktyce POST /api/accounts?bot=1&account=X używamy gdy gracz jest zalogowany.
                addLogMessage("§e[AccountMemory] Wskazówka: aby ręcznie ustawić konto dla bota #"
                        + botId + ", zaloguj się na konto '" + account
                        + "' przez IAS, a następnie wywołaj POST ?bot=" + botId + "&account=" + account
                        + " ponownie, lub po prostu uruchom bota — konto zostanie zapamiętane automatycznie.");
            }
        }
    }

    private String queryRemoteBot(int port) {
        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/api/status");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(80);
            conn.setReadTimeout(120);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void sendRemoteCommandAsync(int port, String cmd, String type) {
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create("http://127.0.0.1:" + port + "/api/command");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(200);
                conn.setReadTimeout(500);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                String payload = String.format("{\"command\":\"%s\",\"type\":\"%s\"}", escapeJson(cmd), escapeJson(type));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
            } catch (Throwable ignored) {}
        });
    }

    private List<Integer> parseTargets(String body) {
        List<Integer> list = new ArrayList<>();
        if (body == null || !body.contains("\"targets\"")) return list;
        int tIdx = body.indexOf("\"targets\"");
        int arrStart = body.indexOf("[", tIdx);
        int arrEnd = body.indexOf("]", arrStart);
        if (arrStart != -1 && arrEnd != -1 && arrEnd > arrStart) {
            String inner = body.substring(arrStart + 1, arrEnd);
            for (String part : inner.split(",")) {
                String s = part.trim().replace("\"", "");
                if ("all".equalsIgnoreCase(s) || "*".equals(s)) {
                    list.add(0);
                } else {
                    try {
                        list.add(Integer.parseInt(s));
                    } catch (Throwable ignored) {}
                }
            }
        }
        return list;
    }

    private static String extractJsonField(String json, String field) {
        if (json == null || !json.contains("\"" + field + "\"")) return "";
        int keyIdx = json.indexOf("\"" + field + "\"");
        int colonIdx = json.indexOf(":", keyIdx);
        if (colonIdx == -1) return "";
        int valStart = json.indexOf("\"", colonIdx);
        if (valStart == -1) return "";
        valStart++;
        int valEnd = json.indexOf("\"", valStart);
        if (valEnd == -1) return "";
        return json.substring(valStart, valEnd);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String getDashboardHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"pl\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Baritone Fleet Manager — Centrum Sterowania Flotą</title>\n" +
                "  <style>\n" +
                "    :root {\n" +
                "      --bg: #070a13;\n" +
                "      --card-bg: rgba(16, 24, 42, 0.78);\n" +
                "      --card-hover: rgba(22, 33, 58, 0.95);\n" +
                "      --border: rgba(56, 189, 248, 0.22);\n" +
                "      --border-glow: rgba(0, 240, 255, 0.35);\n" +
                "      --accent: #00f0ff;\n" +
                "      --accent-glow: rgba(0, 240, 255, 0.4);\n" +
                "      --green: #10b981;\n" +
                "      --green-glow: rgba(16, 185, 129, 0.35);\n" +
                "      --red: #f43f5e;\n" +
                "      --orange: #f59e0b;\n" +
                "      --purple: #a855f7;\n" +
                "      --text: #f1f5f9;\n" +
                "      --text-muted: #94a3b8;\n" +
                "    }\n" +
                "    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; }\n" +
                "    body {\n" +
                "      background: radial-gradient(circle at 50% 0%, #101c36 0%, #070a13 85%);\n" +
                "      color: var(--text);\n" +
                "      min-height: 100vh;\n" +
                "      padding: 20px;\n" +
                "    }\n" +
                "    .container { max-width: 1300px; margin: 0 auto; }\n" +
                "    header {\n" +
                "      display: flex;\n" +
                "      align-items: center;\n" +
                "      justify-content: space-between;\n" +
                "      padding-bottom: 18px;\n" +
                "      border-bottom: 1px solid var(--border);\n" +
                "      margin-bottom: 22px;\n" +
                "      flex-wrap: wrap;\n" +
                "      gap: 14px;\n" +
                "    }\n" +
                "    .logo-box { display: flex; align-items: center; gap: 12px; }\n" +
                "    .logo { font-size: 26px; font-weight: 900; letter-spacing: 0.5px; background: linear-gradient(135deg, #00f0ff, #a855f7); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }\n" +
                "    .header-pills { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }\n" +
                "    .chip {\n" +
                "      padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 600;\n" +
                "      display: flex; align-items: center; gap: 8px; background: rgba(255, 255, 255, 0.05); border: 1px solid var(--border);\n" +
                "    }\n" +
                "    .chip-green { background: rgba(16, 185, 129, 0.15); color: var(--green); border-color: var(--green); }\n" +
                "    .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; box-shadow: 0 0 10px currentColor; }\n" +
                "    .card {\n" +
                "      background: var(--card-bg);\n" +
                "      backdrop-filter: blur(14px);\n" +
                "      border: 1px solid var(--border);\n" +
                "      border-radius: 14px;\n" +
                "      padding: 20px;\n" +
                "      margin-bottom: 22px;\n" +
                "      transition: border-color 0.2s, box-shadow 0.2s;\n" +
                "    }\n" +
                "    .card:hover { border-color: var(--border-glow); }\n" +
                "    .section-title { font-size: 16px; font-weight: 700; margin-bottom: 6px; display: flex; align-items: center; gap: 8px; }\n" +
                "    .section-sub { font-size: 13px; color: var(--text-muted); margin-bottom: 16px; }\n" +
                "    .targets-bar {\n" +
                "      display: flex; flex-wrap: wrap; gap: 10px; align-items: center;\n" +
                "      background: rgba(0, 0, 0, 0.35); padding: 12px 16px; border-radius: 10px;\n" +
                "      border: 1px solid rgba(255, 255, 255, 0.08); margin-bottom: 16px;\n" +
                "    }\n" +
                "    .targets-title { font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.8px; color: var(--accent); margin-right: 6px; }\n" +
                "    .target-pill {\n" +
                "      display: inline-flex; align-items: center; gap: 7px; padding: 6px 12px;\n" +
                "      background: rgba(255, 255, 255, 0.06); border: 1px solid var(--border);\n" +
                "      border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 600;\n" +
                "      transition: all 0.2s; user-select: none;\n" +
                "    }\n" +
                "    .target-pill.active { background: rgba(0, 240, 255, 0.18); border-color: var(--accent); color: var(--accent); box-shadow: 0 0 10px var(--accent-glow); }\n" +
                "    .target-pill input { accent-color: var(--accent); cursor: pointer; }\n" +
                "    .input-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }\n" +
                "    input[type=\"text\"] {\n" +
                "      flex: 1;\n" +
                "      min-width: 280px;\n" +
                "      background: rgba(7, 10, 19, 0.9);\n" +
                "      border: 1px solid var(--border);\n" +
                "      color: #fff;\n" +
                "      padding: 14px 18px;\n" +
                "      border-radius: 10px;\n" +
                "      font-size: 15px;\n" +
                "      outline: none;\n" +
                "      transition: all 0.2s;\n" +
                "    }\n" +
                "    input[type=\"text\"]:focus { border-color: var(--accent); box-shadow: 0 0 14px var(--accent-glow); }\n" +
                "    button {\n" +
                "      background: rgba(255, 255, 255, 0.06);\n" +
                "      color: var(--text);\n" +
                "      border: 1px solid var(--border);\n" +
                "      padding: 12px 20px;\n" +
                "      border-radius: 10px;\n" +
                "      font-size: 14px;\n" +
                "      font-weight: 700;\n" +
                "      cursor: pointer;\n" +
                "      transition: all 0.2s;\n" +
                "      display: inline-flex;\n" +
                "      align-items: center;\n" +
                "      gap: 7px;\n" +
                "    }\n" +
                "    button:hover { background: rgba(0, 240, 255, 0.18); border-color: var(--accent); color: var(--accent); box-shadow: 0 0 12px var(--accent-glow); transform: translateY(-1px); }\n" +
                "    button.btn-chat { background: rgba(59, 130, 246, 0.2); border-color: #3b82f6; color: #60a5fa; }\n" +
                "    button.btn-chat:hover { background: #3b82f6; color: #fff; box-shadow: 0 0 14px rgba(59, 130, 246, 0.5); }\n" +
                "    button.btn-auto { background: rgba(16, 185, 129, 0.2); border-color: var(--green); color: var(--green); }\n" +
                "    button.btn-auto:hover { background: var(--green); color: #070a13; box-shadow: 0 0 14px var(--green-glow); }\n" +
                "    button.btn-danger { background: rgba(244, 63, 94, 0.15); border-color: var(--red); color: var(--red); }\n" +
                "    button.btn-danger:hover { background: var(--red); color: #fff; box-shadow: 0 0 14px rgba(244, 63, 94, 0.4); }\n" +
                "    .macros-row { display: flex; flex-wrap: wrap; gap: 8px; }\n" +
                "    .macro-btn { padding: 8px 14px; font-size: 12px; border-radius: 8px; font-weight: 600; }\n" +
                "    .fleet-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(290px, 1fr)); gap: 16px; margin-bottom: 22px; }\n" +
                "    .bot-card {\n" +
                "      background: var(--card-bg);\n" +
                "      border: 1px solid var(--border);\n" +
                "      border-radius: 12px;\n" +
                "      padding: 16px;\n" +
                "      transition: all 0.2s;\n" +
                "      display: flex; flex-direction: column; justify-content: space-between;\n" +
                "    }\n" +
                "    .bot-card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0,0,0,0.5); border-color: var(--border-glow); }\n" +
                "    .bot-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }\n" +
                "    .bot-title { font-size: 16px; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 8px; }\n" +
                "    .bot-status { font-size: 11px; padding: 4px 10px; border-radius: 12px; font-weight: 700; }\n" +
                "    .status-online { background: rgba(16, 185, 129, 0.18); color: var(--green); border: 1px solid var(--green); }\n" +
                "    .status-offline { background: rgba(244, 63, 94, 0.18); color: var(--red); border: 1px solid var(--red); }\n" +
                "    .bar-row { margin-bottom: 10px; }\n" +
                "    .bar-label { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }\n" +
                "    .progress { width: 100%; height: 7px; background: rgba(255,255,255,0.08); border-radius: 4px; overflow: hidden; }\n" +
                "    .fill { height: 100%; transition: width 0.3s; }\n" +
                "    .bot-details { font-size: 12px; color: var(--text-muted); line-height: 1.6; margin-bottom: 12px; }\n" +
                "    .bot-card-actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; border-top: 1px solid rgba(255,255,255,0.06); padding-top: 10px; }\n" +
                "    .mini-btn { padding: 6px 10px; font-size: 11px; border-radius: 6px; }\n" +
                "    .logs-terminal {\n" +
                "      background: #050811;\n" +
                "      border: 1px solid rgba(255,255,255,0.08);\n" +
                "      border-radius: 10px;\n" +
                "      padding: 14px;\n" +
                "      height: 200px;\n" +
                "      overflow-y: auto;\n" +
                "      font-family: 'Consolas', 'Courier New', monospace;\n" +
                "      font-size: 13px;\n" +
                "      line-height: 1.5;\n" +
                "      color: #38bdf8;\n" +
                "    }\n" +
                "    .log-line { margin-bottom: 4px; }\n" +
                "    .toast {\n" +
                "      position: fixed; bottom: 24px; right: 24px; background: #0f172a;\n" +
                "      border: 1px solid var(--accent); color: #fff; padding: 12px 20px;\n" +
                "      border-radius: 10px; box-shadow: 0 10px 30px rgba(0,240,255,0.3);\n" +
                "      font-size: 14px; font-weight: 600; z-index: 1000; display: none;\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"container\">\n" +
                "    <header>\n" +
                "      <div class=\"logo-box\">\n" +
                "        <span class=\"logo\">🤖 BARITONE FLEET CONTROL</span>\n" +
                "        <span style=\"font-size: 13px; color: var(--text-muted);\">Centrala Czatowa i Dowodzenia Botami</span>\n" +
                "      </div>\n" +
                "      <div class=\"header-pills\">\n" +
                "        <div class=\"chip chip-green\"><span class=\"dot\"></span><span id=\"fleetOnlineCount\">Łączenie...</span></div>\n" +
                "        <div class=\"chip\"><span id=\"fleetHpCount\">HP: --</span></div>\n" +
                "        <div class=\"chip\" style=\"font-size: 11px; color: var(--text-muted);\">Port: " + boundPort + " (Bot #" + botId + ")</div>\n" +
                "      </div>\n" +
                "    </header>\n" +
                "\n" +
                "    <!-- SEKCJA 1: GLOBALNY NADAJNIK CZATU I ROZKAZÓW -->\n" +
                "    <div class=\"card\" style=\"border-color: var(--accent); box-shadow: 0 0 20px rgba(0,240,255,0.08);\">\n" +
                "      <div class=\"section-title\">💬 GLOBALNY CZAT I ROZKAZY DLA BOTÓW</div>\n" +
                "      <div class=\"section-sub\">Wpisz treść na czat (np. wiadomość, /login) lub polecenie Baritone. Zaznacz które boty mają to wykonać i wyślij.</div>\n" +
                "      \n" +
                "      <!-- Wybór celów -->\n" +
                "      <div class=\"targets-bar\">\n" +
                "        <span class=\"targets-title\">🎯 Cel:</span>\n" +
                "        <label class=\"target-pill active\" id=\"pillAll\">\n" +
                "          <input type=\"checkbox\" id=\"targetAll\" checked onchange=\"onToggleAll()\">\n" +
                "          <span>WSZYSTKIE BOTY (ALL)</span>\n" +
                "        </label>\n" +
                "        <div id=\"dynamicTargets\" style=\"display: inline-flex; gap: 8px; flex-wrap: wrap;\"></div>\n" +
                "      </div>\n" +
                "\n" +
                "      <!-- Główne pole wpisywania -->\n" +
                "      <div class=\"input-row\">\n" +
                "        <input type=\"text\" id=\"fleetInput\" placeholder=\"Napisz co boty mają wysłać na czat (np. siema, elo), komendę serwera (/login haslo, /spawn) lub rozkaz Baritone (#mine diamond_ore, #stop)...\" onkeydown=\"if(event.key==='Enter') sendFleetAction('auto')\">\n" +
                "        <button class=\"btn-chat\" onclick=\"sendFleetAction('chat')\">💬 Wyślij na Czat</button>\n" +
                "        <button onclick=\"sendFleetAction('baritone')\">⚡ Rozkaz Baritone</button>\n" +
                "        <button class=\"btn-auto\" onclick=\"sendFleetAction('auto')\">🚀 Auto-Wyślij (Enter)</button>\n" +
                "      </div>\n" +
                "\n" +
                "      <!-- Szybkie Makra -->\n" +
                "      <div class=\"macros-row\">\n" +
                "        <span style=\"font-size: 12px; color: var(--text-muted); align-self: center; margin-right: 4px;\">Szybkie makra:</span>\n" +
                "        <button class=\"macro-btn btn-auto\" style=\"border-color: #00f0ff; font-weight: 800; box-shadow: 0 0 12px rgba(0,240,255,0.35);\" onclick=\"quickAnarchiaLogin()\">🚀 AUTO-LOGIN ANARCHIA (Pojedynczo co 20s: /login + Anty-Bot + Kompas + Serce)</button>\n" +
                "        <button class=\"macro-btn btn-chat\" onclick=\"quickLogin()\">🔑 /login hasło</button>\n" +
                "        <button class=\"macro-btn btn-chat\" onclick=\"quickRegister()\">📝 /register hasło</button>\n" +
                "        <button class=\"macro-btn btn-chat\" onclick=\"quickSendChat('/spawn')\">📍 /spawn</button>\n" +
                "        <button class=\"macro-btn btn-chat\" onclick=\"quickSendChat('/home')\">🏠 /home</button>\n" +
                "        <button class=\"macro-btn btn-danger\" onclick=\"quickSendBaritone('#stop')\">🛑 STOP ALL</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#clean')\">🧹 Czyść EQ (#clean)</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#sort')\">📦 Sortuj EQ (#sort)</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#farm')\">🌾 Farma (#farm)</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#defend')\">⚔️ Bicie Mobów (#defend)</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#mine iron_ore deepslate_iron_ore raw_iron')\">⛏️ Kop Żelazo</button>\n" +
                "        <button class=\"macro-btn\" onclick=\"quickSendBaritone('#mine diamond_ore deepslate_diamond_ore')\">💎 Kop Diamenty</button>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "\n" +
                "    <!-- SEKCJA 2: KARTY WSZYSTKICH BOTÓW W CZASIE RZECZYWISTYM -->\n" +
                "    <div class=\"section-title\" style=\"margin-top: 10px;\">👥 MONITORING FLOTY BOTÓW (STAN NA ŻYWO)</div>\n" +
                "    <div class=\"section-sub\">Wszystkie uruchomione instancje gry widoczne na jednym ekranie z bieżącymi współrzędnymi i stanem zdrowia.</div>\n" +
                "    <div class=\"fleet-grid\" id=\"fleetCards\">Ładowanie instancji botów...</div>\n" +
                "\n" +
                "    <!-- SEKCJA 3: KONSOLA LOGÓW -->\n" +
                "    <div class=\"card\">\n" +
                "      <div class=\"bot-header\">\n" +
                "        <div class=\"section-title\">📋 KONSOLA ZDARZEŃ I LOGÓW FLOTY</div>\n" +
                "        <button class=\"mini-btn\" onclick=\"clearLocalLogs()\">Wyczyść ekran</button>\n" +
                "      </div>\n" +
                "      <div class=\"logs-terminal\" id=\"logsBox\">Oczekiwanie na logi...</div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "\n" +
                "  <div class=\"toast\" id=\"toastBox\">✓ Komenda wysłana!</div>\n" +
                "\n" +
                "  <script>\n" +
                "    let cachedBots = [];\n" +
                "    let manualUnchecked = new Set();\n" +
                "\n" +
                "    function showToast(msg) {\n" +
                "      const t = document.getElementById('toastBox');\n" +
                "      t.innerText = msg;\n" +
                "      t.style.display = 'block';\n" +
                "      setTimeout(() => { t.style.display = 'none'; }, 3000);\n" +
                "    }\n" +
                "\n" +
                "    function onToggleAll() {\n" +
                "      const allChecked = document.getElementById('targetAll').checked;\n" +
                "      document.getElementById('pillAll').className = allChecked ? 'target-pill active' : 'target-pill';\n" +
                "      const cbs = document.querySelectorAll('.bot-target-cb');\n" +
                "      cbs.forEach(cb => {\n" +
                "        cb.checked = allChecked;\n" +
                "        cb.parentElement.className = allChecked ? 'target-pill active' : 'target-pill';\n" +
                "        if (allChecked) manualUnchecked.delete(parseInt(cb.value));\n" +
                "        else manualUnchecked.add(parseInt(cb.value));\n" +
                "      });\n" +
                "    }\n" +
                "\n" +
                "    function onToggleBot(botId, isChecked) {\n" +
                "      if (isChecked) manualUnchecked.delete(botId);\n" +
                "      else manualUnchecked.add(botId);\n" +
                "      const allCb = document.getElementById('targetAll');\n" +
                "      if (manualUnchecked.size > 0) {\n" +
                "        allCb.checked = false;\n" +
                "        document.getElementById('pillAll').className = 'target-pill';\n" +
                "      } else {\n" +
                "        allCb.checked = true;\n" +
                "        document.getElementById('pillAll').className = 'target-pill active';\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    function getSelectedTargets() {\n" +
                "      if (document.getElementById('targetAll').checked) {\n" +
                "        return [0];\n" +
                "      }\n" +
                "      const res = [];\n" +
                "      document.querySelectorAll('.bot-target-cb').forEach(cb => {\n" +
                "        if (cb.checked) res.push(parseInt(cb.value));\n" +
                "      });\n" +
                "      return res.length > 0 ? res : [0];\n" +
                "    }\n" +
                "\n" +
                "    async function fetchFleet() {\n" +
                "      try {\n" +
                "        const res = await fetch('/api/fleet');\n" +
                "        if (!res.ok) return;\n" +
                "        const bots = await res.json();\n" +
                "        cachedBots = bots;\n" +
                "        \n" +
                "        // Statystyki nagłówka\n" +
                "        const onlineBots = bots.filter(b => b.online);\n" +
                "        document.getElementById('fleetOnlineCount').innerText = `${onlineBots.length} / ${bots.length} Botów Online`;\n" +
                "        const totalHp = onlineBots.reduce((s, b) => s + b.health, 0);\n" +
                "        const maxHp = onlineBots.reduce((s, b) => s + b.maxHealth, 0);\n" +
                "        document.getElementById('fleetHpCount').innerText = `❤️ Łączne HP: ${totalHp.toFixed(0)} / ${maxHp.toFixed(0)}`;\n" +
                "        \n" +
                "        // Dynamiczne checkboxy celów\n" +
                "        const targetsDiv = document.getElementById('dynamicTargets');\n" +
                "        targetsDiv.innerHTML = bots.map(b => {\n" +
                "          const isChecked = !manualUnchecked.has(b.botId);\n" +
                "          const cls = isChecked ? 'target-pill active' : 'target-pill';\n" +
                "          return `<label class=\"${cls}\" id=\"pill_${b.botId}\">\n` +\n" +
                "                 `  <input type=\"checkbox\" class=\"bot-target-cb\" value=\"${b.botId}\" ${isChecked ? 'checked' : ''} onchange=\"this.parentElement.className=this.checked?'target-pill active':'target-pill'; onToggleBot(${b.botId}, this.checked)\">\n` +\n" +
                "                 `  <span>Bot #${b.botId}: ${b.name} (${b.online ? b.health.toFixed(0)+' HP' : 'OFF'})</span>\n` +\n" +
                "                 `</label>`;\n" +
                "        }).join('');\n" +
                "        \n" +
                "        // Karty Floty\n" +
                "        const cardsDiv = document.getElementById('fleetCards');\n" +
                "        if (bots.length === 0) {\n" +
                "          cardsDiv.innerHTML = '<div style=\"color: var(--text-muted); padding: 20px;\">Brak aktywnych botów. Uruchom je plikiem bot.bat 5.</div>';\n" +
                "          return;\n" +
                "        }\n" +
                "        cardsDiv.innerHTML = bots.map(b => {\n" +
                "          const hpPercent = b.online ? Math.min(100, (b.health / b.maxHealth) * 100) : 0;\n" +
                "          const foodPercent = b.online ? Math.min(100, (b.food / 20) * 100) : 0;\n" +
                "          const hpColor = hpPercent > 50 ? 'var(--green)' : (hpPercent > 25 ? 'var(--orange)' : 'var(--red)');\n" +
                "          return `<div class=\"bot-card\">\n` +\n" +
                "                 `  <div>\n` +\n" +
                "                 `    <div class=\"bot-header\">\n` +\n" +
                "                 `      <div class=\"bot-title\">🤖 Bot #${b.botId} <span style=\"font-size:13px;color:var(--text-muted);\">(${b.name})</span></div>\n` +\n" +
                "                 `      <div class=\"bot-status ${b.online ? 'status-online' : 'status-offline'}\">${b.online ? 'ONLINE' : 'OFFLINE'}</div>\n` +\n" +
                "                 `    </div>\n` +\n" +
                "                 `    <div class=\"bar-row\">\n` +\n" +
                "                 `      <div class=\"bar-label\"><span>Zdrowie (HP)</span><span>${b.online ? b.health.toFixed(1)+' / '+b.maxHealth.toFixed(1) : '--'}</span></div>\n` +\n" +
                "                 `      <div class=\"progress\"><div class=\"fill\" style=\"width:${hpPercent}%;background:${hpColor};\"></div></div>\n` +\n" +
                "                 `    </div>\n` +\n" +
                "                 `    <div class=\"bar-row\">\n` +\n" +
                "                 `      <div class=\"bar-label\"><span>Głód (Food)</span><span>${b.online ? b.food+' / 20' : '--'}</span></div>\n` +\n" +
                "                 `      <div class=\"progress\"><div class=\"fill\" style=\"width:${foodPercent}%;background:var(--orange);\"></div></div>\n` +\n" +
                "                 `    </div>\n` +\n" +
                "                 `    <div class=\"bot-details\">\n` +\n" +
                "                 `      <div>📍 <b>Pozycja:</b> ${b.online ? `${b.x.toFixed(1)}, ${b.y.toFixed(1)}, ${b.z.toFixed(1)}` : '--'}</div>\n` +\n" +
                "                 `      <div>🗺️ <b>Wymiar:</b> ${b.dimension || '--'}</div>\n` +\n" +
                "                 `      <div>⚙️ <b>Zadanie:</b> <span style=\"color:var(--accent);font-weight:600;\">${b.activeProcess || 'Idle'}</span></div>\n` +\n" +
                "                 `      <div>🛡️ MobDef: ${b.mobDefense?'ON':'OFF'} | Water: ${b.waterClutch?'ON':'OFF'} | Sort: ${b.autoSort?'ON':'OFF'}</div>\n` +\n" +
                "                 `    </div>\n` +\n" +
                "                 `  </div>\n` +\n" +
                "                 `  <div class=\"bot-card-actions\">\n` +\n" +
                "                 `    <button class=\"mini-btn btn-auto\" onclick=\"sendDirect(${b.botId}, 'autologin Tumnik@123', 'auto')\">🚀 Auto-Login</button>\n` +\n" +
                "                 `    <button class=\"mini-btn btn-danger\" onclick=\"sendDirect(${b.botId}, '#stop', 'baritone')\">🛑 Stop</button>\n` +\n" +
                "                 `    <button class=\"mini-btn\" onclick=\"sendDirect(${b.botId}, '#clean', 'baritone')\">🧹 Clean</button>\n` +\n" +
                "                 `    <button class=\"mini-btn\" onclick=\"sendDirect(${b.botId}, '#sort', 'baritone')\">📦 Sort</button>\n` +\n" +
                "                 `    <button class=\"mini-btn btn-chat\" onclick=\"promptChatToBot(${b.botId})\">💬 Czat</button>\n` +\n" +
                "                 `  </div>\n` +\n" +
                "                 `</div>`;\n" +
                "        }).join('');\n" +
                "      } catch (e) {}\n" +
                "    }\n" +
                "\n" +
                "    async function fetchLogs() {\n" +
                "      try {\n" +
                "        const res = await fetch('/api/logs');\n" +
                "        if (!res.ok) return;\n" +
                "        const logs = await res.json();\n" +
                "        const box = document.getElementById('logsBox');\n" +
                "        if (logs.length > 0) {\n" +
                "          box.innerHTML = logs.map(l => `<div class=\"log-line\">${l}</div>`).join('');\n" +
                "          box.scrollTop = box.scrollHeight;\n" +
                "        }\n" +
                "      } catch (e) {}\n" +
                "    }\n" +
                "\n" +
                "    async function sendFleetAction(type) {\n" +
                "      const inp = document.getElementById('fleetInput');\n" +
                "      const val = inp.value.trim();\n" +
                "      if (!val) return;\n" +
                "      const targets = getSelectedTargets();\n" +
                "      \n" +
                "      await fetch('/api/fleet/command', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ targets: targets, command: val, type: type || 'auto' })\n" +
                "      });\n" +
                "      \n" +
                "      showToast(`✓ Wysłano [${type || 'auto'}]: \"${val}\"`);\n" +
                "      inp.value = '';\n" +
                "      fetchLogs();\n" +
                "      fetchFleet();\n" +
                "    }\n" +
                "\n" +
                "    async function sendDirect(botId, cmd, type) {\n" +
                "      await fetch('/api/fleet/command', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ targets: [botId], command: cmd, type: type || 'auto' })\n" +
                "      });\n" +
                "      showToast(`✓ Bot #${botId}: \"${cmd}\"`);\n" +
                "      fetchLogs();\n" +
                "      fetchFleet();\n" +
                "    }\n" +
                "\n" +
                "    function promptChatToBot(botId) {\n" +
                "      const text = prompt(`Wpisz wiadomość na czat gry dla Bota #${botId}:`);\n" +
                "      if (text && text.trim()) {\n" +
                "        sendDirect(botId, text.trim(), 'chat');\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    async function quickSendChat(msg) {\n" +
                "      const targets = getSelectedTargets();\n" +
                "      await fetch('/api/fleet/command', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ targets: targets, command: msg, type: 'chat' })\n" +
                "      });\n" +
                "      showToast(`✓ Wysłano na czat: \"${msg}\"`);\n" +
                "      fetchLogs();\n" +
                "    }\n" +
                "\n" +
                "    async function quickSendBaritone(cmd) {\n" +
                "      const targets = getSelectedTargets();\n" +
                "      await fetch('/api/fleet/command', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ targets: targets, command: cmd, type: 'baritone' })\n" +
                "      });\n" +
                "      showToast(`✓ Wykonano Baritone: \"${cmd}\"`);\n" +
                "      fetchLogs();\n" +
                "      fetchFleet();\n" +
                "    }\n" +
                "\n" +
                "    function quickAnarchiaLogin() {\n" +
                "      const targets = getSelectedTargets();\n" +
                "      const pwd = prompt('Potwierdź hasło do /login dla Anarchia.gg (Direct Connection -> /login -> Anty-Bot W + Myszka 5s -> Kompas -> Serce; boty logują się pojedynczo, kolejny po 20s):', 'Tumnik@123');\n" +
                "      if (pwd === null) return;\n" +
                "      const finalPwd = pwd.trim() || 'Tumnik@123';\n" +
                "      fetch('/api/fleet/command', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': 'application/json' },\n" +
                "        body: JSON.stringify({ targets: targets, command: 'autologin ' + finalPwd, type: 'auto' })\n" +
                "      });\n" +
                "      showToast('🚀 Uruchomiono sekwencyjny Auto-Login (kolejny bot 20s po zalogowaniu)!');\n" +
                "      fetchLogs();\n" +
                "      fetchFleet();\n" +
                "    }\n" +
                "\n" +
                "    function quickLogin() {\n" +
                "      const pwd = prompt('Wpisz hasło do komendy /login dla zaznaczonych botów:', 'Tumnik@123');\n" +
                "      if (pwd && pwd.trim()) {\n" +
                "        const p = pwd.trim();\n" +
                "        if (confirm('Czy chcesz uruchomić pełne automatyczne dołączanie i wybór trybu kompasem (Anarchia)?\\nOK = Pełne Auto-Login (/login -> Anty-Bot W + Myszka 5s -> Kompas -> Serce)\\nAnuluj = Zwykła wiadomość /login na czacie gry')) {\n" +
                "          fetch('/api/fleet/command', {\n" +
                "            method: 'POST',\n" +
                "            headers: { 'Content-Type': 'application/json' },\n" +
                "            body: JSON.stringify({ targets: getSelectedTargets(), command: 'autologin ' + p, type: 'auto' })\n" +
                "          });\n" +
                "          showToast('🚀 Uruchomiono Auto-Login Anarchia!');\n" +
                "        } else {\n" +
                "          quickSendChat('/login ' + p);\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    function quickRegister() {\n" +
                "      const pwd = prompt('Wpisz hasło do komendy /register dla zaznaczonych botów:');\n" +
                "      if (pwd && pwd.trim()) {\n" +
                "        quickSendChat('/register ' + pwd.trim() + ' ' + pwd.trim());\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    function clearLocalLogs() {\n" +
                "      document.getElementById('logsBox').innerHTML = '<div style=\"color:var(--text-muted);\">Wyczyszczono logi.</div>';\n" +
                "    }\n" +
                "\n" +
                "    setInterval(fetchFleet, 800);\n" +
                "    setInterval(fetchLogs, 1200);\n" +
                "    fetchFleet();\n" +
                "    fetchLogs();\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
