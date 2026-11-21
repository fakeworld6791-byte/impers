package ru.saguroko.impers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

@SuppressWarnings("deprecation")
public class EmpireManager {

    public static class Empire {
        public final String id;
        public final String name;
        public final ChatColor color;
        public UUID leader;
        public final Set<UUID> members = new LinkedHashSet<>();

        public Empire(String id, String name, ChatColor color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        /** Цветное название империи, например "§cКрасные" */
        public String colored() {
            return color + name;
        }
    }

    public enum Result { OK, FULL, IN_OTHER, ALREADY }

    private final SagurokoImpers plugin;
    private final Map<String, Empire> empires = new LinkedHashMap<>();
    private final Map<UUID, String> byPlayer = new HashMap<>();
    private final Map<String, Team> teams = new HashMap<>();
    private final Set<String> usedEntries = new HashSet<>();
    private Scoreboard board;
    private Objective objective;
    private File dataFile;

    public EmpireManager(SagurokoImpers plugin) {
        this.plugin = plugin;
    }

    // ---------- загрузка / сохранение ----------

    public void load() {
        empires.clear();
        byPlayer.clear();

        dataFile = new File(plugin.getDataFolder(), "data.yml");
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("empires");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;

                ChatColor color;
                try {
                    color = ChatColor.valueOf(s.getString("color", "WHITE").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    color = ChatColor.WHITE;
                }
                Empire e = new Empire(id.toLowerCase(Locale.ROOT), s.getString("name", id), color);

                String base = "empires." + e.id;
                for (String m : data.getStringList(base + ".members")) {
                    try {
                        UUID u = UUID.fromString(m);
                        if (!byPlayer.containsKey(u)) {
                            e.members.add(u);
                            byPlayer.put(u, e.id);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                String l = data.getString(base + ".leader");
                if (l != null) {
                    try {
                        UUID lu = UUID.fromString(l);
                        if (e.members.contains(lu)) e.leader = lu;
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                empires.put(e.id, e);
            }
        }
        rebuildBoard();
    }

    public void save() {
        if (dataFile == null) return;
        YamlConfiguration data = new YamlConfiguration();
        for (Empire e : empires.values()) {
            String base = "empires." + e.id;
            data.set(base + ".leader", e.leader == null ? null : e.leader.toString());
            List<String> list = new ArrayList<>();
            for (UUID u : e.members) list.add(u.toString());
            data.set(base + ".members", list);
        }
        try {
            plugin.getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить data.yml: " + ex.getMessage());
        }
    }

    // ---------- доступ ----------

    public Collection<Empire> getEmpires() {
        return empires.values();
    }

    public Empire getEmpire(String id) {
        return id == null ? null : empires.get(id.toLowerCase(Locale.ROOT));
    }

    public Empire getEmpireOf(UUID u) {
        String id = byPlayer.get(u);
        return id == null ? null : empires.get(id);
    }

    public int maxOf(Empire e) {
        int global = plugin.getConfig().getInt("max-members", 10);
        return plugin.getConfig().getInt("empires." + e.id + ".max-members", global);
    }

    public boolean isLeader(Empire e, UUID u) {
        return e != null && u.equals(e.leader);
    }

    public String nameOf(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) return p.getName();
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName();
    }

    public String displayNameOf(UUID u) {
        String n = nameOf(u);
        return n == null ? u.toString().substring(0, 8) : n;
    }

    public String prefixOf(Empire e) {
        String fmt = plugin.getConfig().getString("nick-prefix", "[{name}] ");
        return e.color + SagurokoImpers.color(fmt.replace("{name}", e.name));
    }

    /** Ищет участника империи по нику (в том числе офлайн). */
    public UUID findMember(Empire e, String name) {
        for (UUID u : e.members) {
            String n = nameOf(u);
            if (n != null && n.equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    // ---------- изменение состава ----------

    public Result addMember(Empire e, UUID u) {
        String cur = byPlayer.get(u);
        if (cur != null) return cur.equals(e.id) ? Result.ALREADY : Result.IN_OTHER;
        if (e.members.size() >= maxOf(e)) return Result.FULL;

        e.members.add(u);
        byPlayer.put(u, e.id);

        Team t = teams.get(e.id);
        String n = nameOf(u);
        if (t != null && n != null) t.addEntry(n);

        save();
        updateBoard();
        return Result.OK;
    }

    public boolean removeMember(UUID u) {
        Empire e = getEmpireOf(u);
        if (e == null) return false;

        e.members.remove(u);
        byPlayer.remove(u);
        if (u.equals(e.leader)) e.leader = null;

        Team t = teams.get(e.id);
        String n = nameOf(u);
        if (t != null && n != null) t.removeEntry(n);

        save();
        updateBoard();
        return true;
    }

    public Result setLeader(Empire e, UUID u) {
        String cur = byPlayer.get(u);
        if (cur != null && !cur.equals(e.id)) return Result.IN_OTHER;
        if (cur == null) {
            Result r = addMember(e, u);
            if (r != Result.OK) return r;
        }
        e.leader = u;
        save();
        updateBoard();
        return Result.OK;
    }

    public void clearLeader(Empire e) {
        e.leader = null;
        save();
        updateBoard();
    }

    // ---------- скорборд и команды (цветные ники) ----------

    private String title() {
        return SagurokoImpers.color(plugin.getConfig().getString("scoreboard.title", "&6&lSaguroko Impers"));
    }

    private void rebuildBoard() {
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
        }
        for (Team t : new ArrayList<>(board.getTeams())) t.unregister();
        teams.clear();
        if (objective != null) {
            try {
                objective.unregister();
            } catch (IllegalStateException ignored) {
            }
        }
        usedEntries.clear();
        objective = board.registerNewObjective("impers", "dummy", title());

        int i = 0;
        for (Empire e : empires.values()) {
            Team t = board.registerNewTeam("imp" + i++);
            if (e.color.isColor()) t.setColor(e.color);
            t.setPrefix(prefixOf(e));
            for (UUID u : e.members) {
                String n = nameOf(u);
                if (n != null) t.addEntry(n);
            }
            teams.put(e.id, t);
        }
        updateBoard();
    }

    public void updateBoard() {
        FileConfiguration cfg = plugin.getConfig();
        for (String s : usedEntries) board.resetScores(s);
        usedEntries.clear();

        if (!cfg.getBoolean("scoreboard.enabled", true)) {
            objective.setDisplaySlot(null);
            return;
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setDisplayName(title());

        boolean showLeader = cfg.getBoolean("scoreboard.show-leader", true);
        String empireLine = cfg.getString("scoreboard.empire-line", "{color}{name} &7({count}/{max})");
        String leaderLine = cfg.getString("scoreboard.leader-line", " &7Лидер: &f{leader}");
        String noLeader = cfg.getString("scoreboard.no-leader", "нет");

        List<String> lines = new ArrayList<>();
        lines.add("");
        for (Empire e : empires.values()) {
            lines.add(SagurokoImpers.color(empireLine
                    .replace("{color}", e.color.toString())
                    .replace("{name}", e.name)
                    .replace("{count}", String.valueOf(e.members.size()))
                    .replace("{max}", String.valueOf(maxOf(e)))));
            if (showLeader) {
                String leader = e.leader == null ? noLeader : displayNameOf(e.leader);
                lines.add(SagurokoImpers.color(leaderLine.replace("{leader}", leader)));
            }
            lines.add("");
        }

        int score = lines.size();
        int idx = 0;
        for (String line : lines) {
            // каждая строка скорборда должна быть уникальной
            String entry = line + ChatColor.RESET.toString().repeat(idx + 1);
            while (usedEntries.contains(entry)) entry += ChatColor.RESET;
            usedEntries.add(entry);
            objective.getScore(entry).setScore(score--);
            idx++;
        }
    }

    public void applyPlayer(Player p) {
        p.setScoreboard(board);
        Team cur = board.getEntryTeam(p.getName());
        String id = byPlayer.get(p.getUniqueId());
        Team want = id == null ? null : teams.get(id);
        if (cur != want) {
            if (cur != null) cur.removeEntry(p.getName());
            if (want != null) want.addEntry(p.getName());
        }
    }
}
