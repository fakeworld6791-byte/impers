package ru.saguroko.impers;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.saguroko.impers.EmpireManager.Empire;
import ru.saguroko.impers.EmpireManager.Result;

import java.util.*;
import java.util.stream.Collectors;

public class EmpireCommand implements CommandExecutor, TabCompleter {

    private final SagurokoImpers plugin;
    private final EmpireManager manager;

    public EmpireCommand(SagurokoImpers plugin, EmpireManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private boolean isAdmin(CommandSender s) {
        return s.hasPermission("saguroko.admin");
    }

    private String empireList() {
        return manager.getEmpires().stream().map(e -> e.id).collect(Collectors.joining(", "));
    }

    private Empire needEmpire(CommandSender s, String id) {
        Empire e = manager.getEmpire(id);
        if (e == null) plugin.send(s, "unknown-empire", "{list}", empireList());
        return e;
    }

    private String leaderName(Empire e) {
        return e.leader == null
                ? plugin.getConfig().getString("scoreboard.no-leader", "нет")
                : manager.displayNameOf(e.leader);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            for (String line : plugin.getConfig().getStringList("messages.help")) {
                sender.sendMessage(SagurokoImpers.color(line));
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, args);
            case "info" -> info(sender);
            case "join" -> join(sender, args);
            case "leave" -> leave(sender);
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "setleader" -> setLeader(sender, args);
            case "removeleader" -> removeLeader(sender, args);
            case "kick" -> kick(sender, args);
            case "reload" -> {
                if (!isAdmin(sender)) {
                    plugin.send(sender, "no-permission");
                    return true;
                }
                plugin.reloadAll();
                plugin.send(sender, "reloaded");
            }
            default -> plugin.send(sender, "unknown-empire", "{list}", empireList());
        }
        return true;
    }

    // ---------- подкоманды ----------

    private void list(CommandSender s, String[] args) {
        if (args.length >= 2) {
            Empire e = needEmpire(s, args[1]);
            if (e == null) return;
            plugin.sendRaw(s, "members-header", "{color}", e.color.toString(), "{name}", e.name,
                    "{count}", String.valueOf(e.members.size()), "{max}", String.valueOf(manager.maxOf(e)));
            for (UUID u : e.members) {
                plugin.sendRaw(s, "member-line", "{player}", manager.displayNameOf(u),
                        "{leader-mark}", manager.isLeader(e, u) ? plugin.msg("leader-mark") : "");
            }
            return;
        }
        plugin.sendRaw(s, "list-header");
        for (Empire e : manager.getEmpires()) {
            plugin.sendRaw(s, "list-line", "{color}", e.color.toString(), "{name}", e.name,
                    "{count}", String.valueOf(e.members.size()), "{max}", String.valueOf(manager.maxOf(e)),
                    "{leader}", leaderName(e));
        }
    }

    private void info(CommandSender s) {
        if (!(s instanceof Player p)) {
            plugin.send(s, "player-only");
            return;
        }
        Empire e = manager.getEmpireOf(p.getUniqueId());
        if (e == null) {
            plugin.send(s, "not-in-empire");
            return;
        }
        plugin.send(s, "info", "{empire}", e.colored(), "{leader}", leaderName(e),
                "{count}", String.valueOf(e.members.size()), "{max}", String.valueOf(manager.maxOf(e)));
    }

    private void join(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            plugin.send(s, "player-only");
            return;
        }
        if (!s.hasPermission("saguroko.join")) {
            plugin.send(s, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(s, "unknown-empire", "{list}", empireList());
            return;
        }
        Empire e = needEmpire(s, args[1]);
        if (e == null) return;

        Result r = manager.addMember(e, p.getUniqueId());
        switch (r) {
            case OK -> {
                manager.applyPlayer(p);
                plugin.send(s, "joined", "{empire}", e.colored());
            }
            case FULL -> plugin.send(s, "empire-full", "{empire}", e.colored(), "{max}", String.valueOf(manager.maxOf(e)));
            default -> plugin.send(s, "you-already-in-empire");
        }
    }

    private void leave(CommandSender s) {
        if (!(s instanceof Player p)) {
            plugin.send(s, "player-only");
            return;
        }
        Empire e = manager.getEmpireOf(p.getUniqueId());
        if (e == null) {
            plugin.send(s, "not-in-empire");
            return;
        }
        manager.removeMember(p.getUniqueId());
        manager.applyPlayer(p);
        plugin.send(s, "left", "{empire}", e.colored());
    }

    private void add(CommandSender s, String[] args) {
        if (args.length < 2) {
            plugin.sendRaw(s, "help");
            return;
        }
        Empire e;
        if (args.length >= 3 && isAdmin(s)) {
            // /empire add <игрок> <империя> - для операторов
            e = needEmpire(s, args[2]);
            if (e == null) return;
        } else {
            if (!(s instanceof Player p)) {
                plugin.send(s, "player-only");
                return;
            }
            e = manager.getEmpireOf(p.getUniqueId());
            if (e == null || !manager.isLeader(e, p.getUniqueId())) {
                plugin.send(s, "not-leader");
                return;
            }
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.send(s, "player-not-found", "{player}", args[1]);
            return;
        }
        Result r = manager.addMember(e, target.getUniqueId());
        switch (r) {
            case OK -> {
                manager.applyPlayer(target);
                plugin.send(s, "added", "{player}", target.getName(), "{empire}", e.colored());
                plugin.send(target, "added-target", "{empire}", e.colored());
            }
            case FULL -> plugin.send(s, "empire-full", "{empire}", e.colored(), "{max}", String.valueOf(manager.maxOf(e)));
            default -> plugin.send(s, "already-in-empire", "{player}", target.getName());
        }
    }

    private void remove(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            plugin.send(s, "player-only");
            return;
        }
        if (args.length < 2) {
            plugin.sendRaw(s, "help");
            return;
        }
        Empire e = manager.getEmpireOf(p.getUniqueId());
        if (e == null || !manager.isLeader(e, p.getUniqueId())) {
            plugin.send(s, "not-leader");
            return;
        }
        UUID target = manager.findMember(e, args[1]);
        if (target == null) {
            plugin.send(s, "not-member", "{player}", args[1]);
            return;
        }
        if (target.equals(p.getUniqueId())) {
            plugin.send(s, "cant-remove-self");
            return;
        }
        doRemove(s, e, target);
    }

    private void kick(CommandSender s, String[] args) {
        if (!isAdmin(s)) {
            plugin.send(s, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendRaw(s, "help");
            return;
        }
        for (Empire e : manager.getEmpires()) {
            UUID target = manager.findMember(e, args[1]);
            if (target != null) {
                doRemove(s, e, target);
                return;
            }
        }
        plugin.send(s, "player-not-found", "{player}", args[1]);
    }

    private void doRemove(CommandSender s, Empire e, UUID target) {
        String name = manager.displayNameOf(target);
        manager.removeMember(target);
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            manager.applyPlayer(online);
            plugin.send(online, "removed-target", "{empire}", e.colored());
        }
        plugin.send(s, "removed", "{player}", name, "{empire}", e.colored());
    }

    private void setLeader(CommandSender s, String[] args) {
        if (!isAdmin(s)) {
            plugin.send(s, "no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.sendRaw(s, "help");
            return;
        }
        Empire e = needEmpire(s, args[1]);
        if (e == null) return;
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            plugin.send(s, "player-not-found", "{player}", args[2]);
            return;
        }
        Result r = manager.setLeader(e, target.getUniqueId());
        switch (r) {
            case OK -> {
                manager.applyPlayer(target);
                plugin.send(s, "leader-set", "{player}", target.getName(), "{empire}", e.colored());
                plugin.send(target, "leader-set-target", "{empire}", e.colored());
            }
            case FULL -> plugin.send(s, "empire-full", "{empire}", e.colored(), "{max}", String.valueOf(manager.maxOf(e)));
            default -> plugin.send(s, "in-other-empire", "{player}", target.getName());
        }
    }

    private void removeLeader(CommandSender s, String[] args) {
        if (!isAdmin(s)) {
            plugin.send(s, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendRaw(s, "help");
            return;
        }
        Empire e = needEmpire(s, args[1]);
        if (e == null) return;
        manager.clearLeader(e);
        plugin.send(s, "leader-removed", "{empire}", e.colored());
    }

    // ---------- автодополнение ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("help", "list", "info", "leave", "add", "remove"));
            if (sender.hasPermission("saguroko.join")) out.add("join");
            if (isAdmin(sender)) out.addAll(List.of("setleader", "removeleader", "kick", "reload"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list", "join", "setleader", "removeleader" ->
                        manager.getEmpires().forEach(e -> out.add(e.id));
                case "add" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "remove", "kick" -> manager.getEmpires().forEach(e ->
                        e.members.forEach(u -> out.add(manager.displayNameOf(u))));
                default -> {
                }
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("setleader")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            if (sub.equals("add") && isAdmin(sender)) manager.getEmpires().forEach(e -> out.add(e.id));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
