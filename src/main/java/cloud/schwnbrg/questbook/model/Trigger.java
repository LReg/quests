package cloud.schwnbrg.questbook.model;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

public sealed interface Trigger permits Trigger.Continue, Trigger.Reach, Trigger.Region, Trigger.SignClick,
        Trigger.Interact, Trigger.Answer, Trigger.Dimension, Trigger.AdvancementDone, Trigger.Kill {

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

    List<String> TYPES = List.of("CONTINUE", "REACH", "REGION", "SIGN", "INTERACT", "ANSWER", "DIMENSION", "ADVANCEMENT", "KILL");

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
            default -> new Continue();
        };
    }

    /** Normalizes an answer for comparison: lowercase, trimmed, collapsed whitespace, no .,!?'" */
    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[.,!?'\"]", "").trim().replaceAll("\\s+", " ");
    }
}
