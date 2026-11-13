package app.boxblox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boxblox's main screen: design K.
 *
 * <p>Hero launch button with the override count in its notch, a scrolling tab
 * strip, and full-width flag cards below. The tabs are built here rather than
 * with {@code TabLayout} because Material Components would put AndroidX classes
 * on this app's class path ahead of the archive's own — the shadowing failure
 * documented in {@code build.gradle.kts}.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "RobloxWrapper";
    private static final int REQUEST_APK = 1;

    private FlagStore flags;
    private RobloxStore.Build build;

    private LinearLayout tabStrip;
    private LinearLayout flagList;
    private ScrollView scroll;
    private TextView launchLabel;

    /** Set on the intent that restarts the process for a fresh engine. */
    private static final String EXTRA_LAUNCH_ON_START = "boxblox.launch_on_start";

    /** Identifies one restart independently of any state Android restores. */
    private static final String EXTRA_RELAUNCH_TOKEN = "boxblox.relaunch_token";
    private static String consumedRelaunchToken;

    private static final String KEY_RELAUNCH_TOKEN = "boxblox.consumed_relaunch";
    private static final String KEY_LAUNCH_PENDING = "boxblox.launch_pending";
    private String relaunchToken;
    private boolean launchPending;
    private final Runnable pendingLaunch = () -> {
        if (!launchPending || isFinishing() || isDestroyed()) {
            return;
        }
        onLaunch();
    };

    private static final String KEY_GROUP = "boxblox.group";
    private static final String KEY_SCROLL = "boxblox.scroll";
    private static final String KEY_EDITING = "boxblox.editing";

    private String selectedGroup = "All";
    private int pendingScroll;
    /**
     * The flag whose editor is open, or null.
     *
     * <p>A {@code Dialog} is destroyed with its activity on a configuration
     * change unless something puts it back, and the platform only restores
     * dialogs it created itself. Recording the key here and reopening after the
     * activity is recreated is what keeps the editor from vanishing on rotate.
     */
    private String editingKey;
    private final List<View> tabViews = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bb_main);
        applyStatusBarInset();

        flags = new FlagStore(this);
        tabStrip = findViewById(R.id.tab_strip);
        flagList = findViewById(R.id.flag_list);
        scroll = findViewById(R.id.scroll);
        launchLabel = findViewById(R.id.launch_label);

        findViewById(R.id.launch_button).setOnClickListener(v -> onLaunch());
        findViewById(R.id.builds_button).setOnClickListener(v -> showBuildsPanel());

        if (savedInstanceState != null) {
            selectedGroup = savedInstanceState.getString(KEY_GROUP, "All");
            editingKey = savedInstanceState.getString(KEY_EDITING);
        }
        buildTabs();
        loadStaged();
        // Android keeps the original intent in its ActivityRecord. Match saved
        // consumption to this particular restart, rather than replaying the
        // extra after process death or rejecting state from a previous restart.
        if (savedInstanceState != null) {
            relaunchToken = savedInstanceState.getString(KEY_RELAUNCH_TOKEN);
            launchPending = savedInstanceState.getBoolean(KEY_LAUNCH_PENDING, false);
        }
        if (getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_LAUNCH_ON_START, false)) {
            String token = getIntent().getStringExtra(EXTRA_RELAUNCH_TOKEN);
            if (token == null) {
                token = "legacy";
            }
            if (!token.equals(relaunchToken) && !token.equals(consumedRelaunchToken)) {
                launchPending = true;
            }
            relaunchToken = token;
            consumedRelaunchToken = token;
            getIntent().removeExtra(EXTRA_LAUNCH_ON_START);
            getIntent().removeExtra(EXTRA_RELAUNCH_TOKEN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        launchLabel.removeCallbacks(pendingLaunch);
        super.onPause();
    }

    /**
     * Rotation destroys and recreates the activity.
     *
     * <p>The selected tab lived only in a field, so it fell back to "All" on
     * every rotate, and the flag list's scroll position went with it. Saving
     * both here is what makes a rotate look like nothing happened.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_GROUP, selectedGroup);
        outState.putInt(KEY_SCROLL, scroll.getScrollY());
        outState.putString(KEY_EDITING, editingKey);
        outState.putString(KEY_RELAUNCH_TOKEN, relaunchToken);
        outState.putBoolean(KEY_LAUNCH_PENDING, launchPending);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        pendingScroll = state.getInt(KEY_SCROLL, 0);
    }

    /**
     * Apply the restored scroll offset once the list exists.
     *
     * <p>{@code onRestoreInstanceState} runs after {@code onCreate}, so an offset
     * consumed there is applied to an empty list and lands at zero. The offset is
     * therefore held until the activity has resumed — by then {@code onResume}'s
     * refresh has added the cards — and applied on the next pre-draw, after the
     * list has been measured, because a {@code scrollTo} before layout is
     * clamped back to the top.
     */
    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (launchPending) {
            launchLabel.removeCallbacks(pendingLaunch);
            launchLabel.post(pendingLaunch);
        }
        if (pendingScroll <= 0) {
            return;
        }
        final int y = pendingScroll;
        pendingScroll = 0;
        final android.view.ViewTreeObserver observer = scroll.getViewTreeObserver();
        observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                scroll.scrollTo(0, y);
                return true;
            }
        });
    }

    // ------------------------------------------------------------- data

    /**
     * Look for a staged archive without preparing it.
     *
     * <p>Preparation unpacks the archive and swaps the class loader, which is
     * expensive and only has to happen when launching. Listing the staged files
     * is cheap, so the UI can be useful before anything is installed.
     */
    private void loadStaged() {
        try {
            RobloxStore store = new RobloxStore(this);
            build = store.selectedBuild();
            List<File> staged = store.launchArchives();
            if (staged.isEmpty()) {
                showEmpty();
            } else {
                showFlags();
                if (editingKey != null) {
                    final String key = editingKey;
                    editingKey = null;
                    scroll.post(() -> reopenEditor(key));
                }
            }
        } catch (Throwable t) {
            showEmpty();
            Diag.set("could not read staged archives: " + t);
        }
    }

    private void showEmpty() {
        // Nothing is hidden. The flag list simply has no rows, and the dock
        // stays where the layout puts it — at the bottom, because the ScrollView
        // above it holds the weight. Hiding the ScrollView (the previous
        // behaviour) let the dock collapse up under the header, which is what
        // made the button look undocked.
        flagList.removeAllViews();
        tabStrip.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.VISIBLE);
        launchLabel.setText(R.string.import_roblox);
    }

    private void showFlags() {
        scroll.setVisibility(View.VISIBLE);
        tabStrip.setVisibility(View.VISIBLE);
        launchLabel.setText(R.string.launch);
        refresh();
    }

    // ------------------------------------------------------------- tabs

    private void buildTabs() {
        List<String> groups = new ArrayList<>();
        groups.add("All");
        for (FlagStore.Spec spec : FlagStore.CATALOGUE) {
            if (!groups.contains(spec.group)) {
                groups.add(spec.group);
            }
        }
        tabStrip.removeAllViews();
        tabViews.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String group : groups) {
            View tab = inflater.inflate(R.layout.bb_tab, tabStrip, false);
            TextView label = tab.findViewById(R.id.tab_label);
            label.setText(group);
            tab.setOnClickListener(v -> {
                selectedGroup = group;
                updateTabSelection();
                renderFlags();
            });
            tabStrip.addView(tab);
            tabViews.add(tab);
        }
        updateTabSelection();
        distributeTabs();
    }

    /**
     * Spread the tabs across the full width when they fit, scroll when they do not.
     *
     * <p>The strip is a {@code HorizontalScrollView} with a {@code wrap_content}
     * child, so by default the tabs pack to the left with dead space on the
     * right. Measuring the natural widths first is what lets the two cases be
     * told apart: if the tabs fit, each gets an equal share of the width; if
     * they do not, the strip keeps its natural size and scrolls.
     */
    private void distributeTabs() {
        tabStrip.post(() -> {
            View viewport = (View) tabStrip.getParent();
            int available = viewport.getWidth();
            if (available <= 0 || tabViews.isEmpty()) {
                return;
            }
            int natural = 0;
            for (View tab : tabViews) {
                tab.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                natural += tab.getMeasuredWidth();
            }
            if (natural > available) {
                // Too many to fit: natural widths, and the strip scrolls.
                for (View tab : tabViews) {
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) tab.getLayoutParams();
                    lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                    lp.weight = 0;
                    tab.setLayoutParams(lp);
                }
                return;
            }
            int each = available / tabViews.size();
            for (View tab : tabViews) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) tab.getLayoutParams();
                lp.width = each;
                lp.weight = 0;
                tab.setLayoutParams(lp);
            }
        });
    }

    private void updateTabSelection() {
        List<String> groups = new ArrayList<>();
        groups.add("All");
        for (FlagStore.Spec spec : FlagStore.CATALOGUE) {
            if (!groups.contains(spec.group)) {
                groups.add(spec.group);
            }
        }
        for (int i = 0; i < tabViews.size() && i < groups.size(); i++) {
            boolean on = groups.get(i).equals(selectedGroup);
            View tab = tabViews.get(i);
            TextView label = tab.findViewById(R.id.tab_label);
            View indicator = tab.findViewById(R.id.tab_indicator);
            label.setTextColor(on ? 0xFFD0BCFF : 0xFFCAC4D0);
            label.setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            indicator.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on) {
                // Keep the selected tab in view after a programmatic change.
                final View target = tab;
                tabStrip.post(() -> {
                    HorizontalScrollView scrollView = findViewById(R.id.tab_scroll);
                    scrollView.smoothScrollTo(
                            Math.max(0, target.getLeft() - 40), 0);
                });
            }
        }
    }

    // ------------------------------------------------------------ cards

    /**
     * Show the catalogue only when there is something to launch.
     *
     * <p>onResume calls this on every return to the screen, so rendering
     * unconditionally put the whole flag list back under the "Import Roblox"
     * button right after onCreate's empty state had cleared it.
     */
    private void refresh() {
        if (build != null) {
            renderFlags();
            return;
        }
        try {
            if (!new RobloxStore(this).launchArchives().isEmpty()) {
                renderFlags();
            } else {
                showEmpty();
            }
        } catch (Throwable t) {
            // Same fallback as loadStaged: a staging directory that cannot be
            // read is an empty screen, not a catalogue over nothing.
            showEmpty();
        }
    }

    private void renderFlags() {
        flagList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String lastGroup = null;
        for (FlagStore.Spec spec : FlagStore.CATALOGUE) {
            if (!"All".equals(selectedGroup) && !selectedGroup.equals(spec.group)) {
                continue;
            }
            if (!spec.group.equals(lastGroup)) {
                flagList.addView(sectionHeader(spec.group));
                lastGroup = spec.group;
            }
            flagList.addView(flagCard(inflater, spec));
        }
    }

    /**
     * Paint a card's selected state.
     *
     * <p>The outline marks a card whose setting is engaged: a toggle that is on,
     * or a number that has been changed from its default. It is deliberately not
     * the same test as the launch notch, which counts what will actually be
     * written to the guest — the wrapper's own toggles are on by default, so they
     * are outlined while contributing no override at all.
     */
    private void applyCardState(View card, boolean active) {
        // Only the outline moves. The label keeps one colour in every state: a
        // tinted label on a tinted card made the selected rows read as a
        // different kind of row rather than the same row in a different state.
        card.setBackgroundResource(active ? R.drawable.bb_card_on : R.drawable.bb_card);
    }


    private TextView sectionHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title.toUpperCase());
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        header.setLetterSpacing(0.07f);
        header.setTextColor(0xFF938F99);
        int top = dp(13), bottom = dp(2);
        header.setPadding(dp(2), top, dp(2), bottom);
        return header;
    }

    private View flagCard(LayoutInflater inflater, FlagStore.Spec spec) {
        View card = inflater.inflate(R.layout.bb_flag_card, flagList, false);
        TextView label = card.findViewById(R.id.flag_label);
        TextView key = card.findViewById(R.id.flag_key);
        ToggleView toggle = card.findViewById(R.id.flag_switch);
        TextView value = card.findViewById(R.id.flag_value);

        label.setText(spec.label);
        // A real view, not a Spannable: a background span has no padding, so the
        // text sat flush against its own highlight.
        card.findViewById(R.id.flag_badge)
                .setVisibility(spec.desktopOnly ? View.VISIBLE : View.GONE);
        key.setText(spec.key.startsWith("wrapper.") ? spec.description : spec.key);

        if (spec.kind == FlagStore.Kind.CHOICE) {
            // The same shape as a number row: the selected value sits in the
            // trailing slot, and the card opens the picker. A segmented control
            // was tried here first and read badly — three labels do not fit the
            // slot the switch leaves behind.
            toggle.setVisibility(View.GONE);
            value.setVisibility(View.VISIBLE);
            value.setText(currentChoice(spec));
            // The outline means "the wrapper is contributing an identity".
            // Mobile is the stock app — nothing is being overridden — so it is
            // not outlined even though it differs from the stored default.
            boolean active = "wrapper.session_mode".equals(spec.key)
                    ? !SessionMode.MOBILE.equals(SessionMode.read(flags))
                    : flags.isOverridden(spec);
            applyCardState(card, active);
            card.setOnClickListener(v -> editChoice(spec, value));
        } else if (spec.kind == FlagStore.Kind.BOOL) {
            toggle.setVisibility(View.VISIBLE);
            value.setVisibility(View.GONE);
            boolean on = flags.getBool(spec.key, FlagStore.defaultBool(spec));
            toggle.setCheckedImmediate(on);
            applyCardState(card, flags.isOverridden(spec));
            toggle.setOnCheckedChangeListener((view, checked) -> {
                flags.setBool(spec.key, checked);
                if ("wrapper.mouse_lock".equals(spec.key)) {
                    EngineBridge.setMouseLockEnabled(checked);
                }
                // Only this card is restated, not the whole list: a full rebuild
                // would recreate the toggle mid-animation and swallow the slide.
                applyCardState(card, flags.isOverridden(spec));
            });
            // The card is the touch target, not just the 44dp switch.
            card.setOnClickListener(v -> toggle.performClick());
            if (!spec.key.startsWith("wrapper.")) {
                key.setSingleLine(false);
                key.setMaxLines(3);
                // Long-press still clears the override; the key text no longer
                // repeats the hint on every card.
                View.OnLongClickListener reset = v -> {
                    flags.clear(spec.key);
                    refresh();
                    return true;
                };
                card.setOnLongClickListener(reset);
                toggle.setOnLongClickListener(reset);
            }
        } else {
            toggle.setVisibility(View.GONE);
            value.setVisibility(View.VISIBLE);
            String number = flags.getNumber(spec.key, spec.defaultValue);
            // A dash means "not set": the flag is absent from the payload and
            // the engine keeps its own value. Never print an empty pill.
            value.setText(number == null || number.isEmpty() ? "—" : number);
            applyCardState(card, flags.isOverridden(spec));
            card.setOnClickListener(v -> editNumber(spec, value));
        }
        return card;
    }

    /** The stored choice for a spec, migrating the retired key when present. */
    private String currentChoice(FlagStore.Spec spec) {
        String stored = flags.getChoice(spec.key);
        String current = stored != null ? stored
                : ("wrapper.session_mode".equals(spec.key)
                        ? SessionMode.read(flags) : spec.defaultValue);
        return current.substring(0, 1).toUpperCase() + current.substring(1);
    }

    /** Offer one of a choice spec's values, in the app's own dialog surface. */
    private void editChoice(FlagStore.Spec spec, TextView valueView) {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_choice, null);
        ((TextView) content.findViewById(R.id.dialog_title)).setText(spec.label);
        ((TextView) content.findViewById(R.id.dialog_supporting)).setText(spec.description);
        LinearLayout rows = content.findViewById(R.id.choice_rows);

        String current = flags.getChoice(spec.key);
        if (current == null) {
            current = "wrapper.session_mode".equals(spec.key)
                    ? SessionMode.read(flags) : spec.defaultValue;
        }
        BbDialog dialog = dialogWith(content);
        for (String choice : spec.choices) {
            TextView row = new TextView(this);
            row.setText(choice.substring(0, 1).toUpperCase() + choice.substring(1));
            row.setTextSize(15);
            row.setHeight(dp(48));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            boolean selected = choice.equals(current);
            row.setBackgroundResource(R.drawable.bb_card_on);
            // mutate() first: drawables loaded from one resource share a single
            // ConstantState, so without it the last row's alpha would win for
            // every row (and for cards using the same drawable).
            row.getBackground().mutate().setAlpha(selected ? 255 : 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            row.setLayoutParams(lp);
            String pick = choice;
            row.setOnClickListener(v -> {
                if ("wrapper.session_mode".equals(spec.key)) {
                    SessionMode.write(flags, pick);
                } else {
                    flags.setChoice(spec.key, pick);
                }
                valueView.setText(pick.substring(0, 1).toUpperCase() + pick.substring(1));
                dialog.dismiss();
                refresh();
            });
            rows.addView(row);
        }

        content.findViewById(R.id.dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }



    /** Reopen the number editor after a configuration change. */
    private void reopenEditor(String key) {
        FlagStore.Spec spec = FlagStore.spec(key);
        if (spec == null) {
            return;
        }
        for (int i = 0; i < flagList.getChildCount(); i++) {
            View child = flagList.getChildAt(i);
            if (child.findViewById(R.id.flag_value) != null
                    && spec.key.equals(((TextView) child.findViewById(R.id.flag_key)).getText()
                            .toString())) {
                editNumber(spec, child.findViewById(R.id.flag_value));
                return;
            }
        }
    }

    private void editNumber(FlagStore.Spec spec, TextView valueView) {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_number, null);
        TextView title = content.findViewById(R.id.dialog_title);
        TextView supporting = content.findViewById(R.id.dialog_supporting);
        EditText input = content.findViewById(R.id.dialog_input);

        title.setText(spec.label);
        supporting.setText(spec.kind == FlagStore.Kind.NUMBER
                ? spec.key + "\n" + spec.min + "–" + spec.max
                : spec.key);
        String current = flags.getNumber(spec.key, spec.defaultValue);
        // An unset number opens with an empty field and a hint, not the literal
        // "—" that the card shows.
        input.setText(current == null || current.isEmpty() ? "" : current);

        BbDialog dialog = dialogWith(content);
        editingKey = spec.key;
        dialog.setOnDismissListener(d -> editingKey = null);
        content.findViewById(R.id.dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.dialog_reset).setOnClickListener(v -> {
            flags.clear(spec.key);
            dialog.dismiss();
            refresh();
        });
        content.findViewById(R.id.dialog_confirm).setOnClickListener(v -> {
            String raw = input.getText().toString().trim();
            if (raw.isEmpty()) {
                flags.clear(spec.key);
                valueView.setText(flags.getNumber(spec.key, spec.defaultValue));
                dialog.dismiss();
                refresh();
                return;
            }
            // Out-of-range values are refused here rather than written and
            // ignored: the engine clamps or drops a flag it cannot use, with no
            // diagnostic the user would ever see.
            long parsed;
            try {
                parsed = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                input.setError("Not a whole number");
                return;
            }
            if (parsed < spec.min || parsed > spec.max) {
                input.setError("Must be " + spec.min + "–" + spec.max);
                return;
            }
            flags.setNumber(spec.key, raw);
            valueView.setText(flags.getNumber(spec.key, spec.defaultValue));
            dialog.dismiss();
            refresh();
        });
        dialog.show();
    }

    // ----------------------------------------------------------- builds

    /**
     * Show the installed builds.
     *
     * <p>A bottom sheet rather than an activity: switching builds is a choice
     * about the screen you are already looking at, and overrides are stored per
     * build, so the flag list has to be refreshed in place afterwards.
     */
    private void showBuildsPanel() {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_builds_panel, null);
        LinearLayout list = content.findViewById(R.id.build_list);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        if (dialog.getWindow() != null) {
            // The sheet is deliberately full width, unlike the notices.
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        try {
            List<RobloxStore.Build> installed = new RobloxStore(this).installedBuilds();
            // Nothing has been prepared yet on a cold start, so "active" is
            // whatever WrapperApplication last prepared, falling back to the
            // newest installed build. Without this the Active pill never showed
            // until after a launch.
            if (build == null && !installed.isEmpty()) {
                build = WrapperApplication.get() != null
                        ? WrapperApplication.get().getBuild() : null;
                if (build == null) {
                    build = installed.get(0);
                }
            }
            // Freshly staged archives are not "installed" until prepare() has
            // unpacked them, so the list would stay empty right after an import.
            // Listing the staged set as a pending entry is what makes the import
            // visibly do something.
            if (installed.isEmpty()) {
                List<File> staged = new RobloxStore(this).stagedArchives();
                if (!staged.isEmpty()) {
                    View row = LayoutInflater.from(this).inflate(R.layout.bb_build_row, list, false);
                    String name = staged.get(0).getName();
                    ((TextView) row.findViewById(R.id.build_version)).setText(name);
                    // Tapping a build selects it. It must not launch: the row
                    // appears right under the finger that just dismissed the
                    // import dialog, so launching here started Roblox the moment
                    // you imported it (measured).
                    row.setOnClickListener(v -> {
                        dialog.dismiss();
                        prepareStaged(staged);
                    });
                    list.addView(row);
                }
            }
            for (RobloxStore.Build b : installed) {
                View row = LayoutInflater.from(this).inflate(R.layout.bb_build_row, list, false);
                ((TextView) row.findViewById(R.id.build_version)).setText(b.getVersionName());
                boolean active = build != null && build.getSha256().equals(b.getSha256());
                row.findViewById(R.id.build_active).setVisibility(active ? View.VISIBLE : View.GONE);
                row.setSelected(active);
                row.setOnClickListener(v -> {
                    selectBuild(b);
                    dialog.dismiss();
                });
                // Long press deletes. No hint is shown on the row: the gesture is
                // discoverable by trying it, and a "hold to delete" line under
                // every build would read as a warning rather than an affordance.
                row.setOnLongClickListener(v -> {
                    dialog.dismiss();
                    confirmDeleteBuild(b, this::showBuildsPanel);
                    return true;
                });
                list.addView(row);
            }
        } catch (Throwable t) {
            Diag.set("could not list builds: " + t);
        }

        content.findViewById(R.id.build_import).setOnClickListener(v -> {
            dialog.dismiss();
            showImportChooser();
        });
        dialog.show();
    }

    /**
     * One entry point for both import routes.
     *
     * <p>The source is a detail the user should not have to decide about before
     * seeing the options: a build already installed on the phone is by far the
     * common case, and picking a file is the fallback.
     */
    private void showImportChooser() {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_chooser, null);
        ((TextView) content.findViewById(R.id.chooser_title)).setText(R.string.import_title);

        BbDialog dialog = dialogWith(content);

        // Every row is an action, so there is no confirm button to dismiss.
        LinearLayout host = (LinearLayout) content;
        int at = host.indexOfChild(content.findViewById(R.id.chooser_rows));
        for (String[] option : new String[][]{
                {getString(R.string.import_from_device), "installed"},
                {getString(R.string.import_from_file), "file"},
        }) {
            TextView row = new TextView(this);
            row.setText(option[0]);
            row.setTextColor(0xFFE6E0E9);
            row.setTextSize(14.5f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(24), padV = dp(14);
            row.setPadding(padH, padV, padH, padV);
            row.setClickable(true);
            row.setFocusable(true);
            final String which = option[1];
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if ("installed".equals(which)) {
                    useInstalledRoblox();
                } else {
                    pickApk();
                }
            });
            host.addView(row, at++);
        }
        dialog.show();
    }

    /**
     * Copy the Roblox already installed on this device.
     *
     * <p>This is the path most people will take: the official app is on the
     * phone already, so there is no reason to make them find an APK by hand.
     * The copy is staged like any other archive and then verified by the same
     * code, so it cannot be a way around the signature check.
     */
    private void useInstalledRoblox() {
        withProgress(R.string.importing_title, R.string.importing_body, () -> {
            List<File> staged = java.util.Collections.emptyList();
            try {
                staged = new RobloxStore(this).importInstalledRoblox();
                if (staged.isEmpty()) {
                    showError(getString(R.string.import_blocked_title),
                            getString(R.string.no_roblox_installed));
                    return;
                }
            // Verify the whole set, not just the first file: the base APK
            // carries no native libraries, so checking it alone reports
            // "no arm64-v8a libroblox.so" even though the split was copied
            // alongside it (measured).
            String refusal = verifyStaged(staged);
            if (refusal != null) {
                for (File f : staged) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                showBlockedNotice(refusal);
                return;
            }
            loadStaged();
            } catch (RobloxStore.Rejected e) {
                showError(getString(R.string.import_blocked_title), e.getMessage());
            } catch (Throwable t) {
                showError(getString(R.string.import_blocked_title), "Could not copy Roblox: " + t);
            } finally {
                if (!staged.isEmpty()) {
                    try { BuildFiles.delete(staged.get(0).getParentFile()); }
                    catch (java.io.IOException e) { Diag.set("could not clear imported copies: " + e); }
                }
            }
        });
    }

    /**
     * Prepare a staged set without launching it.
     *
     * <p>Unpacking is what turns a staged file into a usable build, and it is
     * separate from launching so that importing and choosing never starts the
     * engine on the user's behalf.
     */
    private void prepareStaged(List<File> staged) {
        // Unpacking a 139 MB XAPK copies ~112 MB of engine and assets, which is
        // seconds of blocking work. The work itself must stay on the UI thread —
        // it installs the class loader and runs the guest Application's onCreate,
        // which are process-wide and not safe to move — so the only fix for the
        // "nothing happened" impression is to paint something first and let the
        // frame commit before the block starts. withProgress does exactly that.
        launchLabel.setText(R.string.launch);
        withProgress(R.string.importing_title, R.string.importing_body, () -> {
            try {
                RobloxStore store = new RobloxStore(this);
                RobloxStore.Build prepared = store.prepare(staged);
                store.select(prepared);
                build = prepared;
                refresh();
            } catch (RobloxStore.Rejected e) {
                showError(getString(R.string.import_blocked_title), e.getMessage());
            } catch (Throwable t) {
                showError("Could not prepare this build", String.valueOf(t));
            }
        });
    }

    /**
     * Make {@code chosen} the active build.
     *
     * <p>Switching re-runs {@code prepare}, which re-verifies the archive's
     * signature — so a build cannot be activated by a path that skipped the
     * check. Overrides are per build, so the flag list is rebuilt afterwards.
     */
    private void selectBuild(RobloxStore.Build chosen) {
        try {
            RobloxStore store = new RobloxStore(this);
            store.select(chosen);
            build = chosen;
            Diag.set("switched to " + build.getVersionName());
            refresh();
        } catch (RobloxStore.Rejected e) {
            showError(e.getMessage());
        } catch (Throwable t) {
            showError("Could not switch build: " + t);
        }
    }

    // ----------------------------------------------------------- launch

    private void onLaunch() {
        launchPending = false;
        launchLabel.removeCallbacks(pendingLaunch);
        try {
            RobloxStore store = new RobloxStore(this);
            RobloxStore.Build selected = store.selectedBuild();
            List<File> staged = store.launchArchives();
            if (staged.isEmpty()) {
                showImportChooser();
                return;
            }

            // A used engine requires a fresh process. Restart before hashing,
            // preparing assets, writing settings, or starting session watchers:
            // the new process will perform that work once for the new session.
            RobloxStore.Build loaded = WrapperApplication.get().getBuild();
            if (WrapperApplication.get().engineWasUsed()
                    || (selected != null && loaded != null
                            && !selected.getSha256().equals(loaded.getSha256()))) {
                Diag.set("restarting the process for a clean engine");
                Intent restart = new Intent(this, MainActivity.class);
                restart.putExtra(EXTRA_LAUNCH_ON_START, true);
                restart.putExtra(EXTRA_RELAUNCH_TOKEN, java.util.UUID.randomUUID().toString());
                restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(restart);
                Runtime.getRuntime().exit(0);
                return;
            }

            // Enabling permits the engine's requests; it never forces capture itself.
            EngineBridge.setMouseLockEnabled(flags.getBool("wrapper.mouse_lock", false));
            launchLabel.setText("Preparing…");
            RobloxStore.Build prepared = WrapperApplication.get().prepare(staged);
            store.select(prepared);
            build = prepared;
            warnIfHooksUnsupported(prepared);

            // The file lives in the prepared build and must be ready before the
            // guest activity reads it during engine initialization.
            boolean written = flags.writeFor(prepared);
            Diag.set("flags file written=" + written + " (" + flags.describeFile(prepared) + ")");
            if (!written) {
                throw new IllegalStateException("Could not apply flag overrides; see diagnostics");
            }
            if (flags.getBool("wrapper.server_location", false)) {
                ServerLocation.start(prepared, this);
            }

            Intent intent = new Intent();
            intent.setClassName(getPackageName(), RobloxStore.MAIN_GAME_ACTIVITY);
            // Device-tested: replace stale guest records rather than stacking
            // sessions whose process-global engine has already ended.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RobloxStore.Rejected e) {
            launchLabel.setText(R.string.launch);
            showError(getString(R.string.import_blocked_title), e.getMessage());
        } catch (Throwable t) {
            launchLabel.setText(R.string.launch);
            showError("Failed: " + t);
            Log.w(TAG, "launch failed", t);
        }
        refresh();
    }

    private void showError(String message) {
        showError("Cannot launch", message);
    }

    /**
     * Say so when the build is not the one the native hooks were written for.
     *
     * <p>A warning, not a refusal. The hooks verify their own fingerprints and
     * disable themselves when Roblox has moved the offsets, so the worst case is
     * a missing input feature on a newer build — which is a far better outcome
     * than refusing to run a build the user deliberately imported.
     */
    private void warnIfHooksUnsupported(RobloxStore.Build prepared) {
        String version = prepared.getVersionName();
        if (version == null || RobloxStore.HOOKED_VERSION.equals(version)) {
            return;
        }
        Diag.set("build " + version + " is not the hooked version ("
                + RobloxStore.HOOKED_VERSION + "); mouse hooks will self-check");
        showError(getString(R.string.unsupported_build_title),
                getString(R.string.unsupported_build_body, version, RobloxStore.HOOKED_VERSION));
    }

    private void showError(String title, String message) {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_notice, null);
        ((TextView) content.findViewById(R.id.notice_title)).setText(title);
        ((TextView) content.findViewById(R.id.notice_body)).setText(message);
        BbDialog dialog = dialogWith(content);
        content.findViewById(R.id.notice_ok).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "application/zip",
        });
        try {
            startActivityForResult(intent, REQUEST_APK);
        } catch (android.content.ActivityNotFoundException e) {
            showError("No file picker on this device.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK || resultCode != RESULT_OK || data == null) return;
        File batch = null;
        try {
            batch = java.nio.file.Files.createTempDirectory(getCacheDir().toPath(), "import-").toFile();
            List<File> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    File file = stage(data.getClipData().getItemAt(i).getUri(), batch);
                    if (file == null) throw new java.io.IOException("Could not read the chosen file.");
                    picked.add(file);
                }
            } else if (data.getData() != null) {
                File file = stage(data.getData(), batch);
                if (file == null) throw new java.io.IOException("Could not read the chosen file.");
                picked.add(file);
            } else {
                return;
            }
            String refusal = verifyStaged(new RobloxStore(this).expandArchives(picked));
            if (refusal != null) showBlockedNotice(refusal
                    + "\n\nSelect the base APK and ABI split together, or choose a complete XAPK.");
        } catch (Exception e) {
            showBlockedNotice(e.getMessage());
        } finally {
            if (batch != null) {
                try { BuildFiles.delete(batch); } catch (java.io.IOException ignored) { }
            }
        }
        loadStaged();
    }

    /**
     * Check a staged set, returning a reason when it cannot be used.
     *
     * <p>Only the wrapper's own acceptance rule is applied here: an archive is
     * usable when {@code prepare} accepts it. That is deliberately the same code
     * path a launch takes, so there is no second, weaker notion of "valid".
     */
    private String verifyStaged(List<File> files) {
        try {
            RobloxStore store = new RobloxStore(this);
            store.select(store.prepare(files));
            return null;
        } catch (RobloxStore.Rejected e) {
            return e.getMessage();
        } catch (Throwable t) {
            return "This file could not be read.";
        }
    }

    /**
     * Give a custom-view dialog M3's width.
     *
     * <p>A custom view inflated with {@code MATCH_PARENT} takes the whole window,
     * which on a phone reads as a full-screen sheet rather than a dialog. M3
     * dialogs are inset from the screen edges and capped, so the width is set
     * explicitly here instead of left to the window.
     */
    /** Wrap a custom view in a dialog at M3's width. */
    private BbDialog dialogWith(View content) {
        return new BbDialog(this, content, 300);
    }

    /**
     * Show a blocking spinner while {@code work} runs on the next frame.
     *
     * <p>Importing verifies signatures and unpacks a 90 MB archive on this
     * thread, so without this the window simply freezes with no explanation.
     * The work is posted rather than run inline because the dialog has to be
     * drawn before the thread blocks; showing it and then calling the work in
     * the same frame means the spinner never paints.
     */
    private void withProgress(int titleRes, int bodyRes, Runnable work) {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_progress, null);
        ((TextView) content.findViewById(R.id.progress_title)).setText(titleRes);
        ((TextView) content.findViewById(R.id.progress_body)).setText(bodyRes);
        BbDialog dialog = dialogWith(content);
        dialog.setCancelable(false);
        dialog.show();
        content.post(() -> {
            try {
                work.run();
            } finally {
                dialog.dismiss();
            }
        });
    }

    /**
     * Ask before removing a build.
     *
     * <p>Deletion is the only destructive action in the app, so it is confirmed
     * rather than undone: a build can take a minute to import again, and the
     * delete gesture is a long press on a row that otherwise just selects.
     */
    private void confirmDeleteBuild(RobloxStore.Build build, Runnable after) {
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_confirm, null);
        ((TextView) content.findViewById(R.id.confirm_title)).setText(R.string.delete_build_title);
        boolean lastBuild;
        try {
            lastBuild = new RobloxStore(this).installedBuilds().size() <= 1;
        } catch (Throwable ignored) {
            // Treat an unreadable list as "not the last one": the delete itself
            // reports the real problem.
            lastBuild = false;
        }
        ((TextView) content.findViewById(R.id.confirm_body)).setText(getString(
                lastBuild ? R.string.delete_last_build_body : R.string.delete_build_body,
                build.getVersionName()));
        TextView action = content.findViewById(R.id.confirm_action);
        action.setText(R.string.delete);
        BbDialog dialog = dialogWith(content);
        content.findViewById(R.id.confirm_cancel).setOnClickListener(v -> dialog.dismiss());
        action.setOnClickListener(v -> {
            dialog.dismiss();
            withProgress(R.string.deleting_title, R.string.deleting_body, () -> {
                try {
                    new RobloxStore(this).delete(build);
                    // The build list is gone from under us, so the launcher has
                    // to forget the build it was pointing at; otherwise the next
                    // launch would try to start a directory that no longer
                    // exists. 'this.build' because the parameter of the same
                    // name is captured here and is final.
                    this.build = null;
                    launchLabel.setText(R.string.launch);
                } catch (RobloxStore.Rejected e) {
                    // Deliberately not showBlockedNotice: that dialog is the
                    // import refusal and says "this APK was not supplied by
                    // Roblox", which reads as a signature failure on a delete.
                    showError(getString(R.string.delete_build_title), e.getMessage());
                }
                // Re-decide the launcher state: with the last build gone this
                // has to fall back to the empty screen, and only refresh() makes
                // that call. Reopening the panel alone left the whole flag
                // catalogue painted over an empty install (reported).
                refresh();
                if (after != null) {
                    after.run();
                }
            });
        });
        dialog.show();
    }

    /**
     * The import refusal.
     *
     * <p>Deliberately says what happened and what is allowed, without naming
     * certificates or hashes: the user's problem is "this file will not run",
     * and the reason is that Boxblox only runs official builds.
     */
    private void showBlockedNotice(String detail) {
        // Same M3 surface as the number editor: a custom layout with the app's
        // own colours, because the platform dialog ignores the theme's palette
        // and renders in the system's default look.
        View content = LayoutInflater.from(this).inflate(R.layout.bb_dialog_notice, null);
        ((TextView) content.findViewById(R.id.notice_title))
                .setText(R.string.import_blocked_title);
        TextView body = content.findViewById(R.id.notice_body);
        body.setText(getString(R.string.import_blocked_body)
                + (detail == null ? "" : "\n\n" + detail));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        content.findViewById(R.id.notice_ok).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * Copy the picked file into the staging directory under its own name.
     *
     * <p>Deliberately not a fixed "roblox.apk": a base APK and its split are two
     * separate staged files, so naming both the same made the second import
     * overwrite the first and the pair could never be verified together.
     */
    private File stage(android.net.Uri uri, File dir) {
        File target = new File(dir, stagedName(uri));
        if (target.exists()) return null;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) return null;
            Streams.copy(in, out);
            return target;
        } catch (java.io.IOException e) {
            Log.w(TAG, "could not stage " + uri, e);
            return null;
        }
    }

    /** The picked file's own name, reduced to a safe last path component. */
    private String stagedName(android.net.Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Throwable t) {
            Diag.set("could not read the picked file's name: " + t);
        }
        if (name == null) {
            // Document ids look like "primary:Download/roblox.apk", so both
            // separators have to go before the name is used as a file name.
            name = uri.getLastPathSegment();
        }
        if (name != null) {
            int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
            if (cut >= 0) {
                name = name.substring(cut + 1);
            }
            name = name.trim();
        }
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "roblox.apk";
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".apk") && !lower.endsWith(".xapk")
                && !lower.endsWith(".apks") && !lower.endsWith(".zip")) {
            // stagedArchives() only lists these suffixes, so a file under any
            // other name would be invisible and the import would look empty.
            name = name + ".apk";
        }
        return name;
    }

    /**
     * Keep the hero out from under the status bar.
     *
     * <p>targetSdk 35 makes the window edge-to-edge by default, so the content
     * draws behind the clock unless it is inset. Measured: the app name collided
     * with the time and battery icons.
     */
    private void applyStatusBarInset() {
        View hero = findViewById(R.id.app_name);
        if (hero == null) {
            return;
        }
        final View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars());
                top = bars.top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            View titleBar = findViewById(R.id.hero);
            titleBar.setPadding(titleBar.getPaddingLeft(), top + dp(18),
                    titleBar.getPaddingRight(), dp(14));
            View dock = findViewById(R.id.dock);
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            dock.setPadding(dock.getPaddingLeft(), dp(12),
                    dock.getPaddingRight(), bottom + dp(14));
            return insets;
        });
        root.requestApplyInsets();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
