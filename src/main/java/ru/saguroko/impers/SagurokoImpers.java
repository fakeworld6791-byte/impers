package ru.saguroko.impers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class SagurokoImpers extends JavaPlugin implements Listener {

    private EmpireManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new EmpireManager(this);
        manager.load();

        EmpireCommand cmd = new EmpireCommand(this, manager);
        getCommand("empire").setExecutor(cmd);
        getCommand("empire").setTabCompleter(cmd);
        getServer().getPluginManager().registerEvents(this, this);

        for (Player p : Bukkit.getOnlinePlayers()) {
            manager.applyPlayer(p);
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.save();
    }

    public void reloadAll() {
        manager.save();
        reloadConfig();
        manager.load();
        for (Player p : Bukkit.getOnlinePlayers()) {
            manager.applyPlayer(p);
        }
    }

    // ---------- сообщения ----------

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String msg(String key, String... pairs) {
        String s = getConfig().getString("messages." + key, "&cНет сообщения: " + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace(pairs[i], pairs[i + 1]);
        }
        return color(s);
    }

    public void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(color(getConfig().getString("messages.prefix", "")) + msg(key, pairs));
    }

    public void sendRaw(CommandSender to, String key, String... pairs) {
        to.sendMessage(msg(key, pairs));
    }

    // ---------- события ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        manager.applyPlayer(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        EmpireManager.Empire emp = manager.getEmpireOf(e.getPlayer().getUniqueId());
        String prefix = emp == null ? "" : manager.prefixOf(emp);
        String fmt = getConfig().getString("chat.format", "{prefix}{player}&r: {message}");
        fmt = color(fmt)
                .replace("{prefix}", prefix.replace("%", "%%"))
                .replace("{player}", "%1$s")
                .replace("{message}", "%2$s");
        e.setFormat(fmt);
    }
}
