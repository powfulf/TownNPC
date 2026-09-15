package id.gaffa.townnpc;

import id.gaffa.townnpc.command.TownNpcCommand;
import id.gaffa.townnpc.npc.NpcManager;
import id.gaffa.townnpc.skin.SkinService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TownNpcPlugin extends JavaPlugin {
    private Settings settings;
    private NpcManager npcs;
    private SkinService skins;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        npcs = new NpcManager(this, settings);
        skins = new SkinService(this, settings.skinTimeoutSeconds(), settings.skinCacheMillis());
        npcs.load();

        for (Player player : Bukkit.getOnlinePlayers()) {
            npcs.onJoin(player);
        }

        Bukkit.getPluginManager().registerEvents(new PlayerListener(npcs), this);
        PluginCommand command = getCommand("townnpc");
        if (command != null) {
            TownNpcCommand executor = new TownNpcCommand(this, npcs);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        Bukkit.getScheduler().runTaskTimer(this, npcs::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (npcs != null) {
            npcs.despawnAll();
            npcs.save();
        }
    }

    public void reload() {
        npcs.save();
        reloadConfig();
        settings = Settings.load(getConfig());
        npcs.applySettings(settings);
        skins = new SkinService(this, settings.skinTimeoutSeconds(), settings.skinCacheMillis());
        npcs.load();
    }

    public NpcManager npcs() {
        return npcs;
    }

    public SkinService skins() {
        return skins;
    }

    public Settings settings() {
        return settings;
    }
}
