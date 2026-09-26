package baritone.bypass.scripting

import baritone.Baritone
import baritone.bypass.BypassProcess
import net.minecraft.network.chat.Component
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Silnik skryptowy Lua (Lua Scripting Engine) napisany w Kotlinie dla modułu Bypass.
 *
 * Umożliwia:
 * 1. Tworzenie i edycję skryptów automatyzacji w plikach .lua bez konieczności restartu gry.
 * 2. Hot-Reloading (#bypass script reload) w czasie rzeczywistym.
 * 3. Wywoływanie hooków:
 *    - onLoad()
 *    - onTick()
 *    - onOreBroken(oreName, totalMined)
 *    - onPlayerDetected(playerName, distance)
 *    - onDanger(reason)
 * 4. Dostęp do API bota:
 *    - bot.log(msg)
 *    - bot.chat(msg)
 *    - bot.getHealth()
 *    - bot.getState()
 *    - bot.getMined()
 *    - bot.disconnect(reason)
 *    - bot.stop()
 */
class BypassLuaEngine(
    private val baritone: Baritone,
    private val process: BypassProcess
) {
    private val scriptsDir: Path = baritone.directory.resolve("scripts")
    private val loadedScripts = mutableMapOf<String, Globals>()
    private val scriptNames = mutableSetOf<String>()

    init {
        initScriptsDirectory()
    }

    fun initScriptsDirectory() {
        try {
            if (!Files.exists(scriptsDir)) {
                Files.createDirectories(scriptsDir)
                createExampleScript()
            }
        } catch (e: Exception) {
            process.logDirect("§c[Lua] Błąd podczas tworzenia folderu scripts: ${e.message}")
        }
    }

    /**
     * Standard Lua libraries without unrestricted host access.
     *
     * `io`, `os`, `luajava`, `package`, `dofile`, and `loadfile` are removed so
     * a script cannot spawn processes or reflect arbitrary Java classes. Scripts
     * are loaded exclusively from the Baritone scripts directory by this engine.
     */
    private fun createSandboxGlobals(): Globals {
        val globals = JsePlatform.standardGlobals()
        globals.set("io", LuaValue.NIL)
        globals.set("os", LuaValue.NIL)
        globals.set("luajava", LuaValue.NIL)
        globals.set("package", LuaValue.NIL)
        globals.set("dofile", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("require", LuaValue.NIL)
        return globals
    }

    private fun createExampleScript() {
        val exampleFile = scriptsDir.resolve("auto_alert.lua")
        if (!Files.exists(exampleFile)) {
            val content = """
-- Przykładowy skrypt Lua dla modułu Bypass (Baritone)
-- Możesz edytować ten plik w notatniku i wpisać #bypass script reload bez restartu gry!

function onLoad()
    bot.log("Skrypt auto_alert.lua załadowany pomyślnie!")
end

-- Wywoływane co tick gry
function onTick()
    -- local hp = bot.getHealth()
    -- if hp < 8.0 then
    --     bot.log("UWAGA: Niski stan zdrowia: " .. hp)
    -- end
end

-- Wywoływane po wykopaniu rudy
function onOreBroken(oreName, totalMined)
    if totalMined % 10 == 0 then
        bot.log("Osiągnięto kolejny pułap: " .. totalMined .. " rud wykopanych!")
    end
end

-- Wywoływane gdy ciche ESP wykryje innego gracza
function onPlayerDetected(playerName, distance)
    bot.log("[LUA ALERT] Wykryto gracza: " .. playerName .. " w odległości " .. string.format("%.1f", distance) .. "m")
    -- Jeśli chcesz uciekać komendą na serwerze:
    -- bot.chat("/home")
end

-- Wywoływane w momencie zagrożenia
function onDanger(reason)
    bot.log("[LUA DANGER] Krytyczne zagrożenie: " .. reason)
end
""".trimIndent()
            Files.writeString(exampleFile, content)
        }
    }

    fun reloadScripts(): Int {
        loadedScripts.clear()
        scriptNames.clear()
        initScriptsDirectory()

        val files = scriptsDir.toFile().listFiles { f ->
            f.isFile && f.name.endsWith(".lua", ignoreCase = true) && f.name.none { it == '\\' || it == '/' }
        } ?: emptyArray()
        var count = 0

        for (file in files) {
            try {
                val globals = createSandboxGlobals()
                bindBotApi(globals)
                val source = Files.readString(file.toPath(), Charsets.UTF_8)
                val chunk = globals.load(source, "@${file.name}")
                if (chunk.isfunction()) {
                    chunk.call()
                } else {
                    throw IllegalStateException(chunk.tojstring())
                }

                val onLoadFunc = globals.get("onLoad")
                if (!onLoadFunc.isnil() && onLoadFunc.isfunction()) {
                    onLoadFunc.call()
                }

                val canonicalName = file.name.lowercase(Locale.ROOT)
                loadedScripts[canonicalName] = globals
                scriptNames.add(file.name)
                count++
            } catch (e: Exception) {
                process.logDirect("§c[Lua] Błąd w skrypcie ${file.name}: ${e.message}")
            }
        }

        process.logDirect("§a[Lua] Załadowano $count skrypt(ów) z folderu baritone/scripts.")
        return count
    }

    private fun bindBotApi(globals: Globals) {
        val botTable = LuaTable()

        // bot.log(text)
        botTable.set("log", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                process.logDirect("§2[Lua]§f " + arg.tojstring())
                return LuaValue.NIL
            }
        })

        // bot.chat(text)
        botTable.set("chat", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                val player = baritone.playerContext?.player()
                if (player != null && player.connection != null) {
                    if (msg.startsWith("/")) {
                        player.connection.sendCommand(msg.substring(1))
                    } else {
                        player.connection.sendChat(msg)
                    }
                }
                return LuaValue.NIL
            }
        })

        // bot.getHealth()
        botTable.set("getHealth", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val hp = baritone.playerContext?.player()?.health ?: 20f
                return LuaValue.valueOf(hp.toDouble())
            }
        })

        // bot.getState()
        botTable.set("getState", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                return LuaValue.valueOf(process.phase)
            }
        })

        // bot.getMined()
        botTable.set("getMined", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                return LuaValue.valueOf(process.oresMined)
            }
        })

        // bot.disconnect(reason)
        botTable.set("disconnect", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val reason = arg.tojstring()
                val player = baritone.playerContext?.player()
                if (player != null && player.connection != null && player.connection.connection != null) {
                    player.connection.connection.disconnect(Component.literal("§c[Bypass Lua] $reason"))
                }
                process.stop()
                return LuaValue.NIL
            }
        })

        // bot.stop()
        botTable.set("stop", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                process.stop()
                return LuaValue.NIL
            }
        })

        globals.set("bot", botTable)
    }

    fun fireTick() {
        for ((_, globals) in loadedScripts) {
            try {
                val func = globals.get("onTick")
                if (!func.isnil() && func.isfunction()) {
                    func.call()
                }
            } catch (ignored: Exception) {
            }
        }
    }

    fun fireOreBroken(oreName: String, count: Int) {
        for ((_, globals) in loadedScripts) {
            try {
                val func = globals.get("onOreBroken")
                if (!func.isnil() && func.isfunction()) {
                    func.call(LuaValue.valueOf(oreName), LuaValue.valueOf(count))
                }
            } catch (e: Exception) {
                process.logDirect("§c[Lua Error onOreBroken]: ${e.message}")
            }
        }
    }

    fun firePlayerDetected(playerName: String, distance: Double) {
        for ((_, globals) in loadedScripts) {
            try {
                val func = globals.get("onPlayerDetected")
                if (!func.isnil() && func.isfunction()) {
                    func.call(LuaValue.valueOf(playerName), LuaValue.valueOf(distance))
                }
            } catch (e: Exception) {
                process.logDirect("§c[Lua Error onPlayerDetected]: ${e.message}")
            }
        }
    }

    fun fireDanger(reason: String) {
        for ((_, globals) in loadedScripts) {
            try {
                val func = globals.get("onDanger")
                if (!func.isnil() && func.isfunction()) {
                    func.call(LuaValue.valueOf(reason))
                }
            } catch (e: Exception) {
                process.logDirect("§c[Lua Error onDanger]: ${e.message}")
            }
        }
    }

    fun executeSnippet(code: String): String {
        return try {
            val globals = createSandboxGlobals()
            bindBotApi(globals)
            val chunk = globals.load(code)
            val result = chunk.call()
            if (result.isnil()) "OK" else result.tojstring()
        } catch (e: Exception) {
            "Błąd: ${e.message}"
        }
    }

    fun getLoadedScriptNames(): List<String> = scriptNames.sorted()
}
