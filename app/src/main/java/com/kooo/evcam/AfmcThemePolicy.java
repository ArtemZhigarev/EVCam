package com.kooo.evcam;

import java.util.HashMap;
import java.util.Map;

/**
 * AppsForMyCar: the rules of {@link AfmcTheme}, kept free of Android so they can be unit-tested.
 * EVCam matches the look picked on LocalStore's Themes card: "glass" draws it in the Glass look,
 * anything else (no LocalStore, an older one, "classic", an unknown look) leaves EVCam as upstream drew it.
 */
public final class AfmcThemePolicy {
    public static final String CLASSIC = "classic";
    public static final String GLASS = "glass";

    /** Corners in Glass: upstream's radii times this (LocalStore's Glass uses the same). */
    public static final float RADIUS_SCALE = 1.5f;
    /** Plain card-coloured panels (no corners upstream) get this radius in Glass, in dp. */
    public static final int CARD_RADIUS_DP = 18;

    private AfmcThemePolicy() {}

    /** LocalStore's answer, as a look id. */
    public static String parse(String answer) {
        return answer != null && GLASS.equals(answer.trim()) ? GLASS : CLASSIC;
    }

    public static boolean isGlass(String answer) {
        return GLASS.equals(parse(answer));
    }

    public static float scaleRadius(float radius) {
        return radius <= 0f ? radius : radius * RADIUS_SCALE;
    }

    /**
     * Upstream colour → Glass colour, from pairs {upstream, glass}. Two upstream colours can be the same
     * value (card and button backgrounds are); the first pair for a value wins, so the map stays
     * well defined. A pair whose Glass colour is itself an upstream colour is left out: styling a
     * screen twice must not change it again.
     */
    public static Map<Integer, Integer> colourMap(int[][] pairs) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int[] p : pairs) if (!map.containsKey(p[0])) map.put(p[0], p[1]);
        Map<Integer, Integer> safe = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (!map.containsKey(e.getValue()) || e.getValue().equals(e.getKey())) safe.put(e.getKey(), e.getValue());
        }
        return safe;
    }
}
