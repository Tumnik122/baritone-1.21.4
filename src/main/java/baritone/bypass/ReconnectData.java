package baritone.bypass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.player.LocalPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Reprezentacja danych sesji do pliku reconnect_data.json przy awaryjnym rozłączeniu z serwerem.
 */
public class ReconnectData {

    public String server_ip;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public List<String> ores;
    public String tunnel_direction;
    public String phase;
    public int ores_mined;
    public float health;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void save(Path filePath, LocalPlayer player, List<String> ores, String tunnelDirection, String phase, int oresMined) {
        if (player == null || filePath == null) return;
        try {
            ReconnectData data = new ReconnectData();
            try {
                net.minecraft.client.multiplayer.ServerData sData = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
                data.server_ip = sData != null ? sData.ip : "";
            } catch (Throwable ignored) {}
            data.x = player.getX();
            data.y = player.getY();
            data.z = player.getZ();
            data.yaw = player.getYRot();
            data.pitch = player.getXRot();
            data.ores = ores;
            data.tunnel_direction = tunnelDirection;
            data.phase = phase;
            data.ores_mined = oresMined;
            data.health = player.getHealth();

            File file = filePath.toFile();
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static ReconnectData load(Path filePath) {
        if (filePath == null) return null;
        try {
            File file = filePath.toFile();
            if (!file.exists()) return null;
            try (FileReader reader = new FileReader(file)) {
                return GSON.fromJson(reader, ReconnectData.class);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
