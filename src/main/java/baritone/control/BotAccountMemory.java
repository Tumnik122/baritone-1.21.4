package baritone.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * BotAccountMemory - trwalosc konta per-slot bota.
 *
 * Jak to dziala:
 * 1. Gdy gracz przelacza konto przez IAS i uruchamia bota,
 *    Baritone wykrywa aktualna nazwe i zapisuje mapowanie botId -> accountName.
 * 2. Przy kolejnym starcie tego samego bota (botId) odczytuje zapisane konto
 *    i jesli aktualnie zalogowane konto jest inne, automatycznie wywoluje login
 *    przez refleksje na IAS.
 * 3. Plik bot_accounts.json przechowuje mapowanie: {"1":"delfin223","2":"inneKonto"}
 *    w katalogu .minecraft.
 *
 * Nie przechowujemy tokenow ani hasel - wylacznie nick wyswietlany gracza.
 */
public final class BotAccountMemory {

    private static final Logger LOG = Logger.getLogger(BotAccountMemory.class.getName());
    private static final String FILE_NAME = "bot_accounts.json";

    private static BotAccountMemory INSTANCE;

    private final Path filePath;
    /** botId (1-5) -> accountName */
    private final Map<Integer, String> accountMap = new LinkedHashMap<>();

    private BotAccountMemory(Path minecraftDir) {
        this.filePath = minecraftDir.resolve(FILE_NAME);
        load();
    }

    public static synchronized BotAccountMemory get() {
        if (INSTANCE == null) {
            Minecraft mc = Minecraft.getInstance();
            Path dir = (mc != null && mc.gameDirectory != null)
                    ? mc.gameDirectory.toPath()
                    : Path.of(".");
            INSTANCE = new BotAccountMemory(dir);
        }
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Publiczne API
    // -------------------------------------------------------------------------

    /**
     * Zapisuje aktualnie zalogowane konto dla podanego botId.
     * Wywoływane automatycznie przez WindowsBotController przy starcie bota.
     */
    public void rememberCurrentAccount(int botId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        User user = mc.getUser();
        if (user == null) return;
        String name = user.getName();
        if (name == null || name.isBlank()) return;

        String prev = accountMap.get(botId);
        if (!name.equals(prev)) {
            accountMap.put(botId, name);
            save();
            LOG.info("[BotAccountMemory] Bot #" + botId + " -> zapamiętano konto: " + name);
        }
    }

    /** Zwraca zapamietana nazwe konta dla botId, lub null jesli brak wpisu. */
    public String getRememberedAccount(int botId) {
        return accountMap.get(botId);
    }

    /** Sprawdza czy aktualnie zalogowane konto zgadza sie z zapamietanym dla botId. */
    public boolean isCorrectAccount(int botId) {
        String remembered = accountMap.get(botId);
        if (remembered == null) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return true;
        User user = mc.getUser();
        if (user == null) return true;
        return remembered.equalsIgnoreCase(user.getName());
    }

    /** Opis zapamietanego konta dla HUD / panelu. */
    public String describe(int botId) {
        String name = accountMap.get(botId);
        return name != null ? name : "(brak wpisu)";
    }

    /** Usuwa zapamietane konto dla botId. */
    public void forget(int botId) {
        if (accountMap.remove(botId) != null) {
            save();
        }
    }

    /** Pelna mapa do odczytu (np. dla endpoint HTTP). */
    public Map<Integer, String> getAllAccounts() {
        return Collections.unmodifiableMap(accountMap);
    }

    /**
     * Jesli aktualnie zalogowane konto ROZNI SIE od zapamietanego dla botId,
     * wywoluje auto-login przez IAS refleksje.
     * Zwraca true jesli inicjuje switch, false gdy switch nie jest potrzebny
     * lub niemozliwy.
     */
    public boolean switchIfNeeded(int botId) {
        if (isCorrectAccount(botId)) return false;
        String target = accountMap.get(botId);
        if (target == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;

        LOG.info("[BotAccountMemory] Bot #" + botId
                + " -> aktualnie: " + (mc.getUser() != null ? mc.getUser().getName() : "?")
                + ", wymagane: " + target + ". Inicjuje przelaczenie...");

        boolean ok = tryIASLogin(mc, target);
        if (!ok) {
            LOG.warning("[BotAccountMemory] Nie udalo sie przelaczyd konta dla bota #" + botId
                    + ". Upewnij sie ze IAS jest zainstalowany i konto '" + target + "' jest dodane.");
        }
        return ok;
    }

    // -------------------------------------------------------------------------
    // IAS refleksja
    // -------------------------------------------------------------------------

    private boolean tryIASLogin(Minecraft mc, String target) {
        try {
            Class<?> iasClass = Class.forName("ru.vidtu.ias.IAS");

            // Pobierz liste kont
            List<?> accounts = null;
            try {
                accounts = (List<?>) iasClass.getMethod("accounts").invoke(null);
            } catch (NoSuchMethodException ignored) {}
            if (accounts == null) {
                try {
                    accounts = (List<?>) iasClass.getMethod("getAccounts").invoke(null);
                } catch (NoSuchMethodException ignored) {}
            }
            if (accounts == null) {
                try {
                    var f = iasClass.getDeclaredField("ACCOUNTS");
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val instanceof List<?> l) accounts = l;
                } catch (NoSuchFieldException ignored) {}
            }

            if (accounts == null || accounts.isEmpty()) {
                LOG.warning("[BotAccountMemory] IAS: lista kont jest pusta lub niedostepna.");
                return false;
            }

            // Znajdz konto o pasujacym nicku
            Object targetAcc = null;
            for (Object acc : accounts) {
                String name = getAccountName(acc);
                if (target.equalsIgnoreCase(name)) {
                    targetAcc = acc;
                    break;
                }
            }

            if (targetAcc == null) {
                LOG.warning("[BotAccountMemory] IAS: konto '" + target + "' nie znalezione na liscie IAS.");
                return false;
            }

            final Object finalAcc = targetAcc;
            mc.execute(() -> {
                try {
                    // IAS 9.x: Account.login(Minecraft) lub IAS.login(Account)
                    try {
                        finalAcc.getClass().getMethod("login", Minecraft.class).invoke(finalAcc, mc);
                        return;
                    } catch (NoSuchMethodException ignored) {}
                    try {
                        iasClass.getMethod("login", finalAcc.getClass()).invoke(null, finalAcc);
                    } catch (NoSuchMethodException ignored) {}
                } catch (Throwable t) {
                    LOG.warning("[BotAccountMemory] Blad IAS login: " + t);
                }
            });
            return true;

        } catch (ClassNotFoundException e) {
            LOG.warning("[BotAccountMemory] IAS (ru.vidtu.ias.IAS) nie znaleziony. Czy IAS jest zainstalowany?");
            return false;
        } catch (Throwable t) {
            LOG.warning("[BotAccountMemory] Blad refleksji IAS: " + t);
            return false;
        }
    }

    /** Wyciaga nazwe konta z obiektu Account przez refleksje. */
    private static String getAccountName(Object acc) {
        if (acc == null) return null;
        try {
            return (String) acc.getClass().getMethod("name").invoke(acc);
        } catch (Throwable ignored) {}
        try {
            var f = acc.getClass().getDeclaredField("name");
            f.setAccessible(true);
            return (String) f.get(acc);
        } catch (Throwable ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Serializacja (prosty JSON bez zewnetrznych bibliotek)
    // -------------------------------------------------------------------------

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            String raw = Files.readString(filePath, StandardCharsets.UTF_8).trim();
            if (raw.startsWith("{") && raw.endsWith("}")) {
                raw = raw.substring(1, raw.length() - 1).trim();
                if (raw.isEmpty()) return;
                for (String entry : raw.split(",")) {
                    entry = entry.trim();
                    int colon = entry.indexOf(':');
                    if (colon < 0) continue;
                    String k = entry.substring(0, colon).trim().replace("\"", "");
                    String v = entry.substring(colon + 1).trim().replace("\"", "");
                    try {
                        if (!v.isEmpty()) accountMap.put(Integer.parseInt(k), v);
                    } catch (NumberFormatException ignored) {}
                }
            }
            LOG.info("[BotAccountMemory] Zaladowano mape kont: " + accountMap);
        } catch (IOException e) {
            LOG.warning("[BotAccountMemory] Blad ladowania bot_accounts.json: " + e.getMessage());
        }
    }

    private void save() {
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Integer, String> e : accountMap.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":\"")
                  .append(e.getValue().replace("\"", "\\\"")).append('"');
            }
            sb.append('}');
            Files.writeString(filePath, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warning("[BotAccountMemory] Blad zapisu bot_accounts.json: " + e.getMessage());
        }
    }
}
