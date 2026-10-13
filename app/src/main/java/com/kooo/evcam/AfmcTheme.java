package com.kooo.evcam;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.Map;

/**
 * AppsForMyCar: EVCam drawn in the look picked on LocalStore's Themes card (docs/PLAN-themes.md).
 *
 * Each time one of EVCam's screens opens it asks LocalStore (content://org.ex2.localstore.theme, our
 * key only). For "glass" it restyles the screen after upstream has built it, without touching upstream's
 * layouts: its colours become the Glass ones (by value, day or night as the car is), corners rounder,
 * text Inter, the accent blue (a theme overlay, so switches and progress bars follow). Fragments are
 * restyled as their views are made, list rows as they are shown. For anything else nothing happens and
 * EVCam looks as upstream drew it. A screen already open keeps its look until it is opened again: EVCam
 * is never restarted for it (a recording may be running). Android 10+ only (activity pre-create hook).
 * Logcat tag {@code AfmcTheme}.
 */
public final class AfmcTheme {
    private static final String TAG = "AfmcTheme";
    private static final Uri LOCALSTORE = Uri.parse("content://org.ex2.localstore.theme");

    private AfmcTheme() {}

    /** The look LocalStore is drawn in; classic when LocalStore can't be asked. */
    static String askLocalStore(Context context) {
        try (Cursor c = context.getContentResolver().query(LOCALSTORE, null, null, null, null)) {
            return AfmcThemePolicy.parse(c != null && c.moveToFirst() ? c.getString(0) : null);
        } catch (Throwable t) {
            // No LocalStore, one without themes, or not signed with our key.
            return AfmcThemePolicy.CLASSIC;
        }
    }

    /** Started by {@link Init} once per EVCam process. */
    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Callbacks());
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        private final Map<Activity, Styler> styled = new java.util.WeakHashMap<>();

        @Override public void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle saved) {
            try {
                if (!AfmcThemePolicy.isGlass(askLocalStore(activity))) return;
                // Before upstream's onCreate inflates anything: the accent for switches, buttons and bars.
                activity.getTheme().applyStyle(R.style.AfmcGlassOverlay, true);
                Styler s = new Styler(activity);
                styled.put(activity, s);
                if (activity instanceof FragmentActivity) {
                    ((FragmentActivity) activity).getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                        new FragmentManager.FragmentLifecycleCallbacks() {
                            @Override public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, @Nullable Bundle b) {
                                s.style(v);
                            }
                        }, true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "glass not applied to " + activity.getClass().getSimpleName() + ": " + t);
            }
        }

        @Override public void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle saved) {
            Styler s = styled.get(activity);
            if (s != null) s.style(activity.findViewById(android.R.id.content));
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) { styled.remove(activity); }
        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle saved) {}
        @Override public void onActivityStarted(@NonNull Activity activity) {}
        @Override public void onActivityResumed(@NonNull Activity activity) {}
        @Override public void onActivityPaused(@NonNull Activity activity) {}
        @Override public void onActivityStopped(@NonNull Activity activity) {}
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle out) {}
    }

    /** Restyles one screen's views in place. Every step is guarded: a view it can't restyle is left as it was. */
    static final class Styler {
        private final Map<Integer, Integer> colours;
        private final int cardColour;
        private final float cardRadius;
        private final Typeface regular, strong;

        Styler(Context c) {
            colours = AfmcThemePolicy.colourMap(new int[][] {
                pair(c, R.color.page_background, R.color.afmc_glass_bg),
                pair(c, R.color.menu_background, R.color.afmc_glass_bg),
                pair(c, R.color.nav_header_background, R.color.afmc_glass_bg),
                pair(c, R.color.card_background, R.color.afmc_glass_surface),
                pair(c, R.color.button_background, R.color.afmc_glass_surface),
                pair(c, R.color.nav_background, R.color.afmc_glass_surface),
                pair(c, R.color.input_background, R.color.afmc_glass_fill),
                pair(c, R.color.text_primary, R.color.afmc_glass_text),
                pair(c, R.color.button_text, R.color.afmc_glass_text),
                pair(c, R.color.nav_text_primary, R.color.afmc_glass_text),
                pair(c, R.color.text_secondary, R.color.afmc_glass_muted),
                pair(c, R.color.nav_text_secondary, R.color.afmc_glass_muted),
                pair(c, R.color.button_accent, R.color.afmc_glass_accent),
                pair(c, R.color.colorPrimary, R.color.afmc_glass_accent),
            });
            cardColour = ContextCompat.getColor(c, R.color.card_background);
            cardRadius = AfmcThemePolicy.CARD_RADIUS_DP * c.getResources().getDisplayMetrics().density;
            regular = ResourcesCompat.getFont(c, R.font.afmc_inter_regular);
            strong = ResourcesCompat.getFont(c, R.font.afmc_inter_semibold);
        }

        private static int[] pair(Context c, int upstream, int glass) {
            return new int[] { ContextCompat.getColor(c, upstream), ContextCompat.getColor(c, glass) };
        }

        void style(@Nullable View v) {
            if (v == null) return;
            // Corners are scaled once per view (a list row is styled again each time it is reused).
            boolean first = v.getTag(R.id.afmc_theme_styled) == null;
            v.setTag(R.id.afmc_theme_styled, Boolean.TRUE);
            try { styleOne(v, first); } catch (Throwable t) { Log.w(TAG, "view not restyled: " + t); }
            if (first && v instanceof RecyclerView) {
                // List rows are made later and reused: restyle each one as it is shown.
                ((RecyclerView) v).addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override public void onChildViewAttachedToWindow(@NonNull View child) { style(child); }
                    @Override public void onChildViewDetachedFromWindow(@NonNull View child) {}
                });
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) style(g.getChildAt(i));
            }
        }

        private void styleOne(View v, boolean first) {

            Drawable bg = v.getBackground();
            if (bg instanceof ColorDrawable) {
                int from = ((ColorDrawable) bg).getColor();
                Integer to = colours.get(from);
                if (to != null) {
                    if (from == cardColour) {
                        GradientDrawable card = new GradientDrawable();
                        card.setColor(to); card.setCornerRadius(cardRadius);
                        v.setBackground(card);
                    } else v.setBackground(new ColorDrawable(to));
                }
            } else if (bg instanceof GradientDrawable) {
                GradientDrawable g = (GradientDrawable) bg;
                ColorStateList fill = g.getColor();
                Integer to = fill == null ? null : colours.get(fill.getDefaultColor());
                if (to != null || first) {
                    g = (GradientDrawable) g.mutate();
                    if (to != null) g.setColor(to);
                    if (first) g.setCornerRadius(AfmcThemePolicy.scaleRadius(g.getCornerRadius()));
                }
            }

            if (v instanceof MaterialButton) {
                MaterialButton b = (MaterialButton) v;
                ColorStateList tint = b.getBackgroundTintList();
                Integer to = tint == null ? null : colours.get(tint.getDefaultColor());
                if (to != null) b.setBackgroundTintList(ColorStateList.valueOf(to));
                if (first) b.setCornerRadius(Math.round(AfmcThemePolicy.scaleRadius(b.getCornerRadius())));
            } else if (v instanceof CardView) {
                CardView card = (CardView) v;
                Integer to = colours.get(card.getCardBackgroundColor().getDefaultColor());
                if (to != null) card.setCardBackgroundColor(to);
                if (first) card.setRadius(AfmcThemePolicy.scaleRadius(card.getRadius()));
            }

            if (v instanceof TextView) {
                TextView t = (TextView) v;
                ColorStateList tc = t.getTextColors();
                Integer to = tc.isStateful() ? null : colours.get(tc.getDefaultColor());
                if (to != null) t.setTextColor(to);
                Typeface face = t.getTypeface();
                if (face != Typeface.MONOSPACE && regular != null && strong != null) {
                    boolean bold = v instanceof Button || (face != null && (face.isBold() || face.getWeight() >= 500));
                    Typeface want = bold ? strong : regular;
                    if (face != want) t.setTypeface(want);
                }
            }
        }
    }

    /**
     * Starts {@link AfmcTheme} when EVCam's process starts (EVCam has no Application class of its own;
     * a provider is the earliest hook that needs no change to upstream code). Not exported.
     */
    public static final class Init extends ContentProvider {
        @Override public boolean onCreate() {
            Context c = getContext();
            if (c != null && c.getApplicationContext() instanceof Application) install((Application) c.getApplicationContext());
            return true;
        }
        @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] p, @Nullable String s, @Nullable String[] a, @Nullable String o) { return null; }
        @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
        @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
        @Override public int delete(@NonNull Uri uri, @Nullable String s, @Nullable String[] a) { return 0; }
        @Override public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String s, @Nullable String[] a) { return 0; }
    }
}
