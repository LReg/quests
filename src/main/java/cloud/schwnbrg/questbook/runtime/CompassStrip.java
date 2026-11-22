package cloud.schwnbrg.questbook.runtime;

/**
 * The text of the compass bar: cardinal letters, ticks and the quest marker as seen from where the player looks, in
 * MiniMessage. No Bukkit in here, so it can be tested on its own.
 *
 * <p>The marker is ◆ when the target is in view, « or » at the edge when it is behind the player. When the target is
 * more than {@link #HEIGHT} blocks above or below the player's feet, ▲ or ▼ is drawn right next to the marker (on its
 * inner side at the right edge), in place of one strip character so the strip keeps its width.
 */
final class CompassStrip {

    /** Characters on each side of the centre; the strip covers 180° of view. */
    static final int HALF = 22;
    static final double DEG_PER_CHAR = 90.0 / HALF;
    /** Height difference (blocks) above which the up/down arrow shows. */
    static final double HEIGHT = 5;

    private CompassStrip() { }

    /**
     * @param yaw the player's yaw
     * @param dx  target x minus player x
     * @param dz  target z minus player z
     * @param dy  target y minus the player's feet
     */
    static String build(double yaw, double dx, double dz, double dy) {
        int marker = (int) Math.round(wrap(bearing(dx, dz) - yaw) / DEG_PER_CHAR);
        int at = Math.clamp(marker, -HALF, HALF);
        String arrow = arrow(dy);
        int arrowAt = arrow == null ? Integer.MIN_VALUE : at < HALF ? at + 1 : at - 1;
        StringBuilder sb = new StringBuilder();
        for (int k = -HALF; k <= HALF; k++) {
            if (k == at) {
                sb.append(marker < -HALF ? "<gold>«</gold>" : marker > HALF ? "<gold>»</gold>" : "<gold><bold>◆</bold></gold>");
            } else if (k == arrowAt) {
                sb.append("<gold><bold>").append(arrow).append("</bold></gold>");
            } else {
                double a = wrap(yaw + k * DEG_PER_CHAR);
                String letter = cardinal(a);
                if (letter != null) sb.append("<white><bold>").append(letter).append("</bold></white>");
                else sb.append(Math.floorMod(Math.round(a), 45) < DEG_PER_CHAR ? "<gray>|</gray>" : "<dark_gray>·</dark_gray>");
            }
        }
        return sb.toString();
    }

    /** ▲ if the target is more than {@link #HEIGHT} blocks higher, ▼ if that much lower, else null. */
    static String arrow(double dy) {
        return dy > HEIGHT ? "▲" : dy < -HEIGHT ? "▼" : null;
    }

    /** Minecraft yaw from the player towards a point: 0 = south, 90 = west, 180 = north, 270 = east. */
    static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** N/E/S/W when this angle is the closest character to one of them. */
    private static String cardinal(double a) {
        String[] letters = {"S", "W", "N", "E"};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(wrap(a - i * 90)) < DEG_PER_CHAR / 2) return letters[i];
        }
        return null;
    }

    /** Into -180..180. */
    static double wrap(double a) {
        a = a % 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
