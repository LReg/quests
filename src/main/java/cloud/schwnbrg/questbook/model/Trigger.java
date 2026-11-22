package cloud.schwnbrg.questbook.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

public sealed interface Trigger permits Trigger.Continue, Trigger.Reach, Trigger.Region, Trigger.SignClick,
        Trigger.Interact, Trigger.Answer, Trigger.Dimension, Trigger.AdvancementDone, Trigger.Kill, Trigger.Choice, Trigger.FirstOf {

    String type();

    record Continue() implements Trigger {
        public String type() { return "CONTINUE"; }
    }

    /** Sphere around (x, y, z). */
    record Reach(String world, double x, double y, double z, int radius) implements Trigger {
        public String type() { return "REACH"; }

        public boolean contains(String w, double px, double py, double pz) {
            if (!world.equals(w)) return false;
            double dx = px - x, dy = py - y, dz = pz - z;
            return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
        }
    }

    /** Box between two ground corners with a height. */
    record Region(String world, int x1, int z1, int x2, int z2, int baseY, int height) implements Trigger {
        public String type() { return "REGION"; }

        public boolean contains(String w, int bx, int by, int bz) {
            return world.equals(w)
                    && bx >= Math.min(x1, x2) && bx <= Math.max(x1, x2)
                    && bz >= Math.min(z1, z2) && bz <= Math.max(z1, z2)
                    && by >= baseY && by < baseY + height;
        }
    }

    record SignClick(BlockRef sign) implements Trigger {
        public String type() { return "SIGN"; }
    }

    record Interact(BlockRef block) implements Trigger {
        public String type() { return "INTERACT"; }
    }

    record Answer(List<String> answers) implements Trigger {
        public String type() { return "ANSWER"; }
    }

    record Dimension(World.Environment env) implements Trigger {
        public String type() { return "DIMENSION"; }
    }

    record AdvancementDone(NamespacedKey key) implements Trigger {
        public String type() { return "ADVANCEMENT"; }
    }

    /**
     * Kill {@code amount} entities. {@code target} is a category (ANY, ANIMAL, MONSTER, PLAYER) or an entity type name
     * such as ZOMBIE. The running count is kept in memory per run, not in the database.
     */
    record Kill(String target, int amount) implements Trigger {
        public static final List<String> CATEGORIES = List.of("ANY", "ANIMAL", "MONSTER", "PLAYER");

        public String type() { return "KILL"; }

        /** A category, or the name of a living entity type. */
        public static boolean validTarget(String t) {
            if (t == null) return false;
            if (CATEGORIES.contains(t)) return true;
            try {
                return org.bukkit.entity.EntityType.valueOf(t).isAlive();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        /** True if a killed entity with these properties counts. {@code typeName} is the EntityType name. */
        public boolean matches(String typeName, boolean animal, boolean enemy, boolean player) {
            return switch (target) {
                case "ANY" -> true;
                case "ANIMAL" -> animal;
                case "MONSTER" -> enemy;
                case "PLAYER" -> player;
                default -> target.equals(typeName);
            };
        }
    }

    /** A button of a CHOICE step. {@code id} is hidden and stable ("o2"), so renaming the label keeps conditions working. */
    record Option(String id, String label) {}

    /** Buttons the player clicks (2..4). The click is remembered for conditions. */
    record Choice(List<Option> options) implements Trigger {
        public Choice {
            int max = 0;
            for (Option o : options) max = Math.max(max, optionNumber(o.id()));
            Set<String> used = new HashSet<>();
            List<Option> out = new ArrayList<>();
            for (Option o : options) {
                String id = o.id();
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || !used.add(id)) {
                    id = "o" + (++max);
                    used.add(id);
                }
                out.add(new Option(id, o.label() == null ? "" : o.label()));
            }
            options = List.copyOf(out);
        }

        public String type() { return "CHOICE"; }

        public Option option(String id) {
            for (Option o : options) if (o.id().equals(id)) return o;
            return null;
        }
    }

    /** One outcome of a WORLD CHOICE: whichever trigger happens first. */
    record Branch(String id, String label, Trigger trigger) {}

    /** Waits for up to 3 triggers at once; the first one that happens finishes the step and counts as the pick. */
    record FirstOf(List<Branch> options) implements Trigger {
        public FirstOf {
            int max = 0;
            for (Branch o : options) max = Math.max(max, optionNumber(o.id()));
            Set<String> used = new HashSet<>();
            List<Branch> out = new ArrayList<>();
            for (Branch o : options) {
                String id = o.id();
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || !used.add(id)) {
                    id = "o" + (++max);
                    used.add(id);
                }
                out.add(new Branch(id, o.label() == null ? "" : o.label(), o.trigger() == null ? new Continue() : o.trigger()));
            }
            options = List.copyOf(out);
        }

        public String type() { return "FIRST_OF"; }

        public Branch option(String id) {
            for (Branch o : options) if (o.id().equals(id)) return o;
            return null;
        }
    }

    /** Trigger types a WORLD CHOICE option may use: things that happen in the world, no buttons and no nesting. */
    List<String> WORLD_TYPES = List.of("REACH", "REGION", "SIGN", "INTERACT", "DIMENSION", "ADVANCEMENT", "KILL");

    List<String> TYPES = List.of("CONTINUE", "REACH", "REGION", "SIGN", "INTERACT", "ANSWER", "DIMENSION", "ADVANCEMENT", "KILL",
            "CHOICE", "FIRST_OF");

    /** The triggers that actually wait for something: the trigger itself, or the options of a WORLD CHOICE. */
    static List<Trigger> leaves(Trigger t) {
        if (t instanceof FirstOf f) return f.options().stream().map(Branch::trigger).toList();
        return List.of(t);
    }

    /** 2 for "o2", 0 for anything else. */
    static int optionNumber(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'o') return 0;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A fresh trigger of the given type with neutral defaults, for the editor. */
    static Trigger blank(String type) {
        return switch (type.toUpperCase()) {
            case "REACH" -> new Reach("world", 0, 64, 0, 5);
            case "REGION" -> new Region("world", 0, 0, 0, 0, 64, 4);
            case "SIGN" -> new SignClick(null);
            case "INTERACT" -> new Interact(null);
            case "ANSWER" -> new Answer(List.of());
            case "DIMENSION" -> new Dimension(World.Environment.NETHER);
            case "ADVANCEMENT" -> new AdvancementDone(NamespacedKey.minecraft("story/root"));
            case "KILL" -> new Kill("ANY", 5);
            case "CHOICE" -> new Choice(List.of(new Option(null, "Yes"), new Option(null, "No")));
            case "FIRST_OF" -> new FirstOf(List.of());
            default -> new Continue();
        };
    }

    /** Normalizes an answer for comparison: lowercase, trimmed, collapsed whitespace, no .,!?'" */
    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[.,!?'\"]", "").trim().replaceAll("\\s+", " ");
    }
}
