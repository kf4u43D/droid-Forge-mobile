package fr.androforge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.util.AtomicFile;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tablet-first shell. All native-engine access and file IO share one serial worker. */
public final class MainActivity extends Activity {
    private static final int OPEN_PATCH = 100, CREATE_PATCH = 101;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int PARAMETER_PAGE_SIZE = 32, MAX_EDITOR_CHARACTERS = 4096;
    private static final int MAX_DRAFT_CHARACTERS = 1024 * 1024;
    private static final int MAX_APPLIED_CELLS = 4096, MAX_APPLIED_CHARACTERS = 1024 * 1024;
    private static final int BG = 0xff242424, PANEL = 0xff292929, RAISED = 0xff373737;
    private static final int TEXT = 0xffeeeeee, MUTED = 0xffa8a8a8, CYAN = 0xff00d0ff;
    private static final int COPPER = 0xffc9c9c9, BORDER = 0xff484848, RED = 0xffff5b4c;
    private static final String[] CONTROLLERS = {"b32", "db8e", "e4", "m4", "p10", "p2b8", "p4b2", "p8s8", "s10"};
    private static final Pattern SECTION_SEPARATOR = Pattern.compile("-{3,}");
    private static final ScheduledThreadPoolExecutor WORKER = new ScheduledThreadPoolExecutor(1);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    // These fields are only accessed on WORKER, except the export snapshot (main thread).
    private static ScheduledFuture<?> pendingSave;
    private static String pendingExport;
    private static JSONObject retainedUi;
    private static volatile long documentRevision;
    private static final BlockTransitions BLOCK_TRANSITIONS = new BlockTransitions(128);
    private static boolean engineReady;
    private static List<Circuit> sharedCatalogue;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Circuit> catalogue = new ArrayList<>();
    private final List<Circuit> filteredCatalogue = new ArrayList<>();
    private final Map<String, Circuit> circuitByName = new HashMap<>();
    private final List<Button> engineButtons = new ArrayList<>();
    private final Map<String, Bitmap> rackImages = new HashMap<>();
    private final Set<String> pendingRackImages = new HashSet<>();
    private final List<Block> visibleBlocks = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    private final HashSet<Integer> foldedBlocks = new HashSet<>();
    private final Map<Integer, Integer> parameterPages = new HashMap<>();
    private final LinkedHashMap<String, Draft> editDrafts = new LinkedHashMap<>();
    private final LinkedHashMap<String, AppliedCells> appliedCells = new LinkedHashMap<>();
    private final List<OrphanDraft> orphanDrafts = new ArrayList<>();
    private final Set<AlertDialog> openDialogs = Collections.newSetFromMap(new WeakHashMap<AlertDialog, Boolean>());
    private Patch patch = Patch.empty();
    private String sourceSnapshot = "", patchName = "droid.ini", query = "", saveError = "";
    private String[] sourceLines = new String[0];
    private int selectedBlock = -1, activePane = 1, operations, inspectorGeneration, selectedSection, cardGeneration;
    private Circuit selectedCircuit;
    private boolean destroyed, loaded;
    private boolean simplifiedNavigation;
    private boolean recoveryRequired;
    private long activityDocumentRevision;
    private final Map<View, Boolean> busyEditors = new WeakHashMap<>();
    private String fingerprintSource, sourceFingerprint = "", draftSaveError = "";
    private final Runnable saveUi = this::rememberUi;
    private LinearLayout root, content, cataloguePanel, patchPanel, inspectorPanel;
    private LinearLayout inspectorBody, paneTabs, workspace, toolRail;
    private RackViewport rackViewport;
    private Button rackZoomLabel;
    private TextView fileLabel, statusLabel, patchCount, catalogueCount, inspectorTitle, sectionTitle;
    private Button undoButton, redoButton, menuButton;
    private EditText catalogueSearch;
    private ListView circuitList;
    private CircuitCardAdapter circuitCardAdapter;
    private SectionAdapter sectionAdapter;
    private AlertDialog catalogueDialog, previewDialog;
    private AlertDialog sourceDialog;
    private AlertDialog annotationDialog;
    private String sourceDraft, sourceDraftBase;
    private String dialogType = "", dialogCircuit = "", newParameterName = "", newParameterValue = "";
    private int dialogBlock = -1, sourceLine, sourceSelection;
    private boolean restoringUi;
    private int initialSelection = 1;
    private ConnectionPoint pendingConnection;
    private String followedCable = "";
    private CellDialogState cellDialog;
    private AnnotationDraft annotationDraft;
    private final List<Button> toolbarButtons = new ArrayList<>();
    private DroidActions droidActions;
    private BlockAdapter blockAdapter;
    private CatalogueAdapter catalogueAdapter;

    @Override protected void attachBaseContext(Context base) {
        Configuration configuration = new Configuration();
        configuration.setLocale(Locale.ENGLISH);
        configuration.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WORKER.setRemoveOnCancelPolicy(true);
        if (savedInstanceState != null) {
            selectedBlock = savedInstanceState.getInt("selected", -1);
            activePane = savedInstanceState.getInt("pane", 1);
            query = savedInstanceState.getString("query", "");
            patchName = savedInstanceState.getString("name", "droid.ini");
            selectedSection = savedInstanceState.getInt("section", 0);
            int[] folded = savedInstanceState.getIntArray("folded");
            if (folded != null) for (int index : folded) foldedBlocks.add(index);
            // Drafts are restored only from the complete, fingerprinted private session.
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildLayout();
        droidActions = new DroidActions(this, new DroidActions.Host() {
            @Override public boolean ready() { return loaded && operations == 0 && !recoveryRequired; }
            @Override public boolean hasDrafts() {
                return !editDrafts.isEmpty() || !orphanDrafts.isEmpty()
                        || annotationDraft != null && annotationDraft.dirty()
                        || sourceDraft != null && sourceSnapshot.equals(sourceDraftBase) && !sourceDraft.equals(sourceSnapshot);
            }
            @Override public void busy(boolean value) {
                operations += value ? 1 : -1;
                updateEnabled();
                if (!value) { updateDiagnostics(); droidActions.onHostReady(); }
            }
            @Override public void show(AlertDialog dialog) { trackDialog(dialog).show(); }
            @Override public void error(String title, String message) { showError(title, message); }
            @Override public void importSource(String source) { importDroidSource(source); }
            @Override public void exportSource() { confirmExport(); }
        });
        initialise();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        rememberUi();
        super.onSaveInstanceState(state);
        state.putInt("selected", selectedBlock);
        state.putInt("pane", activePane);
        state.putString("query", query);
        state.putString("name", patchName);
        state.putInt("section", selectedSection);
        int[] folded = new int[foldedBlocks.size()];
        int position = 0;
        for (int index : foldedBlocks) folded[position++] = index;
        state.putIntArray("folded", folded);
        // The full session file avoids Binder limits and partial draft restoration.
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        arrangePanels();
    }

    @Override protected void onStop() {
        rememberUi();
        super.onStop();
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            if (!engineReady) return;
            if (pendingSave != null) pendingSave.cancel(false);
            try {
                writeAutosave(application, NativeBridge.source());
                deliver(() -> { saveError = ""; if (loaded && operations == 0) updateDiagnostics(); });
            }
            catch (Exception | LinkageError | OutOfMemoryError exception) { reportSaveError(exception); }
        });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (droidActions != null) droidActions.close();
        for (AlertDialog dialog : new ArrayList<>(openDialogs)) dialog.dismiss();
        openDialogs.clear();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildLayout() {
        root = vertical();
        root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, right, top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left; right = bars.right; top = bars.top;
                bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
        LinearLayout titlebar = horizontal();
        titlebar.setGravity(Gravity.CENTER_VERTICAL);
        titlebar.setBackgroundColor(RAISED);
        TextView brand = label(getString(R.string.mobile_forge_name), 15, TEXT);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setPadding(dp(12), 0, dp(16), 0);
        titlebar.addView(brand);
        fileLabel = label("Loading the DROID engine…", 13, TEXT);
        fileLabel.setMaxLines(1);
        titlebar.addView(fileLabel, new LinearLayout.LayoutParams(0, -1, 1));
        menuButton = action("⋮", "File actions", false);
        menuButton.setOnClickListener(this::showFileMenu);
        titlebar.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(titlebar, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout editor = horizontal();
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        ScrollView railScroll = new ScrollView(this);
        railScroll.setFillViewport(false);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setBackgroundColor(0xff303030);
        toolRail = vertical();
        toolRail.setPadding(dp(2), dp(4), dp(2), dp(4));
        railScroll.addView(toolRail);
        editor.addView(railScroll, new LinearLayout.LayoutParams(dp(76), -1));
        addTool("New", "new", "Create a new patch", this::newPatch);
        addTool("Open", "open", "Import an .ini patch", this::openPatch);
        addTool("Save", "save", "Export the .ini patch", this::confirmExport);
        addToolDivider();
        addTool("Circuit", "new_circuit", "Open the circuit catalog", this::showCatalogue);
        addTool("Parameter", "add_jack", "Add a parameter to the selected circuit", this::showSelectedParameter);
        addTool("Module", "add_controller", "Add a hardware controller", this::addController);
        addToolDivider();
        addTool("Problems", "problems", "Open local diagnostics", this::showDiagnostics);
        addTool("Activate!", "upload_to_droid", "Activate the patch on DROID over MIDI", () -> droidActions.activate());
        addTool("Save to SD", "save_to_sd", "Save droid.ini to the DROID card", () -> droidActions.savePatch());
        addTool("Eject SD", "eject_sd", "Eject the DROID card", () -> droidActions.eject());
        undoButton = action("↶", "Undo the last change", false);
        undoButton.setTextSize(24);
        undoButton.setOnClickListener(v -> mutateHistory(false));
        toolRail.addView(undoButton, new LinearLayout.LayoutParams(-1, dp(48)));
        redoButton = action("↷", "Redo the change", false);
        redoButton.setTextSize(24);
        redoButton.setOnClickListener(v -> mutateHistory(true));
        toolRail.addView(redoButton, new LinearLayout.LayoutParams(-1, dp(48)));
        addTool("Info", "info", "About droid Forge mobile", this::showAbout);

        workspace = vertical();
        editor.addView(workspace, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout rack = new FrameLayout(this);
        rackViewport = new RackViewport(this);
        rackViewport.setListener(new RackViewport.Listener() {
            @Override public void openModule(RackViewport.Module module) { showRackModule(module); }
            @Override public void addModule() { addController(); }
            @Override public void editLabel(RackViewport.Module module, String register) {
                if (register == null) chooseModuleLabel(module); else editRegisterLabel(register, module);
            }
            @Override public void viewportChanged() { updateRackZoom(); requestUiSave(); }
        });
        rack.addView(rackViewport, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout rackTools = horizontal();
        rackTools.setBackground(background(0xe6303030, BORDER, 3));
        Button smaller = action("−", getString(R.string.mobile_rack_zoom_out), false);
        smaller.setOnClickListener(v -> rackViewport.zoomBy(1 / 1.25f));
        rackTools.addView(smaller, new LinearLayout.LayoutParams(dp(48), dp(48)));
        rackZoomLabel = action(getString(R.string.mobile_rack_zoom_percent, 100), getString(R.string.mobile_rack_zoom_reset), false);
        rackZoomLabel.setOnClickListener(v -> rackViewport.resetZoom());
        rackTools.addView(rackZoomLabel, new LinearLayout.LayoutParams(dp(66), dp(48)));
        Button larger = action("+", getString(R.string.mobile_rack_zoom_in), false);
        larger.setOnClickListener(v -> rackViewport.zoomBy(1.25f));
        rackTools.addView(larger, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button labels = action(getString(R.string.mobile_labels), getString(R.string.mobile_labels_edit), false);
        labels.setOnClickListener(v -> chooseRackLabels());
        rackTools.addView(labels, new LinearLayout.LayoutParams(dp(76), dp(48)));
        FrameLayout.LayoutParams rackToolsPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rackToolsPosition.setMargins(dp(4), dp(4), dp(4), dp(4));
        rack.addView(rackTools, rackToolsPosition);
        workspace.addView(rack, new LinearLayout.LayoutParams(-1, dp(180)));
        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        workspace.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        paneTabs = horizontal();
        Button sectionsTab = action("Sections", "Show patch sections", false);
        sectionsTab.setOnClickListener(v -> { activePane = 1; arrangePanels(); });
        Button circuitsTab = action("Circuits", "Show patch circuits", false);
        circuitsTab.setOnClickListener(v -> { activePane = 2; arrangePanels(); });
        paneTabs.addView(sectionsTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        paneTabs.addView(circuitsTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        workspace.addView(paneTabs, new LinearLayout.LayoutParams(-1, dp(48)));
        content = horizontal();
        workspace.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildCatalogue();
        buildPatch();
        buildInspector();
        statusLabel = label("Initializing…", 12, MUTED);
        statusLabel.setGravity(Gravity.CENTER_VERTICAL);
        statusLabel.setMinHeight(dp(44));
        statusLabel.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusLabel.setBackgroundColor(0xff303030);
        statusLabel.setOnClickListener(v -> {
            if (pendingConnection != null) showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.forge_connection_start).setMessage(statusLabel.getText())
                    .setNegativeButton("Continue", null).setPositiveButton(R.string.forge_connection_abort, (dialog, which) -> abortConnection()));
            else if (orphanDrafts.isEmpty()) showDiagnostics(); else showOrphanDrafts();
        });
        root.addView(statusLabel, new LinearLayout.LayoutParams(-1, -2));
        arrangePanels();
        updateEnabled();
    }

    private void addTool(String title, String icon, String description, Runnable callback) {
        Button button = action(title, description, false);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setPadding(dp(2), dp(7), dp(2), dp(5));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff),
                new android.graphics.drawable.ColorDrawable(0xff303030), null));
        try (InputStream input = getAssets().open("forge/icons/" + icon + ".png")) {
            Drawable image = Drawable.createFromStream(input, icon);
            if (image != null) {
                image.setBounds(0, 0, dp(28), dp(28));
                button.setCompoundDrawables(null, image, null, null);
                button.setCompoundDrawablePadding(dp(4));
            }
        } catch (IOException ignored) { /* Label remains available if an asset is unavailable. */ }
        button.setOnClickListener(v -> { if (loaded && operations == 0) callback.run(); });
        toolRail.addView(button, new LinearLayout.LayoutParams(-1, dp(68)));
        toolbarButtons.add(button);
        button.setTag(icon);
    }

    private void addToolDivider() {
        View line = new View(this); line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, dp(1));
        spacing.setMargins(dp(8), dp(4), dp(8), dp(4)); toolRail.addView(line, spacing);
    }

    private void buildCatalogue() {
        cataloguePanel = panel();
        LinearLayout heading = horizontal();
        heading.addView(label("Catalog", 18, TEXT), new LinearLayout.LayoutParams(0, dp(48), 1));
        catalogueCount = label("", 12, CYAN);
        catalogueCount.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(catalogueCount);
        cataloguePanel.addView(heading);
        EditText search = edit("Search circuits…");
        catalogueSearch = search;
        search.setSingleLine(true);
        search.setText(query);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setContentDescription("Search the official circuit catalog");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); filterCatalogue(); requestUiSave();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        cataloguePanel.addView(search, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView hint = label("Tap a circuit to view its parameters.", 12, MUTED);
        hint.setPadding(0, dp(10), 0, dp(10));
        cataloguePanel.addView(hint);
        ListView list = list();
        catalogueAdapter = new CatalogueAdapter();
        list.setAdapter(catalogueAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> showCircuitPreview(filteredCatalogue.get(position)));
        cataloguePanel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void buildPatch() {
        patchPanel = panel();
        TextView heading = label("SECTIONS", 12, MUTED);
        heading.setPadding(dp(8), 0, dp(8), 0);
        patchPanel.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));
        ListView list = list();
        sectionAdapter = new SectionAdapter();
        list.setAdapter(sectionAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedSection = position;
            selectedBlock = -1;
            activePane = 2;
            updateVisibleBlocks();
            renderInspector();
            arrangePanels();
        });
        patchPanel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        patchCount = label("", 12, MUTED);
        patchCount.setPadding(dp(8), 0, dp(8), 0);
        patchPanel.addView(patchCount, new LinearLayout.LayoutParams(-1, dp(44)));
        // Kept separate from section navigation: source block indices remain engine indices.
        blockAdapter = new BlockAdapter();
    }

    private void buildInspector() {
        inspectorPanel = panel();
        sectionTitle = label("PATCH", 12, MUTED);
        sectionTitle.setPadding(dp(12), 0, dp(12), 0);
        inspectorPanel.addView(sectionTitle, new LinearLayout.LayoutParams(-1, dp(40)));
        circuitList = list(true);
        circuitList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        circuitList.setItemsCanFocus(true);
        circuitList.setContentDescription("Circuits in this patch section");
        circuitCardAdapter = new CircuitCardAdapter();
        circuitList.setAdapter(circuitCardAdapter);
        inspectorPanel.addView(circuitList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void arrangePanels() {
        if (content == null || inspectorPanel == null) return;
        content.removeAllViews();
        int width = getResources().getConfiguration().screenWidthDp;
        boolean split = width >= 640;
        paneTabs.setVisibility(split ? View.GONE : View.VISIBLE);
        if (split) {
            int sidebar = Math.max(150, Math.min(240, Math.round((width - 76) * 0.23f)));
            content.addView(patchPanel, panelParams(dp(sidebar), 0));
            content.addView(inspectorPanel, panelParams(0, 1));
        } else content.addView(activePane == 1 ? patchPanel : inspectorPanel, panelParams(0, 1));
        for (int i = 0; i < paneTabs.getChildCount(); i++) {
            Button tab = (Button) paneTabs.getChildAt(i);
            boolean selected = (i == 0 && activePane == 1) || (i == 1 && activePane != 1);
            tab.setTextColor(selected ? TEXT : MUTED);
            tab.setBackground(background(selected ? RAISED : BG, BORDER, 0));
        }
        if (workspace != null) {
            int height = getResources().getConfiguration().screenHeightDp;
            int rackHeight = height < 500 ? 116 : height < 700 ? 152 : 180;
            View rack = workspace.getChildAt(0);
            rack.getLayoutParams().height = dp(rackHeight);
            rack.requestLayout();
        }
    }

    private LinearLayout.LayoutParams panelParams(int width, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -1, weight);
        params.setMargins(dp(1), dp(1), dp(1), 0);
        return params;
    }

    private void showCatalogue() {
        if (!loaded || operations != 0) return;
        detach(cataloguePanel);
        dialogType = "catalogue";
        catalogueDialog = trackDialog(new AlertDialog.Builder(this).setTitle("Add a circuit")
                .setView(cataloguePanel).setNegativeButton("Close", null).create());
        catalogueDialog.setOnDismissListener(dialog -> {
            detach(cataloguePanel); catalogueDialog = null;
            if (!destroyed && "catalogue".equals(dialogType)) { dialogType = ""; requestUiSave(); }
        });
        catalogueDialog.show();
        int width = getResources().getConfiguration().screenWidthDp;
        catalogueDialog.getWindow().setLayout(dp(Math.min(780, Math.max(300, width - 48))),
                dp(Math.min(640, Math.max(320, getResources().getConfiguration().screenHeightDp - 100))));
    }

    private static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private void showCircuitPreview(Circuit circuit) {
        if (catalogueDialog != null) catalogueDialog.dismiss();
        selectedCircuit = circuit;
        dialogType = "circuit"; dialogCircuit = circuit.name;
        LinearLayout body = panel();
        inspectorTitle = label(circuit.name.toUpperCase(Locale.ROOT), 17, TEXT);
        body.addView(inspectorTitle, new LinearLayout.LayoutParams(-1, dp(48)));
        Button initialParameters = action(getString(R.string.forge_initial_parameters), getString(R.string.forge_initial_parameters), false);
        initialParameters.setOnClickListener(v -> chooseInitialParameters());
        body.addView(initialParameters, new LinearLayout.LayoutParams(-1, dp(48)));
        ScrollView scroll = new ScrollView(this);
        inspectorBody = vertical();
        scroll.addView(inspectorBody);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        inspectorGeneration++;
        engineButtons.clear();
        renderCircuitPreview(circuit);
        previewDialog = trackDialog(new AlertDialog.Builder(this).setView(body).setNegativeButton("Close", null).create());
        previewDialog.setOnDismissListener(dialog -> {
            selectedCircuit = null; inspectorBody = null; previewDialog = null; engineButtons.clear();
            if (!destroyed) { dialogType = ""; requestUiSave(); }
        });
        previewDialog.show();
        previewDialog.getWindow().setLayout(dp(Math.min(720, Math.max(300, getResources().getConfiguration().screenWidthDp - 48))),
                dp(Math.min(640, Math.max(320, getResources().getConfiguration().screenHeightDp - 100))));
        updateEnabled();
    }

    private void showSelectedParameter() {
        Block block = findBlock(selectedBlock);
        if (block == null && !visibleBlocks.isEmpty()) { block = visibleBlocks.get(0); selectedBlock = block.index; }
        if (block == null) { toast("Add or select a circuit first."); return; }
        showParameterDialog(block);
    }

    private void showParameterDialog(Block block) {
        Circuit circuit = circuitByName.get(block.name);
        if (circuit == null) { toast("Undocumented parameters. Use the source editor."); return; }
        if (dialogBlock != block.index || !"parameter".equals(dialogType)) { newParameterName = ""; newParameterValue = ""; }
        dialogType = "parameter"; dialogBlock = block.index;
        LinearLayout body = panel();
        ScrollView scroll = new ScrollView(this);
        inspectorBody = vertical();
        scroll.addView(inspectorBody); body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        engineButtons.clear();
        renderAddParameter(block, circuit);
        if (inspectorBody.getChildCount() == 0) {
            inspectorBody = null;
            toast("All documented parameters are already present in this circuit."); return;
        }
        previewDialog = trackDialog(new AlertDialog.Builder(this).setTitle("Parameter · " + block.name.toUpperCase(Locale.ROOT))
                .setView(body).setNegativeButton("Close", null).create());
        previewDialog.setOnDismissListener(dialog -> {
            inspectorBody = null; previewDialog = null; engineButtons.clear();
            if (!destroyed) { dialogType = ""; requestUiSave(); }
        });
        previewDialog.show();
        previewDialog.getWindow().setLayout(dp(Math.min(640, Math.max(300, getResources().getConfiguration().screenWidthDp - 48))),
                dp(Math.min(440, Math.max(300, getResources().getConfiguration().screenHeightDp - 100))));
        updateEnabled();
    }

    private void initialise() {
        operations++;
        updateEnabled();
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            String sourceForRecovery = null;
            try {
                if (sharedCatalogue == null) sharedCatalogue = parseCatalogue(NativeBridge.catalogue());
                List<Circuit> circuits = sharedCatalogue;
                String notice = "";
                Patch result;
                if (engineReady) {
                    // Rotation and multiwindow recreation retain native history and pending edits.
                    sourceForRecovery = NativeBridge.source();
                    result = parsePatch(NativeBridge.state());
                } else {
                    String text;
                    try (InputStream input = autosave(application).openRead()) { text = readUtf8(input); }
                    catch (FileNotFoundException noSave) {
                        try (InputStream input = application.getAssets().open("examples/starter.ini")) { text = readUtf8(input); }
                    } catch (IOException badSave) {
                        File original = new File(application.getFilesDir(), "patch-autosave.ini");
                        if (original.isFile()) java.nio.file.Files.copy(original.toPath(),
                                new File(application.getFilesDir(), "patch-autosave-recovery.ini").toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        try (InputStream input = application.getAssets().open("examples/starter.ini")) { text = readUtf8(input); }
                        notice = "The local autosave could not be read. A recovery copy was kept and the example was opened: " + errorMessage(badSave);
                    }
                    sourceForRecovery = text;
                    result = parsePatch(NativeBridge.load(text));
                }
                engineReady = true;
                String snapshot = NativeBridge.source();
                JSONObject retained = retainedUi;
                if (retained != null && retained.optLong("documentRevision", -1) != documentRevision) retained = null;
                JSONObject restored = retained;
                if (restored == null) {
                    try (InputStream input = uiAutosave(application).openRead()) {
                        restored = new JSONObject(readUtf8(input, 32 * 1024 * 1024));
                    } catch (IOException | JSONException ignored) {
                        // The committed patch is independent of optional UI recovery metadata.
                    }
                }
                if (retained == null) {
                    // A process may die after an import commits its source but before its UI callback.
                    try (InputStream input = uiTransition(application).openRead()) {
                        JSONObject journal = new JSONObject(readUtf8(input, 64 * 1024 * 1024));
                        String[] candidates = journal.optBoolean("committed", false)
                                ? new String[]{"next", "previous"} : new String[]{"previous", "next"};
                        for (String candidate : candidates) {
                            JSONObject state = journal.optJSONObject(candidate);
                            if (state != null && PatchTextCodec.sha256(snapshot).equals(state.optString("sourceHash"))) {
                                restored = state;
                                break;
                            }
                        }
                    } catch (IOException | JSONException ignored) { /* The verified pre-import copies remain available. */ }
                }
                final JSONObject uiState = restored;
                final boolean uiFromMemory = retained != null;
                final long revision = documentRevision;
                Map<String, Bitmap> images = new HashMap<>();
                for (String module : new String[]{"master", "master18", "p2b8", "m4", "b32"}) {
                    Bitmap bitmap = loadAssetBitmap(application, "modules/" + module + ".png");
                    if (bitmap != null) images.put(module, bitmap);
                }
                scheduleAutosave(application, snapshot);
                final String message = notice;
                deliver(() -> {
                    operations--;
                    catalogue.clear(); catalogue.addAll(circuits);
                    circuitByName.clear();
                    for (Circuit circuit : circuits) {
                        circuitByName.put(circuit.name, circuit);
                        if (!circuit.shortName.isEmpty()) circuitByName.put(circuit.shortName, circuit);
                    }
                    rackImages.putAll(images);
                    loaded = true;
                    sourceSnapshot = snapshot;
                    patch = result;
                    activityDocumentRevision = revision;
                    restoreUi(uiState, uiFromMemory);
                    filterCatalogue();
                    renderPatch();
                    restoreDialog();
                    if (!message.isEmpty()) showError("Local autosave", message);
                    droidActions.onHostReady();
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                if (exception instanceof OutOfMemoryError && sourceForRecovery == null) {
                    try (InputStream input = autosave(application).openRead()) { sourceForRecovery = readUtf8(input); }
                    catch (IOException | OutOfMemoryError ignored) { /* The private file remains intact for a later restart. */ }
                }
                final String recoverySource = sourceForRecovery;
                deliver(() -> {
                    operations--;
                    if (exception instanceof OutOfMemoryError && recoverySource != null) {
                        sourceSnapshot = recoverySource; recoveryRequired = true; loaded = true;
                        renderPatch();
                    } else {
                        updateEnabled();
                        fileLabel.setText(R.string.forge_engine_unavailable);
                        statusLabel.setText(R.string.forge_patch_load_failed);
                    }
                    showError("Unable to load", errorMessage(exception));
                });
            }
        });
    }

    private interface EngineAction { String run() throws Exception; }
    private enum EditMode { NORMAL, HISTORY, SOURCE, RESET }

    private static String replaceDocument(String source, boolean resetHistory) {
        if (!resetHistory) return NativeBridge.replaceSource(source);
        // Native publication can succeed before its JSON return runs out of memory.
        // A raw source edit belongs to the current history; importing starts a document.
        long previousRevision = documentRevision;
        documentRevision++;
        try {
            String result = NativeBridge.load(source);
            BLOCK_TRANSITIONS.clear();
            return result;
        }
        catch (OutOfMemoryError uncertain) {
            BLOCK_TRANSITIONS.clear();
            throw uncertain;
        }
        catch (RuntimeException | LinkageError refused) {
            // These native failures reject before publication. OOM remains uncertain.
            documentRevision = previousRevision;
            throw refused;
        }
    }

    private void mutateHistory(boolean redo) {
        mutate(redo ? "Redoing…" : "Undoing…", -2,
                redo ? NativeBridge::redo : NativeBridge::undo, null, EditMode.HISTORY);
    }

    private void mutate(String progress, int selection, EngineAction action) {
        mutate(progress, selection, action, null);
    }

    private void mutate(String progress, int selection, EngineAction action, Runnable afterSuccess) {
        mutate(progress, selection, action, afterSuccess, EditMode.NORMAL);
    }

    private void mutate(String progress, int selection, EngineAction action, Runnable afterSuccess, EditMode mode) {
        if (!loaded || operations != 0) return;
        if (sourceDialog == null && sourceDraft != null && sourceSnapshot.equals(sourceDraftBase)) {
            showDialog(new AlertDialog.Builder(this).setTitle("A source draft is pending")
                    .setMessage("This change would alter the patch your draft is based on. Resume or discard the draft before continuing.")
                    .setNegativeButton("Return to patch", null)
                    .setPositiveButton("Edit draft", (dialog, which) -> editSource(sourceLine))
                    .setNeutralButton("Discard and continue", (dialog, which) -> {
                        sourceDraft = null; sourceDraftBase = null;
                        mutate(progress, selection, action, afterSuccess, mode);
                    }));
            return;
        }
        operations++;
        statusLabel.setText(progress);
        updateEnabled();
        Context application = getApplicationContext();
        Patch before = patch;
        String beforeSource = sourceSnapshot;
        int selectedBefore = selectedBlock;
        WORKER.execute(() -> {
            try {
                Patch result = parsePatch(action.run());
                String snapshot = NativeBridge.source();
                int[] correspondence = transition(before, result, beforeSource, snapshot, selection, selectedBefore, mode);
                long revision = documentRevision;
                String autosaveFailure = persistAppliedPatch(application, snapshot);
                deliver(() -> {
                    operations--;
                    rebaseAppliedCells(before, result, correspondence, mode, beforeSource.equals(snapshot));
                    rebaseDrafts(before, result, correspondence);
                    patch = result; sourceSnapshot = snapshot;
                    activityDocumentRevision = revision; recoveryRequired = false;
                    saveError = autosaveFailure;
                    if (selection == -3) {
                        selectedBlock = result.blocks.isEmpty() ? -1 : result.blocks.get(result.blocks.size() - 1).index;
                        selectedCircuit = null; activePane = 2;
                    } else if (selection != -2) {
                        selectedBlock = selection; selectedCircuit = null;
                        if (selection >= 0) activePane = 2;
                    }
                    if (afterSuccess != null) afterSuccess.run();
                    if (previewDialog != null) previewDialog.dismiss();
                    if (catalogueDialog != null) catalogueDialog.dismiss();
                    renderPatch(); arrangePanels();
                    droidActions.onHostReady();
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                String recoveredSource = null;
                Patch recoveredPatch = null;
                if (exception instanceof OutOfMemoryError) {
                    try {
                        recoveredSource = NativeBridge.source();
                        recoveredPatch = parsePatch(NativeBridge.state());
                    } catch (Exception | LinkageError | OutOfMemoryError ignored) { /* Export the recovered source if rendering still cannot complete. */ }
                }
                final String recoverySource = recoveredSource;
                final Patch recoveryPatch = recoveredPatch;
                final long recoveryRevision = documentRevision;
                int[] recoveredCorrespondence = null;
                if (recoverySource != null && recoveryPatch != null) {
                    try { recoveredCorrespondence = transition(before, recoveryPatch, beforeSource, recoverySource, selection, selectedBefore, mode); }
                    catch (OutOfMemoryError ignored) { /* Preserve drafts separately when identity cannot be recovered. */ }
                }
                final int[] recoveryMapping = recoveredCorrespondence;
                final String recoverySaveError = recoverySource == null ? saveError : persistAppliedPatch(application, recoverySource);
                deliver(() -> {
                    operations--;
                    activityDocumentRevision = recoveryRevision;
                    if (exception instanceof OutOfMemoryError) {
                        if (recoverySource != null) sourceSnapshot = recoverySource;
                        saveError = recoverySaveError;
                        if (recoveryPatch != null) {
                            rebaseAppliedCells(before, recoveryPatch, recoveryMapping, mode, beforeSource.equals(recoverySource));
                            rebaseDrafts(before, recoveryPatch, recoveryMapping);
                            patch = recoveryPatch; renderPatch();
                        }
                        recoveryRequired = recoveryPatch == null;
                    }
                    updateEnabled(); updateDiagnostics();
                    showError(exception instanceof OutOfMemoryError ? "Operation interrupted" : "Change rejected", errorMessage(exception));
                });
            }
        });
    }

    private void renderPatch() {
        cardGeneration++;
        if (selectedBlock >= 0 && findBlock(selectedBlock) == null) selectedBlock = -1;
        fileLabel.setText(getString(R.string.forge_file_name, patchName, patch.firmware.isEmpty() ? "DROID" : patch.firmware));
        patchCount.setText(getResources().getQuantityString(R.plurals.forge_block_count, patch.blocks.size(), patch.blocks.size()));
        parseSections();
        updateVisibleBlocks();
        sectionAdapter.notifyDataSetChanged();
        catalogueAdapter.notifyDataSetChanged();
        renderInspector();
        renderRack();
        updateEnabled();
        updateDiagnostics();
        requestUiSave();
    }

    private void updateEnabled() {
        boolean enabled = loaded && operations == 0;
        menuButton.setEnabled(enabled);
        undoButton.setEnabled(enabled && patch.canUndo && !recoveryRequired);
        redoButton.setEnabled(enabled && patch.canRedo && !recoveryRequired);
        undoButton.setTextColor(undoButton.isEnabled() ? CYAN : MUTED);
        redoButton.setTextColor(redoButton.isEnabled() ? CYAN : MUTED);
        if (inspectorBody != null) enableEditors(inspectorBody, enabled);
        if (circuitList != null) enableEditors(circuitList, enabled);
        boolean canEdit = enabled && patch.analysisComplete && !recoveryRequired;
        for (Button button : engineButtons) button.setEnabled(canEdit);
        for (Button button : toolbarButtons) {
            Object icon = button.getTag();
            boolean needsAnalysis = "new_circuit".equals(icon) || "add_jack".equals(icon) || "add_controller".equals(icon);
            button.setEnabled(enabled && (!needsAnalysis || (patch.analysisComplete && !recoveryRequired)));
        }
        if (catalogueAdapter != null) catalogueAdapter.notifyDataSetChanged();
        if (sourceDialog != null) {
            View source = sourceDialog.findViewById(android.R.id.edit);
            if (source != null) source.setEnabled(enabled);
            Button apply = sourceDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (apply != null) apply.setEnabled(enabled);
        }
        if (annotationDialog != null && annotationDialog.getWindow() != null)
            enableEditors(annotationDialog.getWindow().getDecorView(), enabled);
    }

    private void enableEditors(View view, boolean enabled) {
        if (view instanceof EditText || view instanceof Spinner || view instanceof Button) {
            if (!enabled) { if (!busyEditors.containsKey(view)) busyEditors.put(view, view.isEnabled()); view.setEnabled(false); }
            else if (busyEditors.containsKey(view)) view.setEnabled(busyEditors.remove(view));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enableEditors(group.getChildAt(i), enabled);
        }
    }

    private void filterCatalogue() {
        filteredCatalogue.clear();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (Circuit circuit : catalogue) {
            if (needle.isEmpty() || (circuit.name + " " + circuit.title + " " + circuit.category)
                    .toLowerCase(Locale.ROOT).contains(needle)) filteredCatalogue.add(circuit);
        }
        catalogueCount.setText(getString(R.string.forge_catalogue_count, filteredCatalogue.size()));
        catalogueAdapter.notifyDataSetChanged();
    }

    private void renderInspector() {
        if (circuitCardAdapter == null) return;
        sectionTitle.setText(sections.isEmpty() ? "PATCH" : sections.get(selectedSection).title.toUpperCase(Locale.ROOT));
        circuitCardAdapter.notifyDataSetChanged();
        if (sectionAdapter != null) sectionAdapter.notifyDataSetChanged();
        updateEnabled();
    }

    // Forge sections are comments bracketed by '# ----'. Read them for navigation only;
    // source mutations continue to use the original engine block indices and exact bytes.
    private void parseSections() {
        sections.clear();
        sections.add(new Section("Untitled section", 1));
        int lineCount = 1;
        simplifiedNavigation = sourceSnapshot.length() > 512 * 1024;
        if (!simplifiedNavigation) for (int i = 0; i < sourceSnapshot.length(); i++) {
            char character = sourceSnapshot.charAt(i);
            if (character == '\n' || (character == '\r' && (i + 1 == sourceSnapshot.length() || sourceSnapshot.charAt(i + 1) != '\n')))
                if (++lineCount > 8192) { simplifiedNavigation = true; break; }
        }
        if (simplifiedNavigation) {
            sourceLines = new String[0];
            sections.set(0, new Section("Patch · simplified view", 1));
            for (Block block : patch.blocks) if (!block.controller) sections.get(0).blocks.add(block);
            selectedSection = 0;
            return;
        }
        sourceLines = sourceSnapshot.split("\\r\\n|\\n|\\r", -1);
        String[] lines = sourceLines;
        boolean header = false;
        String title = "";
        for (int line = 0; line < lines.length; line++) {
            String text = lines[line].trim();
            if (line == 0 && text.startsWith("\ufeff")) text = text.substring(1).trim();
            if (text.isEmpty()) continue;
            if (!text.startsWith("#")) { header = false; title = ""; continue; }
            String comment = text.substring(1).trim();
            if (SECTION_SEPARATOR.matcher(comment).matches()) {
                if (header) {
                    if (!title.isEmpty()) sections.add(new Section(displayText(title, 256), line + 2));
                    header = false; title = "";
                } else { header = true; title = ""; }
            } else if (header && title.isEmpty() && !comment.isEmpty()) title = comment;
        }
        int section = 0;
        for (Block block : patch.blocks) if (!block.controller) {
            while (section + 1 < sections.size() && block.line >= sections.get(section + 1).startLine) section++;
            sections.get(section).blocks.add(block);
        }
        if (sections.size() > 1 && sections.get(0).blocks.isEmpty()) sections.remove(0);
        selectedSection = Math.max(0, Math.min(selectedSection, sections.size() - 1));
        // Selecting a newly inserted circuit brings its real section into view.
        if (selectedBlock >= 0) {
            for (int i = 0; i < sections.size(); i++) for (Block block : sections.get(i).blocks)
                if (block.index == selectedBlock) selectedSection = i;
        }
    }

    private void updateVisibleBlocks() {
        visibleBlocks.clear();
        if (!sections.isEmpty()) visibleBlocks.addAll(sections.get(selectedSection).blocks);
    }

    /** Runs on WORKER: establish and retain identity before an Activity can be recreated. */
    private static int[] transition(Patch before, Patch after, String beforeSource, String afterSource,
                                    int selection, int selectedBefore, EditMode mode) {
        if (beforeSource.equals(afterSource)) return BlockTransitions.identity(before.blocks.size());
        String beforeHash = PatchTextCodec.sha256(beforeSource), afterHash = PatchTextCodec.sha256(afterSource);
        if (mode == EditMode.HISTORY) {
            int[] known = BLOCK_TRANSITIONS.lookup(beforeHash, afterHash);
            if (known != null) return known;
        }
        int difference = after.blocks.size() - before.blocks.size();
        int[] mapping = null;
        if (mode == EditMode.NORMAL) {
            if (difference == 0) mapping = BlockTransitions.identity(before.blocks.size());
            else if (difference == 1 && (selection >= 0 || selection == -3))
                mapping = BlockTransitions.insertion(before.blocks.size(), selection == -3 ? before.blocks.size() : selection);
            else if (difference == -1 && selection == -1 && selectedBefore >= 0 && selectedBefore < before.blocks.size())
                mapping = BlockTransitions.deletion(before.blocks.size(), selectedBefore);
            if (mapping != null) for (int index = 0; index < mapping.length; index++) {
                int target = mapping[index];
                if (target >= 0 && !sameBlockIdentity(before.blocks.get(index), after.blocks.get(target))) { mapping = null; break; }
            }
        }
        if (mapping == null) mapping = BlockTransitions.inferUnique(blockSignatures(before), blockSignatures(after));
        // Raw source edits never borrow a similarly shaped historical operation.
        // Conflicting mappings for identical source hashes invalidate both directions.
        if (mode == EditMode.NORMAL || mode == EditMode.SOURCE)
            BLOCK_TRANSITIONS.record(beforeHash, afterHash, mapping, after.blocks.size());
        return mapping;
    }

    private static List<String> blockSignatures(Patch value) {
        List<String> signatures = new ArrayList<>();
        for (Block block : value.blocks) signatures.add(blockSignature(block));
        return signatures;
    }

    private static String blockSignature(Block block) {
        StringBuilder complete = new StringBuilder();
        appendSignature(complete, block.name);
        complete.append(block.controller ? '1' : '0').append(block.disabled ? '1' : '0');
        for (Parameter parameter : block.parameters) {
            appendSignature(complete, parameter.name); appendSignature(complete, parameter.value);
            complete.append(parameter.disabled ? '1' : '0');
        }
        return PatchTextCodec.sha256(complete.toString());
    }

    private static void appendSignature(StringBuilder output, String value) {
        output.append(value.length()).append(':').append(value);
    }

    private void rebaseDrafts(Patch before, Patch after, int[] mapping) {
        Map<Integer, Integer> indices = new HashMap<>();
        if (mapping != null && mapping.length == before.blocks.size())
            for (int index = 0; index < mapping.length; index++) {
                int target = mapping[index];
                if (target >= 0 && target < after.blocks.size()
                        && sameBlockIdentity(before.blocks.get(index), after.blocks.get(target)))
                    indices.put(before.blocks.get(index).index, target);
            }
        LinkedHashMap<String, Draft> rebased = new LinkedHashMap<>();
        for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) {
            int separator = entry.getKey().indexOf(':');
            boolean preserved = false;
            try {
                Integer index = separator < 0 ? null : indices.get(Integer.parseInt(entry.getKey().substring(0, separator)));
                if (index != null) {
                    String suffix = entry.getKey().substring(separator);
                    String parameterName = suffix.substring(suffix.indexOf(':', 1) + 1);
                    Parameter parameter = effectiveParameter(after.blocks.get(index), parameterName);
                    Draft draft = entry.getValue();
                    if (parameter == null || !parameter.value.equals(draft.value)
                            || (draft.cells != null && !draft.cells.equals(displayCells(after.blocks.get(index), parameter))))
                        rebased.put(index + suffix, new Draft(parameter == null ? "" : parameter.value, draft.value, draft.cells));
                    preserved = true;
                }
            } catch (NumberFormatException ignored) { /* Keep malformed recovery metadata separately as well. */ }
            if (!preserved) orphanDrafts.add(new OrphanDraft(entry.getKey(), entry.getValue()));
        }
        editDrafts.clear(); editDrafts.putAll(rebased);
        HashSet<Integer> folded = new HashSet<>();
        for (int index : foldedBlocks) if (indices.containsKey(index)) folded.add(indices.get(index));
        foldedBlocks.clear(); foldedBlocks.addAll(folded);
        selectedBlock = indices.containsKey(selectedBlock) ? indices.get(selectedBlock) : -1;
        dialogBlock = indices.containsKey(dialogBlock) ? indices.get(dialogBlock) : -1;
        Map<Integer, Integer> pages = new HashMap<>();
        for (Map.Entry<Integer, Integer> page : parameterPages.entrySet())
            if (indices.containsKey(page.getKey())) pages.put(indices.get(page.getKey()), page.getValue());
        parameterPages.clear(); parameterPages.putAll(pages);
        if (pendingConnection != null) {
            Integer target = indices.get(pendingConnection.block);
            if (target == null) pendingConnection = null;
            else pendingConnection = pendingConnection.atBlock(target);
        }
        if (cellDialog != null) {
            Integer target = indices.get(cellDialog.point.block);
            Integer from = cellDialog.from == null ? null : indices.get(cellDialog.from.block);
            if (target == null || (cellDialog.from != null && from == null)) cellDialog = null;
            else {
                cellDialog.point = cellDialog.point.atBlock(target);
                if (cellDialog.from != null) cellDialog.from = cellDialog.from.atBlock(from);
            }
        }
        if (annotationDraft != null && annotationDraft.block >= 0 && !annotationDraft.orphan) {
            Integer target = indices.get(annotationDraft.block);
            if (target == null) annotationDraft.orphan = true;
            else {
                annotationDraft.block = target;
                if ("label".equals(annotationDraft.kind)) {
                    RackModule module = null;
                    for (RackModule candidate : after.rackModules) if (candidate.block == target) { module = candidate; break; }
                    if (module == null || module.controller != annotationDraft.controller) annotationDraft.orphan = true;
                }
            }
        }
    }

    private static boolean sameBlockIdentity(Block first, Block second) {
        return first.controller == second.controller && first.name.equals(second.name);
    }

    private static Parameter effectiveParameter(Block block, String name) {
        return block.effective.get(name);
    }

    private void bindDraft(EditText value, Block block, Parameter parameter) {
        String key = block.index + ":" + block.name + ":" + parameter.name;
        addDraftBudgetFilter(value, () -> key, () -> parameter.value);
        Draft draft = editDrafts.get(key);
        value.setText(draft == null ? parameter.value : draft.value);
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                String typed = text.toString();
                if (typed.equals(parameter.value)) editDrafts.remove(key);
                else editDrafts.put(key, new Draft(parameter.value, typed));
                requestUiSave();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private interface TextSupplier { String get(); }

    private static String draftKey(Block block, Parameter parameter) {
        return block.index + ":" + block.name + ":" + parameter.name;
    }

    private ForgeCells displayCells(Block block, Parameter parameter) {
        AppliedCells remembered = appliedCells.get(draftKey(block, parameter));
        return remembered != null && remembered.value.equals(parameter.value) ? remembered.cells : parameter.cells;
    }

    private boolean canRememberCells(Map<String, ForgeCells> replacements) {
        long characters = 0;
        int count = 0;
        for (Map.Entry<String, AppliedCells> entry : appliedCells.entrySet()) if (!replacements.containsKey(entry.getKey())) {
            characters += entry.getValue().characters(entry.getKey()); count++;
        }
        for (Map.Entry<String, ForgeCells> entry : replacements.entrySet()) if (entry.getValue().hasEmptyBaseRoles()) {
            ForgeCells cells = entry.getValue();
            String value = cells.draftExpression();
            if (value.length() > MAX_EDITOR_CHARACTERS) {
                Toast.makeText(this, "This expression would exceed the editor limit. Choose a shorter name or use the source editor.", Toast.LENGTH_LONG).show();
                return false;
            }
            // Native composition may insert operator spaces, e.g. "1/2" -> "1 / 2".
            // Two binary operators require at most six additional characters.
            int canonicalLength = Math.max(value.length(), cells.characters() + 8);
            characters += (long) entry.getKey().length() + cells.characters() + canonicalLength; count++;
        }
        if (count <= MAX_APPLIED_CELLS && characters <= MAX_APPLIED_CHARACTERS) return true;
        Toast.makeText(this, "The limit for remembered cells has been reached. Enter a base value or use the raw editor before continuing.", Toast.LENGTH_LONG).show();
        return false;
    }

    private void rememberCells(ConnectionPoint point, ForgeCells cells) {
        String key = pointKey(point);
        Parameter parameter = pointParameter(point);
        if (parameter != null && "input".equals(parameter.direction) && cells.hasEmptyBaseRoles())
            appliedCells.put(key, new AppliedCells(parameter.value, cells));
        else appliedCells.remove(key);
    }

    private void rebaseAppliedCells(Patch before, Patch after, int[] mapping, EditMode mode, boolean unchanged) {
        if (mode != EditMode.NORMAL) {
            if (!unchanged || mode == EditMode.RESET) appliedCells.clear();
            return;
        }
        LinkedHashMap<String, AppliedCells> rebased = new LinkedHashMap<>();
        for (Map.Entry<String, AppliedCells> entry : appliedCells.entrySet()) {
            String[] key = entry.getKey().split(":", 3);
            if (key.length != 3 || mapping == null) continue;
            int oldIndex;
            try { oldIndex = Integer.parseInt(key[0]); } catch (NumberFormatException ignored) { continue; }
            if (oldIndex < 0 || oldIndex >= mapping.length || oldIndex >= before.blocks.size()) continue;
            int index = mapping[oldIndex];
            if (index < 0 || index >= after.blocks.size() || !sameBlockIdentity(before.blocks.get(oldIndex), after.blocks.get(index))) continue;
            Parameter parameter = effectiveParameter(after.blocks.get(index), key[2]);
            if (parameter != null && parameter.value.equals(entry.getValue().value))
                rebased.put(index + ":" + key[1] + ":" + key[2], entry.getValue());
        }
        appliedCells.clear(); appliedCells.putAll(rebased);
    }

    /** The three cells retain their own text while a draft is incomplete. */
    private void renderValueCells(LinearLayout row, Block block, Parameter parameter) {
        String key = draftKey(block, parameter);
        Draft draft = editDrafts.get(key);
        ForgeCells baseline = displayCells(block, parameter);
        boolean separate = "input".equals(parameter.direction) && parameter.editable
                && (draft == null || draft.cells != null);
        ForgeCells initial = separate ? (draft == null ? baseline : draft.cells) : null;
        EditText[] fields = new EditText[separate ? 3 : 1];
        int[] focusedColumn = {0};
        LinearLayout cells = horizontal();
        cells.setGravity(Gravity.CENTER_VERTICAL);
        // Narrow screens scroll the cells instead of shrinking touch targets below 48dp.
        if (getResources().getConfiguration().screenWidthDp < 640 && separate) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setFillViewport(false);
            scroll.addView(cells, new ViewGroup.LayoutParams(dp(352), dp(48)));
            row.addView(scroll, new LinearLayout.LayoutParams(0, dp(48), 1));
        } else row.addView(cells, new LinearLayout.LayoutParams(0, dp(48), 1));
        for (int column = 0; column < fields.length; column++) {
            if (column > 0) {
                TextView operation = label(column == 1 ? "×" : "+", 16, MUTED);
                operation.setGravity(Gravity.CENTER);
                cells.addView(operation, new LinearLayout.LayoutParams(dp(20), dp(48)));
            }
            final int selectedColumn = column;
            EditText field = edit(separate ? (column == 0 ? "A" : column == 1 ? "1" : "0") : "Value or cable (_name)");
            fields[column] = field;
            field.setTypeface(Typeface.MONOSPACE);
            field.setSingleLine(true);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setContentDescription(column == 0 ? "Value of parameter " + parameter.name
                    : getString(column == 1 ? R.string.forge_factor_description : R.string.forge_offset_description, parameter.name));
            field.setEnabled(loaded && operations == 0 && patch.analysisComplete && !recoveryRequired);
            ConnectionPoint point = new ConnectionPoint(block.index, block.name, parameter.name, column, parameter.direction);
            field.setTag(point);
            if (!parameter.editable) field.setTooltipText(getString(R.string.forge_expression_complex));
            field.setOnClickListener(view -> {
                focusedColumn[0] = selectedColumn;
                if (pendingConnection != null) finishConnection(point);
            });
            ((CableEditText) field).setConnectionMode(() -> pendingConnection != null);
            field.setOnFocusChangeListener((view, focused) -> {
                if (focused) { focusedColumn[0] = selectedColumn; selectedBlock = block.index; }
            });
            field.setOnLongClickListener(view -> { focusedColumn[0] = selectedColumn; showCellMenu(view, point); return true; });
            if (separate) field.setText(initial.cell(column));
            else bindDraft(field, block, parameter);
            cells.addView(field, new LinearLayout.LayoutParams(0, dp(48), column == 0 ? 2 : 1));
            updatePlug(field, point, parameter.disabled || block.disabled);
        }
        if (separate) for (int column = 0; column < fields.length; column++) {
            final int selectedColumn = column;
            EditText field = fields[column];
            InputFilter[] filters = java.util.Arrays.copyOf(field.getFilters(), field.getFilters().length + 1);
            filters[filters.length - 1] = (inserted, start, end, destination, replaceStart, replaceEnd) -> {
                ForgeCells candidate = cellsFrom(fields).with(selectedColumn,
                        new StringBuilder().append(destination, 0, replaceStart).append(inserted, start, end)
                                .append(destination, replaceEnd, destination.length()).toString());
                Draft replacement = new Draft(parameter.value, candidate.draftExpression(), candidate);
                long characters = candidate.equals(baseline) ? 0 : replacement.characters(key);
                for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) if (!entry.getKey().equals(key))
                    characters += entry.getValue().characters(entry.getKey());
                for (OrphanDraft orphan : orphanDrafts) characters += orphan.draft.characters(orphan.key);
                if (candidate.draftExpression().length() <= MAX_EDITOR_CHARACTERS && characters <= MAX_DRAFT_CHARACTERS) return null;
                field.setError("The expression or draft limit has been reached. Apply the values already entered.");
                return destination.subSequence(replaceStart, replaceEnd);
            };
            field.setFilters(filters);
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    ForgeCells edited = cellsFrom(fields);
                    if (edited.equals(baseline)) editDrafts.remove(key);
                    else editDrafts.put(key, new Draft(parameter.value, edited.draftExpression(), edited));
                    updatePlug(field, (ConnectionPoint) field.getTag(), parameter.disabled || block.disabled);
                    requestUiSave();
                }
                @Override public void afterTextChanged(Editable text) {}
            });
        } else fields[0].addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                updatePlug(fields[0], (ConnectionPoint) fields[0].getTag(), parameter.disabled || block.disabled);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        Button port = action("⋮", getString(R.string.forge_cable_menu, parameter.name), false);
        port.setTextColor(CYAN);
        port.setOnClickListener(view -> {
            ConnectionPoint point = new ConnectionPoint(block.index, block.name, parameter.name, focusedColumn[0], parameter.direction);
            if (pendingConnection != null) finishConnection(point); else showCellMenu(view, point);
        });
        port.setOnLongClickListener(view -> {
            showCellMenu(view, new ConnectionPoint(block.index, block.name, parameter.name, focusedColumn[0], parameter.direction)); return true;
        });
        row.addView(port, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button apply = action("✓", "Apply " + parameter.name, false);
        apply.setTextColor(TEXT);
        apply.setEnabled(loaded && operations == 0 && patch.analysisComplete && !recoveryRequired);
        Runnable commit = () -> {
            if (!currentPoint(new ConnectionPoint(block.index, block.name, parameter.name, 0, parameter.direction))) return;
            if (separate) {
                ForgeCells edited = cellsFrom(fields);
                if (edited.equals(baseline) || !canRememberCells(Collections.singletonMap(key, edited))) return;
                mutate("Updating " + parameter.name + "…", -2,
                        () -> NativeBridge.setParameterExpression(block.index, parameter.name, edited.base, edited.factor, edited.offset),
                        () -> {
                            editDrafts.remove(key);
                            rememberCells(new ConnectionPoint(block.index, block.name, parameter.name, 0, parameter.direction), edited);
                        });
            } else {
                String value = fields[0].getText().toString();
                if (value.equals(parameter.value)) return;
                mutate("Updating " + parameter.name + "…", -2,
                        () -> NativeBridge.setParameter(block.index, parameter.name, value), () -> { editDrafts.remove(key); appliedCells.remove(key); });
            }
        };
        apply.setOnClickListener(view -> commit.run());
        for (EditText field : fields) field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { commit.run(); return true; } return false;
        });
        row.addView(apply, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private static ForgeCells cellsFrom(EditText[] fields) {
        return new ForgeCells(fields[0].getText().toString(), fields[1].getText().toString(), fields[2].getText().toString());
    }

    private void updatePlug(EditText field, ConnectionPoint point, boolean disabled) {
        String cable = ForgeCells.cableName(field.getText().toString());
        field.setCompoundDrawables(null, null, null, null);
        field.setTextColor(cable.isEmpty() ? TEXT : cableColor(cable));
        field.setBackground(background(0xff282828, !cable.isEmpty() && cable.equals(followedCable) ? CYAN : BORDER, 0));
        if (cable.isEmpty()) return;
        String[] colors = {"black", "blue", "brown", "cyan", "darkgreen", "green", "grey", "magenta", "orange", "red", "white", "yellow"};
        int color = (cable.hashCode() & 0x7fffffff) % (colors.length * 9);
        String asset = "forge/cables/" + (disabled ? "ghost" : colors[color / 9] + "-" + color % 9) + ".png";
        Bitmap cached = IMAGES.get(asset);
        if (cached != null) { setPlug(field, cached, "output".equals(point.direction)); return; }
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            Bitmap bitmap = loadAssetBitmap(application, asset);
            if (bitmap != null) deliver(() -> {
                if (point.equals(field.getTag()) && cable.equals(ForgeCells.cableName(field.getText().toString())))
                    setPlug(field, bitmap, "output".equals(point.direction));
            });
        });
    }

    private void setPlug(EditText field, Bitmap bitmap, boolean output) {
        PlugDrawable plug = new PlugDrawable(bitmap, output);
        plug.setBounds(0, 0, dp(34), dp(8));
        field.setCompoundDrawablePadding(dp(4));
        field.setCompoundDrawables(plug, null, null, null);
    }

    private static int cableColor(String name) {
        int[] colors = {0xffbbbbbb, 0xff72bfff, 0xfff0be93, 0xff70e6e6, 0xff8cd98c, 0xffbef08a,
                0xffcccccc, 0xffff8aff, 0xffffbc69, 0xffff8e87, 0xffeeeeee, 0xffffe47f};
        return colors[((name.hashCode() & 0x7fffffff) % (colors.length * 9)) / 9];
    }

    private boolean currentPoint(ConnectionPoint point) {
        if (point == null || point.block < 0 || point.block >= patch.blocks.size()) return false;
        Block block = patch.blocks.get(point.block);
        return block.name.equals(point.blockName) && effectiveParameter(block, point.parameter) != null;
    }

    private Parameter pointParameter(ConnectionPoint point) {
        return currentPoint(point) ? effectiveParameter(patch.blocks.get(point.block), point.parameter) : null;
    }

    private String pointKey(ConnectionPoint point) { return point.block + ":" + point.blockName + ":" + point.parameter; }

    private String pointValue(ConnectionPoint point) {
        Draft draft = editDrafts.get(pointKey(point));
        Parameter parameter = pointParameter(point);
        return draft != null ? draft.value : parameter == null ? "" : parameter.value;
    }

    private ForgeCells pointCells(ConnectionPoint point) {
        Parameter parameter = pointParameter(point);
        if (parameter == null) return null;
        Draft draft = editDrafts.get(pointKey(point));
        if (draft != null) return draft.cells;
        return parameter.editable ? displayCells(patch.blocks.get(point.block), parameter) : null;
    }

    private String pointAtom(ConnectionPoint point) {
        ForgeCells cells = pointCells(point);
        return cells == null ? (point.column == 0 ? pointValue(point) : "") : cells.cell(point.column);
    }

    private boolean connectable(ConnectionPoint point) {
        Parameter parameter = pointParameter(point);
        if (parameter == null || !parameter.editable) return false;
        if ("output".equals(parameter.direction)) return point.column == 0;
        return "input".equals(parameter.direction) && pointCells(point) != null;
    }

    private boolean cableEditingReady(ConnectionPoint point) {
        if (!loaded || operations != 0 || !patch.analysisComplete || recoveryRequired || !currentPoint(point)) return false;
        if (sourceDialog != null || (sourceDraft != null && sourceSnapshot.equals(sourceDraftBase))) {
            Toast.makeText(this, "Apply or discard the source draft before editing cables.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void showCellMenu(View anchor, ConnectionPoint point) {
        if (!loaded || operations != 0 || !currentPoint(point)) return;
        String cable = ForgeCells.cableName(pointAtom(point));
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.forge_connection_start).setEnabled(connectable(point));
        if (pendingConnection != null) {
            menu.getMenu().add(0, 2, 1, R.string.forge_connection_finish).setEnabled(connectable(point));
            menu.getMenu().add(0, 3, 2, R.string.forge_connection_abort);
        }
        if (!cable.isEmpty()) menu.getMenu().add(0, 4, 3, R.string.forge_connection_follow);
        menu.getMenu().add(0, 5, 4, R.string.forge_cable_choose).setEnabled(connectable(point));
        menu.getMenu().add(0, 6, 5, R.string.forge_cable_create).setEnabled(connectable(point));
        if (!cable.isEmpty()) menu.getMenu().add(0, 7, 6, R.string.forge_cable_rename).setEnabled(cable.length() <= 256);
        menu.getMenu().add(0, 8, 7, R.string.forge_cell_clear);
        menu.getMenu().add(0, 9, 8, R.string.forge_expression_raw);
        menu.setOnMenuItemClickListener(item -> {
            if (!currentPoint(point)) return true;
            switch (item.getItemId()) {
                case 1:
                    if (cableEditingReady(point)) { pendingConnection = point; updateDiagnostics(); requestUiSave(); }
                    break;
                case 2: finishConnection(point); break;
                case 3: abortConnection(); break;
                case 4: followCable(point, cable); break;
                case 5: chooseCable(point); break;
                case 6: chooseCableName("Create a cable", "", point, null, "create"); break;
                case 7: renameCable(point, cable); break;
                case 8: applyCell(point, ""); break;
                case 9: editRawValue(point); break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    private void abortConnection() { pendingConnection = null; updateDiagnostics(); requestUiSave(); }

    private void applyCell(ConnectionPoint point, String value) {
        if (!cableEditingReady(point)) return;
        Parameter parameter = pointParameter(point);
        String key = pointKey(point);
        ForgeCells cells = pointCells(point);
        String previous = pointAtom(point), previousCable = ForgeCells.cableName(previous);
        if (previous.equals(value) || (!previousCable.isEmpty() && previousCable.equals(ForgeCells.cableName(value)))) return;
        if ("input".equals(parameter.direction) && cells != null) {
            ForgeCells edited = cells.with(point.column, value);
            if (!canRememberCells(Collections.singletonMap(key, edited))) return;
            mutate("Updating " + point.parameter + "…", -2,
                    () -> NativeBridge.setParameterExpression(point.block, point.parameter, edited.base, edited.factor, edited.offset),
                    () -> { editDrafts.remove(key); rememberCells(point, edited); });
        } else if (point.column == 0) mutate("Updating " + point.parameter + "…", -2,
                () -> NativeBridge.setParameter(point.block, point.parameter, value), () -> { editDrafts.remove(key); appliedCells.remove(key); });
    }

    private void finishConnection(ConnectionPoint destination) {
        ConnectionPoint source = pendingConnection;
        if (!cableEditingReady(destination) || source == null) return;
        if (!currentPoint(source) || !connectable(source) || !connectable(destination)
                || source.direction.equals(destination.direction) || source.equals(destination)) {
            Toast.makeText(this, R.string.forge_connection_incompatible, Toast.LENGTH_LONG).show(); return;
        }
        String sourceCable = ForgeCells.cableName(pointAtom(source));
        String destinationCable = ForgeCells.cableName(pointAtom(destination));
        String chosen = !sourceCable.isEmpty() ? sourceCable : destinationCable;
        if (chosen.isEmpty()) chooseCableName("New cable name", "", destination, source, "connect");
        else connect(source, destination, chosen);
    }

    private void connect(ConnectionPoint source, ConnectionPoint destination, String cable) {
        if (source == null || !cableEditingReady(destination) || !source.equals(pendingConnection) || !currentPoint(source)) return;
        ForgeCells fromCells = "output".equals(source.direction) ? new ForgeCells(pointValue(source), "", "") : pointCells(source);
        ForgeCells toCells = "output".equals(destination.direction) ? new ForgeCells(pointValue(destination), "", "") : pointCells(destination);
        if (fromCells == null || toCells == null) return;
        ForgeCells fromConnected = fromCells.with(source.column, cable), toConnected = toCells.with(destination.column, cable);
        Map<String, ForgeCells> remembered = new HashMap<>();
        remembered.put(pointKey(source), fromConnected); remembered.put(pointKey(destination), toConnected);
        if (!canRememberCells(remembered)) return;
        mutate("Connecting " + cable + "…", -2,
                () -> NativeBridge.connectParameterExpressions(source.block, source.parameter, source.column,
                        fromCells.base, fromCells.factor, fromCells.offset, destination.block, destination.parameter,
                        destination.column, toCells.base, toCells.factor, toCells.offset, cable), () -> {
                    editDrafts.remove(pointKey(source)); editDrafts.remove(pointKey(destination));
                    rememberCells(source, fromConnected); rememberCells(destination, toConnected);
                    pendingConnection = null; followedCable = cable;
                });
    }

    private void chooseCableName(String title, String existing, ConnectionPoint point, ConnectionPoint from, String operation) {
        if (!cableEditingReady(point)) return;
        CellDialogState state = cellDialog != null && operation.equals(cellDialog.operation) && point.equals(cellDialog.point)
                ? cellDialog : new CellDialogState(operation, title, existing, point, from);
        cellDialog = state; dialogType = "cable_name";
        String dialogSource = sourceSnapshot;
        EditText name = edit("Cable name");
        name.setSingleLine(true);
        name.setContentDescription("Cable name");
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(255)});
        name.setText(state.name);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { state.name = text.toString(); requestUiSave(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        LinearLayout body = vertical(); body.setPadding(dp(20), dp(12), dp(20), 0); body.addView(name);
        body.addView(label("Spaces become underscores. Other special characters are removed.", 12, MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(body)
                .setNegativeButton("Cancel", null).setPositiveButton("OK", null).create();
        trackDialog(dialog);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!dialogSource.equals(sourceSnapshot)) { dialog.dismiss(); Toast.makeText(this, "The patch has changed. Select the cell again.", Toast.LENGTH_LONG).show(); return; }
            String cleaned = name.getText().toString().trim().replace(' ', '_').replaceAll("[^A-Za-z0-9_]", "").toUpperCase(Locale.ROOT);
            while (cleaned.startsWith("_")) cleaned = cleaned.substring(1);
            if (cleaned.isEmpty() || cleaned.length() > 255) { name.setError("Choose a name with 1 to 255 letters, digits or underscores."); return; }
            dialog.dismiss();
            String cable = "_" + cleaned;
            if ("connect".equals(operation)) connect(from, point, cable);
            else if ("rename".equals(operation)) renameCableTo(point, existing, cable);
            else applyCell(point, cable);
        }));
        dialog.setOnDismissListener(ignored -> clearCellDialog(state));
        dialog.show(); requestUiSave();
    }

    private interface InventoryAction { void accept(JSONObject inventory); }

    private void withCableInventory(InventoryAction action) {
        if (!loaded || operations != 0) return;
        String capturedSource = sourceSnapshot;
        WORKER.execute(() -> {
            try {
                JSONObject inventory = new JSONObject(NativeBridge.cables());
                deliver(() -> {
                    if (operations == 0 && capturedSource.equals(sourceSnapshot)) action.accept(inventory);
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                deliver(() -> Toast.makeText(this, "Cable inventory unavailable: " + errorMessage(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void chooseCable(ConnectionPoint point) {
        if (!cableEditingReady(point)) return;
        withCableInventory(inventory -> {
            JSONArray cables = inventory.optJSONArray("cables");
            if (cables == null || cables.length() == 0) {
                showDialog(new AlertDialog.Builder(this).setTitle(R.string.forge_cable_choose)
                        .setMessage(inventory.optBoolean("analysisComplete", false) ? "No cables available."
                                : "Partial inventory: no cables can be shown in this view. All occurrences are preserved in the source.").setNegativeButton("Cancel", null)
                        .setPositiveButton(R.string.forge_cable_create, (dialog, which) -> chooseCableName("Create a cable", "", point, null, "create")));
                return;
            }
            showCablePicker(point, cables, inventory.optBoolean("analysisComplete", false), 0);
        });
    }

    private void showCablePicker(ConnectionPoint point, JSONArray cables, boolean complete, int page) {
        String dialogSource = sourceSnapshot;
        int first = page * 128, count = Math.min(128, cables.length() - first);
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            JSONObject cable = cables.optJSONObject(first + i);
            if (cable == null) { labels[i] = "?"; continue; }
            int inputs = cable.optInt("inputs"), outputs = cable.optInt("outputs");
            labels[i] = getString(R.string.forge_cable_summary, displayText(cable.optString("name"), 128),
                    getResources().getQuantityString(R.plurals.forge_cable_inputs, inputs, inputs),
                    getResources().getQuantityString(R.plurals.forge_cable_outputs, outputs, outputs));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.forge_cable_choose) + (complete ? "" : " · partial inventory"))
                .setItems(labels, (dialog, which) -> {
                    JSONObject cable = cables.optJSONObject(first + which);
                    if (cable != null && dialogSource.equals(sourceSnapshot)) applyCell(point, cable.optString("name"));
                }).setNegativeButton("Cancel", null);
        if (first + count < cables.length()) builder.setPositiveButton("Next", (dialog, which) -> showCablePicker(point, cables, complete, page + 1));
        if (page > 0) builder.setNeutralButton("Previous", (dialog, which) -> showCablePicker(point, cables, complete, page - 1));
        showDialog(builder);
    }

    private void followCable(ConnectionPoint current, String name) {
        withCableInventory(inventory -> {
            JSONArray cables = inventory.optJSONArray("cables");
            JSONArray occurrences = null;
            if (cables != null) for (int i = 0; i < cables.length(); i++) {
                JSONObject cable = cables.optJSONObject(i);
                if (cable != null && name.equalsIgnoreCase(cable.optString("name"))) { occurrences = cable.optJSONArray("occurrences"); break; }
            }
            List<ConnectionPoint> destinations = new ArrayList<>();
            if (occurrences != null) for (int i = 0; i < occurrences.length(); i++) {
                JSONObject occurrence = occurrences.optJSONObject(i);
                if (occurrence == null) continue;
                int blockIndex = occurrence.optInt("block", -1), column = occurrence.optInt("column", -1);
                if (blockIndex < 0 || blockIndex >= patch.blocks.size() || column < 0 || column > 2) continue;
                Block block = patch.blocks.get(blockIndex);
                ConnectionPoint point = new ConnectionPoint(blockIndex, block.name, occurrence.optString("name"), column, occurrence.optString("direction"));
                Parameter parameter = pointParameter(point);
                // A hidden duplicate source assignment is never presented as the effective port.
                if (parameter != null && parameter.line == occurrence.optInt("line") && ForgeCells.cableName(pointAtom(point)).equals(name))
                    destinations.add(point);
            }
            // Source serialization can move an empty-base factor into column zero.
            // Follow the actual displayed cells, including saved roles and dirty drafts.
            Set<ConnectionPoint> seen = new HashSet<>(destinations);
            Set<String> displayedKeys = new HashSet<>(appliedCells.keySet());
            displayedKeys.addAll(editDrafts.keySet());
            for (String displayedKey : displayedKeys) {
                String[] key = displayedKey.split(":", 3);
                if (key.length != 3) continue;
                int blockIndex;
                try { blockIndex = Integer.parseInt(key[0]); } catch (NumberFormatException ignored) { continue; }
                if (blockIndex < 0 || blockIndex >= patch.blocks.size()) continue;
                Block block = patch.blocks.get(blockIndex);
                Parameter parameter = effectiveParameter(block, key[2]);
                if (parameter == null || !block.name.equals(key[1])) continue;
                for (int column = 0; column < ("input".equals(parameter.direction) ? 3 : 1); column++) {
                    ConnectionPoint point = new ConnectionPoint(blockIndex, block.name, parameter.name, column, parameter.direction);
                    if (connectable(point) && ForgeCells.cableName(pointAtom(point)).equals(name)
                            && destinations.size() < 8192 && seen.add(point)) destinations.add(point);
                }
            }
            destinations.sort((first, second) -> {
                int order = Integer.compare(first.block, second.block);
                if (order == 0) order = Integer.compare(pointParameter(first).line, pointParameter(second).line);
                return order == 0 ? Integer.compare(first.column, second.column) : order;
            });
            int found = destinations.indexOf(current);
            ConnectionPoint next = destinations.isEmpty() ? null : destinations.get((found + 1) % destinations.size());
            if (next == null) { Toast.makeText(this, "No other displayable cell was found for this cable. Check the source.", Toast.LENGTH_LONG).show(); return; }
            if (!inventory.optBoolean("analysisComplete", false)) Toast.makeText(this, "Partial inventory: only analyzed occurrences can be followed.", Toast.LENGTH_LONG).show();
            navigateToCell(next, name);
        });
    }

    private void navigateToCell(ConnectionPoint point, String cable) {
        if (!currentPoint(point)) return;
        for (int i = 0; i < sections.size(); i++) for (Block block : sections.get(i).blocks)
            if (block.index == point.block) selectedSection = i;
        Block block = patch.blocks.get(point.block);
        LinkedHashMap<String, Parameter> effective = new LinkedHashMap<>();
        for (Parameter parameter : block.parameters) {
            Parameter previous = effective.get(parameter.name);
            if (previous == null || !parameter.disabled || previous.disabled) effective.put(parameter.name, parameter);
        }
        int ordinal = 0;
        for (String name : effective.keySet()) { if (name.equals(point.parameter)) break; ordinal++; }
        parameterPages.put(point.block, ordinal / PARAMETER_PAGE_SIZE);
        foldedBlocks.remove(point.block); selectedBlock = point.block; followedCable = cable;
        updateVisibleBlocks(); cardGeneration++; sectionAdapter.notifyDataSetChanged(); circuitCardAdapter.notifyDataSetChanged();
        for (int i = 0; i < visibleBlocks.size(); i++) if (visibleBlocks.get(i).index == point.block) {
            circuitList.setSelectionFromTop(i, 0); break;
        }
        circuitList.post(() -> {
            View field = findCell(circuitList, point);
            if (field != null) { android.graphics.Rect rectangle = new android.graphics.Rect(); field.getDrawingRect(rectangle); field.requestRectangleOnScreen(rectangle, false); }
        });
        requestUiSave();
    }

    private static View findCell(View view, ConnectionPoint point) {
        if (point.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findCell(group.getChildAt(i), point); if (found != null) return found; }
        }
        return null;
    }

    private void editRawValue(ConnectionPoint point) {
        if (!cableEditingReady(point)) return;
        CellDialogState state = new CellDialogState("raw", "", "", point, null);
        cellDialog = state; dialogType = "raw_value";
        EditText value = edit("Full expression");
        value.setContentDescription("Raw value of parameter " + point.parameter);
        value.setText(pointValue(point));
        String key = pointKey(point);
        addDraftBudgetFilter(value, () -> key, () -> pointParameter(point) == null ? "" : pointParameter(point).value);
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                Parameter parameter = pointParameter(point);
                if (parameter == null) return;
                if (parameter.value.equals(text.toString())) editDrafts.remove(key);
                else editDrafts.put(key, new Draft(parameter.value, text.toString()));
                requestUiSave();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        LinearLayout body = vertical(); body.setPadding(dp(20), dp(12), dp(20), 0); body.addView(value);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.forge_expression_raw).setView(body)
                .setNegativeButton("Close", null).setPositiveButton("Apply", null).create();
        trackDialog(dialog);
        dialog.setOnDismissListener(ignored -> {
            clearCellDialog(state);
            if (!destroyed) { cardGeneration++; circuitCardAdapter.notifyDataSetChanged(); }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!cableEditingReady(point)) return;
            String typed = value.getText().toString();
            if (typed.equals(pointParameter(point).value)) { dialog.dismiss(); return; }
            dialog.dismiss(); mutate("Updating " + point.parameter + "…", -2,
                    () -> NativeBridge.setParameter(point.block, point.parameter, typed), () -> { editDrafts.remove(key); appliedCells.remove(key); });
        }));
        dialog.show(); requestUiSave();
    }

    private void clearCellDialog(CellDialogState state) {
        if (!destroyed && cellDialog == state) {
            cellDialog = null;
            if ("cable_name".equals(dialogType) || "raw_value".equals(dialogType)) dialogType = "";
            requestUiSave();
        }
    }

    private void renameCable(ConnectionPoint point, String oldName) {
        chooseCableName("Rename cable " + oldName, oldName, point, null, "rename");
    }

    private void renameCableTo(ConnectionPoint point, String oldName, String newName) {
        if (newName.equals(oldName) || !cableEditingReady(point)) return;
        String capturedSource = sourceSnapshot;
        withCableInventory(inventory -> {
            if (!inventory.optBoolean("analysisComplete", false)) {
                showDialog(new AlertDialog.Builder(this).setTitle("Partial cable inventory")
                        .setMessage("Cable renaming is unavailable in this view because not all occurrences can be shown. The complete patch is preserved in the source.")
                        .setPositiveButton("Close", null)); return;
            }
            JSONArray cables = inventory.optJSONArray("cables");
            boolean oldPresent = false, newPresent = false;
            if (cables != null) for (int i = 0; i < cables.length(); i++) {
                JSONObject cable = cables.optJSONObject(i);
                if (cable == null) continue;
                if (oldName.equalsIgnoreCase(cable.optString("name"))) oldPresent = true;
                if (newName.equalsIgnoreCase(cable.optString("name"))) newPresent = true;
            }
            final boolean renameSource = oldPresent;
            if (newPresent) showDialog(new AlertDialog.Builder(this).setTitle("Merge cables?")
                    .setMessage(newName + " already exists. All occurrences of " + oldName + " will be connected to " + newName
                            + ". This merge may connect multiple outputs. Check the diagnostics afterwards.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Merge", (dialog, which) -> {
                        if (capturedSource.equals(sourceSnapshot)) applyCableRename(point, oldName, newName, renameSource, true);
                    }));
            else applyCableRename(point, oldName, newName, renameSource, false);
        });
    }

    private void applyCableRename(ConnectionPoint point, String oldName, String newName, boolean renameSource, boolean merge) {
        if (!cableEditingReady(point)) return;
        LinkedHashMap<String, Draft> changed = new LinkedHashMap<>();
        LinkedHashMap<String, ForgeCells> remembered = new LinkedHashMap<>();
        for (Map.Entry<String, AppliedCells> entry : appliedCells.entrySet()) {
            ForgeCells cells = entry.getValue().cells;
            remembered.put(entry.getKey(), new ForgeCells(ForgeCells.renameCableTokens(cells.base, oldName, newName),
                    ForgeCells.renameCableTokens(cells.factor, oldName, newName), ForgeCells.renameCableTokens(cells.offset, oldName, newName)));
        }
        if (!canRememberCells(remembered)) return;
        long characters = 0;
        for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) {
            Draft draft = entry.getValue();
            String renamedBase = ForgeCells.renameCableTokens(draft.base, oldName, newName);
            ForgeCells cells = draft.cells == null ? null : new ForgeCells(
                    ForgeCells.renameCableTokens(draft.cells.base, oldName, newName),
                    ForgeCells.renameCableTokens(draft.cells.factor, oldName, newName),
                    ForgeCells.renameCableTokens(draft.cells.offset, oldName, newName));
            String value = cells == null ? ForgeCells.renameCableTokens(draft.value, oldName, newName) : cells.draftExpression();
            if (value.length() > MAX_EDITOR_CHARACTERS || renamedBase.length() > MAX_EDITOR_CHARACTERS) {
                Toast.makeText(this, "This name would exceed the limit of an expression being edited. Choose a shorter name or apply the draft.", Toast.LENGTH_LONG).show(); return;
            }
            Draft renamed = new Draft(renamedBase, value, cells);
            changed.put(entry.getKey(), renamed); characters += renamed.characters(entry.getKey());
        }
        for (OrphanDraft orphan : orphanDrafts) characters += orphan.draft.characters(orphan.key);
        if (characters > MAX_DRAFT_CHARACTERS) { Toast.makeText(this, "This name would exceed the draft limit. Choose a shorter name.", Toast.LENGTH_LONG).show(); return; }
        mutate("Renaming " + oldName + "…", -2,
                () -> renameSource ? NativeBridge.renameCableWithMerge(oldName, newName, merge) : NativeBridge.state(), () -> {
                    for (Map.Entry<String, ForgeCells> entry : remembered.entrySet()) {
                        String[] parts = entry.getKey().split(":", 3);
                        if (parts.length != 3) continue;
                        int block;
                        try { block = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) { continue; }
                        if (block < 0 || block >= patch.blocks.size()) continue;
                        Parameter parameter = effectiveParameter(patch.blocks.get(block), parts[2]);
                        if (parameter != null) rememberCells(new ConnectionPoint(block, parts[1], parts[2], 0, parameter.direction), entry.getValue());
                    }
                    editDrafts.clear();
                    for (Map.Entry<String, Draft> entry : changed.entrySet()) {
                        String[] parts = entry.getKey().split(":", 3);
                        if (parts.length != 3) continue;
                        int block;
                        try { block = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) { continue; }
                        Parameter parameter = block < 0 || block >= patch.blocks.size() ? null : effectiveParameter(patch.blocks.get(block), parts[2]);
                        Draft draft = entry.getValue();
                        if (parameter == null || !parameter.value.equals(draft.value) || (draft.cells != null && !draft.cells.equals(displayCells(patch.blocks.get(block), parameter))))
                            editDrafts.put(entry.getKey(), new Draft(parameter == null ? "" : parameter.value, draft.value, draft.cells));
                    }
                    if (oldName.equals(followedCable)) followedCable = newName;
                });
    }

    private void addDraftBudgetFilter(EditText field, TextSupplier key, TextSupplier base) {
        InputFilter[] existing = field.getFilters();
        InputFilter[] filters = java.util.Arrays.copyOf(existing, existing.length + 1);
        filters[existing.length] = (inserted, start, end, destination, replaceStart, replaceEnd) -> {
            String candidate = new StringBuilder().append(destination, 0, replaceStart).append(inserted, start, end)
                    .append(destination, replaceEnd, destination.length()).toString();
            String keyText = key.get(), baseText = base.get();
            long characters = candidate.equals(baseText) ? 0 : (long) keyText.length() + baseText.length() + candidate.length();
            for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) if (!entry.getKey().equals(keyText))
                characters += entry.getValue().characters(entry.getKey());
            for (OrphanDraft orphan : orphanDrafts)
                characters += orphan.draft.characters(orphan.key);
            if (characters <= MAX_DRAFT_CHARACTERS) return null;
            field.setError("The draft limit has been reached. Apply the values already entered before continuing.");
            return destination.subSequence(replaceStart, replaceEnd);
        };
        field.setFilters(filters);
    }

    private int parameterColor(Definition definition, boolean disabled) {
        if (disabled) return MUTED;
        if (definition == null) return TEXT;
        return "output".equalsIgnoreCase(definition.direction) ? RED : CYAN;
    }

    private void showBlockMenu(View anchor, Block block) {
        if (!loaded || operations != 0) return;
        selectedBlock = block.index;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Add a parameter…");
        menu.getMenu().add(0, 2, 1, "Circuit information…");
        menu.getMenu().add(0, 3, 2, "Edit source…");
        menu.getMenu().add(0, 4, 3, "Delete this block…");
        menu.getMenu().add(0, 5, 2, R.string.mobile_comment_edit);
        menu.getMenu().findItem(1).setEnabled(patch.analysisComplete);
        menu.getMenu().findItem(4).setEnabled(patch.analysisComplete);
        menu.getMenu().findItem(5).setEnabled(patch.analysisComplete && !recoveryRequired);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showParameterDialog(block); break;
                case 2:
                    Circuit circuit = circuitByName.get(block.name);
                    showDialog(new AlertDialog.Builder(this).setTitle(displayText(block.name.toUpperCase(Locale.ROOT), 256))
                            .setMessage((circuit == null ? "Undocumented block." : circuit.title + "\n\n" + circuit.description)
                                    + "\n\nLine " + block.line + (block.disabled ? " · disabled" : ""))
                            .setPositiveButton("Close", null)); break;
                case 3: editSource(block.line); break;
                case 4: confirmDeleteBlock(block); break;
                case 5: editBlockComment(block); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void confirmDeleteBlock(Block block) {
        if (block.controller && !editDrafts.isEmpty()) {
            showError(getString(R.string.mobile_controller_drafts_title), getString(R.string.mobile_controller_drafts)); return;
        }
        selectedBlock = block.index;
        showDialog(new AlertDialog.Builder(this).setTitle("Delete [" + displayText(block.name, 256) + "]?")
                .setMessage("The block and its parameters will be removed from the patch. You can undo this change.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (dialog, which) -> mutate("Deleting…", -1,
                        () -> NativeBridge.removeCircuit(block.index))));
    }

    private void renderCircuitPreview(Circuit circuit) {
        inspectorTitle.setText(circuit.name.toUpperCase(Locale.ROOT));
        inspectorBody.addView(label(circuit.title, 19, TEXT));
        TextView category = label(circuit.category, 12, COPPER);
        category.setPadding(0, dp(8), 0, dp(10));
        inspectorBody.addView(category);
        if (!circuit.description.isEmpty()) addParagraph(circuit.description);
        Button add = action("+ Add to patch", "Add circuit " + circuit.name, true);
        add.setOnClickListener(v -> addCircuit(circuit));
        inspectorBody.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));
        ImageView illustration = new ImageView(this);
        illustration.setVisibility(View.GONE);
        illustration.setScaleType(ImageView.ScaleType.FIT_CENTER);
        illustration.setContentDescription("Official illustration of circuit " + circuit.name);
        inspectorBody.addView(illustration, new LinearLayout.LayoutParams(-1, dp(160)));
        loadPreviewImage("circuits/" + circuit.name + ".png", illustration, inspectorGeneration);
        if (circuit.manualPage > 0) addParagraph("DROID manual · page " + circuit.manualPage);
        addSection("Available parameters · " + circuit.parameters.size());
        if (circuit.parameters.isEmpty()) addParagraph("This circuit has no documented parameters.");
        int shown = 0;
        for (Definition definition : circuit.parameters) {
            if (++shown > 24) break;
            LinearLayout card = parameterCard();
            card.addView(label(definition.name, 14, parameterColor(definition, false)));
            card.addView(label(parameterMeta(definition), 12, MUTED));
            if (!definition.description.isEmpty()) card.addView(label(definition.description, 13, TEXT));
            inspectorBody.addView(card, spacedParams());
        }
        if (circuit.parameters.size() > 24) addParagraph("The first 24 parameters are shown here. All catalog parameters are available after adding the circuit.");
    }

    private void renderBlockInspector(Block block) {
        inspectorTitle.setText(getString(R.string.forge_block_brackets, displayText(block.name, 256)));
        Circuit circuit = circuitByName.get(block.name);
        if (circuit != null && !circuit.title.isEmpty()) inspectorBody.addView(label(circuit.title, 17, TEXT));
        String details = "Block " + (block.index + 1) + " · line " + block.line;
        if (block.controller) details += " · hardware module";
        if (block.disabled) details += " · disabled";
        addParagraph(details);
        if (block.disabled) addParagraph("This block is disabled in the patch source.");
        // JNI identifies a parameter by name, selecting the last enabled occurrence.
        // Show exactly that occurrence so duplicate source lines cannot misdirect an edit.
        Map<String, Parameter> effective = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Parameter parameter : block.parameters) {
            Parameter previous = effective.get(parameter.name);
            if (previous == null || !parameter.disabled || previous.disabled) effective.put(parameter.name, parameter);
            occurrences.put(parameter.name, occurrences.getOrDefault(parameter.name, 0) + 1);
        }
        int shown = 0;
        for (Parameter parameter : effective.values()) {
            if (++shown > PARAMETER_PAGE_SIZE) break;
            Definition definition = circuit == null ? null : circuit.find(parameter.name);
            LinearLayout card = parameterCard();
            TextView name = label(parameter.name + (parameter.disabled ? " · disabled" : ""), 14, parameterColor(definition, parameter.disabled));
            name.setTypeface(Typeface.MONOSPACE);
            card.addView(name);
            int duplicates = occurrences.get(parameter.name);
            if (duplicates > 1) card.addView(label(duplicates + " occurrences · editing the last active occurrence. Use the source editor for the others.", 12, COPPER));
            if (definition != null && !definition.description.isEmpty()) card.addView(label(definition.description, 12, MUTED));
            if (parameter.value.length() > MAX_EDITOR_CHARACTERS || parameter.name.length() > 256 || block.name.length() > 256) {
                card.addView(label(displayText(parameter.value, 256) + "\nThe full value is preserved. Check the source.", 13, MUTED));
                inspectorBody.addView(card, spacedParams());
                continue;
            }
            LinearLayout editRow = horizontal();
            renderValueCells(editRow, block, parameter);
            card.addView(editRow);
            inspectorBody.addView(card, spacedParams());
        }
        if (block.parameters.isEmpty()) addParagraph("No parameters have been entered in this block.");
        if (effective.size() > PARAMETER_PAGE_SIZE) addParagraph("The first 32 module parameters are shown here. All parameters are preserved in the source.");
        renderAddParameter(block, circuit);
        Button delete = action("Delete this block…", "Delete block " + block.name, true);
        delete.setTextColor(RED);
        delete.setOnClickListener(v -> confirmDeleteBlock(block));
        inspectorBody.addView(delete, spacedParams());
    }

    private void renderAddParameter(Block block, Circuit circuit) {
        List<Definition> available = new ArrayList<>();
        if (circuit != null) {
            for (Definition definition : circuit.parameters) {
                if (!block.hasParameter(definition.name)
                        && (definition.shortName.isEmpty() || !block.hasParameter(definition.shortName))) available.add(definition);
            }
        }
        if (available.isEmpty()) return;
        addSection("Add a parameter");
        LinearLayout card = parameterCard();
        Spinner parameter = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (Definition definition : available) names.add(definition.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        parameter.setAdapter(adapter);
        parameter.setBackground(background(RAISED, BORDER, 0));
        parameter.setContentDescription("Parameter to add from the official catalog");
        card.addView(parameter, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView help = label("", 12, MUTED);
        help.setPadding(0, dp(8), 0, dp(8));
        card.addView(help);
        EditText value = edit("Value or cable (_name)");
        value.setSingleLine(true);
        value.setContentDescription("Value of the new parameter");
        String[] selectedName = {""};
        boolean[] settingValue = {false};
        addDraftBudgetFilter(value, () -> block.index + ":" + block.name + ":" + selectedName[0], () -> "");
        value.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (settingValue[0] || selectedName[0].isEmpty()) return;
                String key = block.index + ":" + block.name + ":" + selectedName[0];
                String typed = text.toString();
                if (typed.isEmpty()) editDrafts.remove(key); else editDrafts.put(key, new Draft("", typed));
                newParameterName = selectedName[0]; newParameterValue = typed;
                requestUiSave();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        parameter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) ((TextView) view).setTextColor(TEXT);
                Definition definition = available.get(position);
                // Documentation sentinels and mathematical typesetting are not DROID expressions.
                String initial = definition.defaultValue.trim();
                if (initial.equalsIgnoreCase("unpatched") || initial.contains("\\") || initial.contains("$")) initial = "";
                selectedName[0] = definition.name; newParameterName = definition.name;
                Draft draft = editDrafts.get(block.index + ":" + block.name + ":" + definition.name);
                settingValue[0] = true;
                value.setText(draft == null ? initial : draft.value);
                settingValue[0] = false;
                help.setText(getString(R.string.forge_parameter_help, parameterMeta(definition), definition.description.isEmpty() ? "" : "\n" + definition.description));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        if (!newParameterName.isEmpty()) for (int i = 0; i < available.size(); i++)
            if (newParameterName.equals(available.get(i).name)) { parameter.setSelection(i); break; }
        card.addView(value, new LinearLayout.LayoutParams(-1, dp(50)));
        Button add = action("Add parameter", "Add the selected parameter to this block", true);
        add.setOnClickListener(v -> {
            Definition definition = available.get(parameter.getSelectedItemPosition());
            String text = value.getText().toString();
            if (text.trim().isEmpty()) {
                value.setError("Enter a value or cable.");
                value.requestFocus();
                return;
            }
            mutate("Adding " + definition.name + "…", -2,
                    () -> NativeBridge.setParameter(block.index, definition.name, text),
                    () -> editDrafts.remove(block.index + ":" + block.name + ":" + definition.name));
        });
        card.addView(add, new LinearLayout.LayoutParams(-1, dp(48)));
        inspectorBody.addView(card, spacedParams());
    }

    private void renderRack() {
        if (rackViewport == null) return;
        List<RackViewport.Module> modules = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        for (RegisterLabel label : patch.registerLabels.values()) {
            String text = label.shortName.isEmpty() ? label.description : label.shortName;
            int end = Math.min(18, text.length());
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            labels.put(label.register, text.substring(0, end));
        }
        for (RackModule module : patch.rackModules)
            modules.add(new RackViewport.Module(module.name, module.block, module.controller, module.g8, module.rgbOffset, rackImages.get(module.name)));
        rackViewport.setModules(modules, labels);
        updateRackZoom();
        for (RackViewport.Module module : modules) loadRackImage(module.name);
    }

    private void loadRackImage(String name) {
        if (rackImages.containsKey(name) || !pendingRackImages.add(name)) return;
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            Bitmap found = loadAssetBitmap(application, "modules/" + name + ".png");
            deliver(() -> {
                pendingRackImages.remove(name);
                if (found == null) return;
                rackImages.put(name, found);
                for (RackViewport.Module module : rackViewport.modules()) if (module.name.equals(name)) module.image = found;
                rackViewport.invalidate();
            });
        });
    }

    private void updateRackZoom() {
        if (rackZoomLabel != null) rackZoomLabel.setText(getString(R.string.mobile_rack_zoom_percent, Math.round(rackViewport.zoom() * 100)));
    }

    private void showRackModule(RackViewport.Module module) {
        if (!loaded || operations != 0) return;
        List<String> choices = new ArrayList<>();
        choices.add(getString(R.string.mobile_labels_edit));
        if (module.block >= 0) choices.add(getString(R.string.mobile_module_parameters));
        showDialog(new AlertDialog.Builder(this).setTitle(moduleTitle(module))
                .setItems(choices.toArray(new String[0]), (dialog, position) -> {
                    if (position == 0) chooseModuleLabel(module);
                    else {
                        Block block = findBlock(module.block);
                        if (block != null && block.controller) showController(block);
                    }
                }).setNegativeButton("Close", null));
    }

    private String moduleTitle(RackViewport.Module module) {
        return getString(R.string.mobile_rack_module, module.name.toUpperCase(Locale.ROOT),
                module.controller > 0 ? " · " + module.controller : module.g8 > 0 ? " · G" + module.g8 : "");
    }

    private void chooseRackLabels() {
        if (!loaded || operations != 0) return;
        List<RackViewport.Module> modules = rackViewport.modules();
        String[] titles = new String[modules.size()];
        for (int index = 0; index < modules.size(); index++) titles[index] = moduleTitle(modules.get(index));
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.mobile_labels_edit)
                .setItems(titles, (dialog, position) -> chooseModuleLabel(modules.get(position)))
                .setNegativeButton("Close", null));
    }

    private void chooseModuleLabel(RackViewport.Module module) {
        if (!loaded || operations != 0) return;
        List<ModuleGeometry.Port> ports = module.ports();
        String[] titles = new String[ports.size()];
        for (int index = 0; index < ports.size(); index++) {
            String register = ports.get(index).register;
            RegisterLabel label = patch.registerLabels.get(register);
            String text = label == null ? "" : label.shortName.isEmpty() ? label.description : label.shortName;
            titles[index] = text.isEmpty() ? register : register + " · " + displayText(text, 96);
        }
        showDialog(new AlertDialog.Builder(this).setTitle(moduleTitle(module))
                .setItems(titles, (dialog, position) -> editRegisterLabel(ports.get(position).register, module))
                .setNegativeButton("Close", null));
    }

    private boolean annotationEditingAvailable() {
        if (!loaded || operations != 0) return false;
        if (!patch.analysisComplete || recoveryRequired) {
            showError(getString(R.string.mobile_annotation_unavailable), getString(R.string.mobile_annotation_source)); return false;
        }
        if (sourceDraft != null || sourceDialog != null) {
            showError(getString(R.string.mobile_annotation_unavailable), getString(R.string.mobile_annotation_source_pending)); return false;
        }
        return true;
    }

    private boolean resumeAnnotationBefore(String kind, int block, String register, Runnable next) {
        if (annotationDraft == null || annotationDraft.matches(kind, block, register)) return false;
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.mobile_annotation_pending)
                .setMessage(R.string.mobile_annotation_replace)
                .setNegativeButton("Keep", null)
                .setPositiveButton(R.string.mobile_annotation_resume, (dialog, which) -> showAnnotationDraft())
                .setNeutralButton(R.string.mobile_annotation_discard, (dialog, which) -> {
                    annotationDraft = null; requestUiSave(); next.run();
                }));
        return true;
    }

    private void editRegisterLabel(String register, RackViewport.Module module) {
        if (!annotationEditingAvailable()) return;
        if (!patch.rackComplete) { showError(getString(R.string.mobile_annotation_unavailable), getString(R.string.mobile_rack_partial)); return; }
        if (resumeAnnotationBefore("label", module.block, register, () -> editRegisterLabel(register, module))) return;
        RegisterLabel value = patch.registerLabels.get(register);
        if (value != null && (value.shortName.length() > 18 || !withinAnnotationBytes(value.description, 4096))) {
            showError(getString(R.string.mobile_annotation_unavailable), getString(R.string.mobile_annotation_source)); return;
        }
        if (annotationDraft == null) annotationDraft = new AnnotationDraft("label", module.block, module.name,
                module.controller, register, value == null ? "" : value.shortName, value == null ? "" : value.description);
        showAnnotationDraft();
    }

    private void editBlockComment(Block block) {
        if (!annotationEditingAvailable()) return;
        if (!block.commentEditable) { showError(getString(R.string.mobile_annotation_unavailable), getString(R.string.mobile_annotation_source)); return; }
        if (resumeAnnotationBefore("comment", block.index, "", () -> editBlockComment(block))) return;
        if (annotationDraft == null) annotationDraft = new AnnotationDraft("comment", block.index, block.name, 0, "", block.comment, "");
        showAnnotationDraft();
    }

    private boolean annotationCurrent(AnnotationDraft draft) {
        if (draft.orphan) return false;
        if ("comment".equals(draft.kind)) {
            Block block = findBlock(draft.block);
            return block != null && block.commentEditable && block.name.equals(draft.moduleName) && block.comment.equals(draft.baseFirst);
        }
        if (draft.block >= 0) {
            RackModule module = null;
            for (RackModule candidate : patch.rackModules) if (candidate.block == draft.block) { module = candidate; break; }
            if (module == null || !module.name.equals(draft.moduleName) || module.controller != draft.controller) return false;
        } else {
            boolean present = false;
            for (RackModule module : patch.rackModules) if (module.name.equals(draft.moduleName))
                for (ModuleGeometry.Port port : ModuleGeometry.ports(module.name, module.controller, module.g8, module.rgbOffset))
                    if (port.register.equals(draft.register)) present = true;
            if (!present) return false;
        }
        RegisterLabel value = patch.registerLabels.get(draft.register);
        return draft.baseFirst.equals(value == null ? "" : value.shortName)
                && draft.baseSecond.equals(value == null ? "" : value.description);
    }

    private void showAnnotationDraft() {
        AnnotationDraft draft = annotationDraft;
        if (draft == null) return;
        if (!annotationCurrent(draft)) {
            showDialog(new AlertDialog.Builder(this).setTitle(R.string.mobile_annotation_pending)
                    .setMessage(getString(R.string.mobile_annotation_changed) + "\n\n" + draft.readable())
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Copy", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mobile_annotation_pending), draft.readable()));
                    }).setNeutralButton(R.string.mobile_annotation_discard, (dialog, which) -> {
                        if (annotationDraft == draft) annotationDraft = null; requestUiSave();
                    }));
            return;
        }
        if (!annotationEditingAvailable()) return;
        boolean label = "label".equals(draft.kind);
        LinearLayout body = panel();
        if (label) body.addView(label(getString(R.string.mobile_label_help), 12, MUTED));
        EditText first = edit(label ? getString(R.string.mobile_label_short) : getString(R.string.mobile_comment_hint));
        first.setContentDescription(label ? getString(R.string.mobile_label_short_description, draft.register)
                : getString(R.string.mobile_comment_description, draft.moduleName));
        first.setSingleLine(label);
        if (label) first.setFilters(new InputFilter[0]);
        else { first.setFilters(new InputFilter[0]); first.setMinLines(4); first.setMaxLines(10); first.setGravity(Gravity.TOP | Gravity.START); first.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        first.setText(draft.first);
        body.addView(first, new LinearLayout.LayoutParams(-1, label ? dp(52) : -2));
        EditText second = label ? edit(getString(R.string.mobile_label_full)) : null;
        if (second != null) {
            second.setSingleLine(true); second.setContentDescription(getString(R.string.mobile_label_full_description, draft.register));
            second.setText(draft.second); body.addView(second, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        addAnnotationFilter(first, label ? 72 : 16 * 1024, label ? 1 : 256, label);
        if (second != null) addAnnotationFilter(second, 4096, 1, false);
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                draft.first = first.getText().toString(); draft.second = second == null ? "" : second.getText().toString(); requestUiSave();
            }
            @Override public void afterTextChanged(Editable text) {}
        };
        first.addTextChangedListener(watcher); if (second != null) second.addTextChangedListener(watcher);
        dialogType = "annotation";
        ScrollView form = new ScrollView(this); form.addView(body);
        AlertDialog dialog = trackDialog(new AlertDialog.Builder(this)
                .setTitle(label ? getString(R.string.mobile_label_title, draft.register) : getString(R.string.mobile_comment_title, draft.moduleName.toUpperCase(Locale.ROOT)))
                .setView(form).setNegativeButton("Close", null).setPositiveButton("Apply", null)
                .setNeutralButton(R.string.mobile_annotation_discard, null).create());
        dialog.setOnDismissListener(closed -> {
            if (annotationDialog == dialog) annotationDialog = null;
            if (!destroyed) {
                if (annotationDraft == draft && !draft.dirty()) annotationDraft = null;
                if ("annotation".equals(dialogType)) dialogType = "";
                requestUiSave();
            }
        });
        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                if (annotationDraft == draft) annotationDraft = null; dialog.dismiss(); requestUiSave();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!annotationCurrent(draft) || !annotationEditingAvailable()) return;
                if (!draft.dirty()) { annotationDraft = null; dialog.dismiss(); return; }
                String a = draft.first, b = draft.second;
                EngineAction action = label ? () -> NativeBridge.setRegisterLabel(draft.register, a, b)
                        : () -> NativeBridge.setBlockComment(draft.block, a);
                mutate(getString(R.string.mobile_annotation_saving), -2, action, () -> {
                    if (annotationDraft == draft) annotationDraft = null; dialog.dismiss();
                });
            });
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        annotationDialog = dialog;
        dialog.show(); requestUiSave();
    }

    private void addAnnotationFilter(EditText field, int maxBytes, int maxLines, boolean bracketsForbidden) {
        InputFilter[] existing = field.getFilters();
        InputFilter[] filters = java.util.Arrays.copyOf(existing, existing.length + 1);
        filters[existing.length] = (inserted, start, end, destination, replaceStart, replaceEnd) -> {
            long characters = (long) destination.length() - (replaceEnd - replaceStart) + end - start;
            if (characters > maxBytes || maxBytes == 72 && characters > 18) {
                toast(getString(R.string.mobile_annotation_limit)); return destination.subSequence(replaceStart, replaceEnd);
            }
            String candidate = destination.subSequence(0, replaceStart).toString() + inserted.subSequence(start, end)
                    + destination.subSequence(replaceEnd, destination.length());
            boolean valid = (!bracketsForbidden || !candidate.contains("]"))
                    && (maxBytes != 72 || candidate.length() <= 18);
            int lines = 1;
            for (int index = 0; valid && index < candidate.length(); index++) {
                char character = candidate.charAt(index);
                if (character == '\n') lines++;
                if (character == '\r' && (index + 1 == candidate.length() || candidate.charAt(index + 1) != '\n')) lines++;
                if (character == 0) valid = false;
            }
            try { valid = valid && lines <= maxLines && PatchTextCodec.encode(candidate).length <= maxBytes; }
            catch (IOException exception) { valid = false; }
            if (valid) return null;
            toast(getString(R.string.mobile_annotation_limit));
            return destination.subSequence(replaceStart, replaceEnd);
        };
        field.setFilters(filters);
    }

    private static boolean withinAnnotationBytes(String text, int maximum) {
        if (text.length() > maximum) return false;
        try { return PatchTextCodec.encode(text).length <= maximum; }
        catch (IOException exception) { return false; }
    }

    private void showController(Block block) {
        selectedBlock = block.index;
        dialogType = "controller"; dialogBlock = block.index;
        LinearLayout body = panel();
        inspectorTitle = label("[" + block.name + "]", 17, TEXT);
        body.addView(inspectorTitle, new LinearLayout.LayoutParams(-1, dp(48)));
        ScrollView scroll = new ScrollView(this);
        inspectorBody = vertical(); scroll.addView(inspectorBody);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        engineButtons.clear();
        renderBlockInspector(block);
        previewDialog = trackDialog(new AlertDialog.Builder(this).setView(body).setNegativeButton("Close", null).create());
        previewDialog.setOnDismissListener(dialog -> {
            inspectorBody = null; previewDialog = null; engineButtons.clear();
            if (!destroyed) { dialogType = ""; requestUiSave(); }
        });
        previewDialog.show();
        previewDialog.getWindow().setLayout(dp(Math.min(640, Math.max(300, getResources().getConfiguration().screenWidthDp - 48))),
                dp(Math.min(560, Math.max(300, getResources().getConfiguration().screenHeightDp - 100))));
        updateEnabled();
    }

    private void loadPreviewImage(String asset, ImageView image, int generation) {
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            Bitmap bitmap = loadAssetBitmap(application, asset);
            if (bitmap == null) return;
            deliver(() -> {
                if (generation != inspectorGeneration) return;
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            });
        });
    }

    private void addCircuit(Circuit circuit) {
        final int selection = initialSelection;
        mutate("Adding " + circuit.name + "…", -3, () -> NativeBridge.addCircuitWithSelection(circuit.name, selection));
    }

    private String parameterMeta(Definition definition) {
        String meta = definition.type;
        if (!definition.direction.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + definition.direction;
        if (!definition.defaultValue.isEmpty()) meta += " · default: " + definition.defaultValue;
        return meta;
    }

    private void showFileMenu(View anchor) {
        if (!loaded || operations != 0) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 6, 0, "New patch…");
        menu.getMenu().add(0, 1, 1, "Import an .ini patch…");
        menu.getMenu().add(0, 2, 2, "Export the .ini patch…");
        menu.getMenu().add(0, 7, 3, "Add a hardware controller…");
        menu.getMenu().add(0, 9, 4, R.string.forge_initial_parameters);
        menu.getMenu().add(0, 10, 4, R.string.mobile_labels_edit);
        menu.getMenu().add(0, 12, 4, R.string.mobile_examples_menu);
        menu.getMenu().add(0, 13, 4, "DROID connection…");
        menu.getMenu().add(0, 14, 4, "Import droid.ini from the DROID card…");
        menu.getMenu().add(0, 15, 4, "Activate! — send to DROID…");
        menu.getMenu().add(0, 16, 4, "Save to SD — write droid.ini…");
        menu.getMenu().add(0, 17, 4, "Eject SD…");
        menu.getMenu().add(0, 18, 4, "DROID backups…");
        if (new File(getFilesDir(), "droid-import-backups/latest.json").isFile())
            menu.getMenu().add(0, 19, 4, "Recover the document saved before DROID import…");
        menu.getMenu().add(0, 3, 4, "Edit source…");
        menu.getMenu().add(0, 4, 5, "View local diagnostics…");
        if (!orphanDrafts.isEmpty()) menu.getMenu().add(0, 8, 6, R.string.forge_orphan_drafts_menu);
        if (annotationDraft != null && annotationDraft.dirty()) menu.getMenu().add(0, 11, 6, R.string.mobile_annotation_pending_menu);
        menu.getMenu().add(0, 5, 6, "About and licenses…");
        menu.getMenu().findItem(7).setEnabled(patch.analysisComplete && !recoveryRequired);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openPatch(); break;
                case 2: confirmExport(); break;
                case 3: editSource(0); break;
                case 4: showDiagnostics(); break;
                case 5: showAbout(); break;
                case 6: newPatch(); break;
                case 7: addController(); break;
                case 8: showOrphanDrafts(); break;
                case 9: chooseInitialParameters(); break;
                case 10: chooseRackLabels(); break;
                case 11: showAnnotationDraft(); break;
                case 12: showExamples(); break;
                case 13: droidActions.connection(); break;
                case 14: droidActions.readPatch(); break;
                case 15: droidActions.activate(); break;
                case 16: droidActions.savePatch(); break;
                case 17: droidActions.eject(); break;
                case 18: droidActions.backups(); break;
                case 19: recoverBeforeDroidImport(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void showExamples() {
        if (!loaded || operations != 0) return;
        String sourceBefore = sourceSnapshot;
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            try (InputStream input = application.getAssets().open("examples/manifest.json")) {
                JSONObject manifest = new JSONObject(readUtf8(input, 2 * 1024 * 1024));
                if (manifest.optInt("schema_version") != 1) throw new IOException("Unrecognized example index.");
                JSONArray entries = manifest.optJSONArray("examples");
                if (entries == null || entries.length() > 1024) throw new IOException("The example index is incomplete or too large.");
                Map<String, List<Example>> categories = new LinkedHashMap<>();
                String firmware = manifest.optString("firmware");
                for (int index = 0; index < entries.length(); index++) {
                    Example example = new Example(entries.getJSONObject(index), firmware);
                    categories.computeIfAbsent(example.category, category -> new ArrayList<>()).add(example);
                }
                deliver(() -> {
                    if (operations != 0 || !sourceBefore.equals(sourceSnapshot)) return;
                    List<String> names = new ArrayList<>(categories.keySet());
                    String[] categoryLabels = new String[names.size()];
                    for (int index = 0; index < names.size(); index++) categoryLabels[index] = ExampleText.category(names.get(index));
                    showDialog(new AlertDialog.Builder(this).setTitle(R.string.mobile_examples_title)
                            .setItems(categoryLabels, (dialog, position) -> chooseExample(names.get(position), categories.get(names.get(position))))
                            .setNegativeButton("Close", null));
                });
            } catch (Exception | OutOfMemoryError exception) {
                deliver(() -> showError(getString(R.string.mobile_examples_unavailable), errorMessage(exception)));
            }
        });
    }

    private void chooseExample(String category, List<Example> examples) {
        String[] titles = new String[examples.size()];
        for (int index = 0; index < examples.size(); index++) {
            Example example = examples.get(index);
            titles[index] = ExampleText.title(example.title) + (example.page > 0 ? getString(R.string.mobile_example_page, example.page) : "");
        }
        showDialog(new AlertDialog.Builder(this).setTitle(ExampleText.category(category)).setItems(titles, (dialog, position) -> previewExample(examples.get(position)))
                .setNegativeButton("Close", null));
    }

    private static String readExample(Context context, Example example) throws IOException {
        if (!example.file.matches("examples/[a-zA-Z0-9_./-]+\\.ini") || example.file.contains("..")) throw new IOException("Invalid example path.");
        try (InputStream input = context.getAssets().open(example.file)) {
            String text = readUtf8(input);
            if (!example.sha256.isEmpty() && !PatchTextCodec.sha256(text).equalsIgnoreCase(example.sha256))
                throw new IOException("The example text does not match its index.");
            return text;
        }
    }

    private void previewExample(Example example) {
        if (!loaded || operations != 0) return;
        String sourceBefore = sourceSnapshot;
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            try {
                String text = readExample(application, example);
                deliver(() -> {
                    if (operations != 0 || !sourceBefore.equals(sourceSnapshot)) return;
                    LinearLayout body = vertical(), details = panel();
                    details.addView(label(getString(R.string.mobile_example_metadata, example.firmware, ExampleText.category(example.category),
                            example.page > 0 ? getString(R.string.mobile_example_page, example.page) : ""), 12, MUTED));
                    details.addView(label(getString("full".equals(example.kind) ? R.string.mobile_example_full
                            : "fragment".equals(example.kind) ? R.string.mobile_example_fragment : R.string.mobile_example_snippet), 13, TEXT));
                    if (!example.note.isEmpty()) details.addView(label(ExampleText.note(example.note), 12, MUTED));
                    TextView provenance = label(getString(R.string.mobile_example_provenance, example.source, example.sourcePath), 11, MUTED);
                    provenance.setTextIsSelectable(true); details.addView(provenance);
                    if (example.contextRequired) details.addView(label(getString(R.string.mobile_example_context), 13, CYAN));
                    ScrollView scroll = new ScrollView(this);
                    TextView code = label(examplePreview(text), 12, TEXT);
                    code.setTypeface(Typeface.MONOSPACE); code.setTextIsSelectable(true);
                    code.setPadding(dp(8), dp(12), dp(8), dp(8)); details.addView(code); scroll.addView(details);
                    body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(Math.max(120, Math.min(480, getResources().getConfiguration().screenHeightDp - 180)))));
                    showDialog(new AlertDialog.Builder(this).setTitle(ExampleText.title(example.title)).setView(body).setNegativeButton("Close", null)
                            .setNeutralButton("Copy", (dialog, which) -> {
                                if (text.length() > 128 * 1024) { toast(getString(R.string.mobile_example_copy_limit)); return; }
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(ExampleText.title(example.title), text));
                            }).setPositiveButton(R.string.mobile_example_open, (dialog, which) -> confirmExample(example)));
                });
            } catch (IOException | OutOfMemoryError exception) {
                deliver(() -> showError(getString(R.string.mobile_examples_unavailable), errorMessage(exception)));
            }
        });
    }

    private String examplePreview(String text) {
        int end = 0, lines = 1;
        while (end < text.length() && end < 16384 && lines <= 256) { if (text.charAt(end) == '\n') lines++; end++; }
        if (end == text.length()) return text;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n\n" + getString(R.string.mobile_example_preview_excerpt);
    }

    private void confirmExample(Example example) {
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.mobile_example_replace_title)
                .setMessage(getString(R.string.mobile_example_replace) + (example.contextRequired ? "\n\n" + getString(R.string.mobile_example_context) : ""))
                .setNegativeButton("Keep current patch", null)
                .setNeutralButton("Export first", (dialog, which) -> confirmExport())
                .setPositiveButton(R.string.mobile_example_open, (dialog, which) -> {
                    Context application = getApplicationContext();
                    mutate(getString(R.string.forge_read_patch), -1, () -> replaceDocument(readExample(application, example), true), () -> {
                        patchName = safeDocumentName(example.file.substring(example.file.lastIndexOf('/') + 1));
                        selectedBlock = -1; selectedSection = 0; activePane = 1;
                        editDrafts.clear(); orphanDrafts.clear(); appliedCells.clear(); foldedBlocks.clear(); parameterPages.clear();
                        pendingConnection = null; annotationDraft = null; sourceDraft = null; sourceDraftBase = null;
                    }, EditMode.RESET);
                }));
    }

    private void openPatch() {
        showDialog(new AlertDialog.Builder(this).setTitle("Import another patch?")
                .setMessage("The selected file will replace the current patch, its local autosave and unapplied drafts. Undo/Redo history will be reset. Export your patch first if you want to keep it, and recover your drafts.")
                .setNegativeButton("Keep current patch", null)
                .setNeutralButton("Export first", (dialog, which) -> confirmExport())
                .setPositiveButton("Choose a file", (dialog, which) -> choosePatch())
                );
    }

    private String[] initialParameterChoices() {
        return new String[]{getString(R.string.forge_initial_all), getString(R.string.forge_initial_typical),
                getString(R.string.forge_initial_essential), getString(R.string.forge_initial_none)};
    }

    private void chooseInitialParameters() {
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.forge_initial_parameters)
                .setSingleChoiceItems(initialParameterChoices(), initialSelection, (dialog, which) -> {
                    initialSelection = which; requestUiSave(); dialog.dismiss();
                }).setNegativeButton("Close", null));
    }

    private void choosePatch() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Many providers classify .ini as application/octet-stream rather than text/plain.
        launchDocument(intent, OPEN_PATCH);
    }

    private void importDroidSource(String text) {
        importDroidSource(text, "droid.ini", null);
    }

    private void importDroidSource(String text, String restoredName, JSONObject restoredUi) {
        if (!loaded || operations != 0) return;
        final JSONObject previousUi;
        try { previousUi = captureUi(); }
        catch (JSONException | OutOfMemoryError failure) {
            showError("Import rejected", "The current drafts could not be saved: " + errorMessage(failure));
            return;
        }
        main.removeCallbacks(saveUi);
        Context application = getApplicationContext();
        final String previousName = patchName;
        final String previousSource = sourceSnapshot;
        final java.util.concurrent.atomic.AtomicReference<String> importWarning = new java.util.concurrent.atomic.AtomicReference<>("");
        mutate("Importing droid.ini…", -1, () -> {
            if (!previousSource.equals(NativeBridge.source()))
                throw new IOException("The document has changed. Start the import again.");
            File directory = new File(application.getFilesDir(), "droid-import-backups");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("The backup of the previous document could not be created.");
            String previousState = previousUi.toString();
            File[] saved = directory.listFiles();
            if (saved == null) throw new IOException("Previous backups are inaccessible.");
            long savedBytes = 0;
            int documents = 0;
            for (File file : saved) {
                if (file.isFile()) savedBytes += file.length();
                if (file.getName().endsWith(".ini")) documents++;
            }
            long requiredBytes = previousSource.getBytes(StandardCharsets.UTF_8).length
                    + previousState.getBytes(StandardCharsets.UTF_8).length;
            if (documents >= 128 || savedBytes + requiredBytes > 64L * 1024 * 1024)
                throw new IOException("The pre-import backup limit has been reached (128 documents / 64 MiB). Export any documents you want to keep before importing another patch.");
            String identity = java.util.UUID.randomUUID().toString();
            File sourceCopy = new File(directory, identity + ".ini");
            writeAtomic(new AtomicFile(sourceCopy), NativeBridge.source());
            try (InputStream input = new AtomicFile(sourceCopy).openRead()) {
                if (!readUtf8(input).equals(NativeBridge.source()))
                    throw new IOException("The backup of the previous document could not be verified.");
            }
            File sessionCopy = new File(directory, identity + ".json");
            writeAtomic(new AtomicFile(sessionCopy), previousState);
            try (InputStream input = new AtomicFile(sessionCopy).openRead()) {
                if (!previousState.equals(readUtf8(input, 32 * 1024 * 1024)))
                    throw new IOException("The draft backup could not be verified.");
            }
            writeAtomic(new AtomicFile(new File(directory, "latest.json")),
                    new JSONObject().put("id", identity).put("name", previousName).toString());
            JSONObject nextUi = restoredUi == null ? new JSONObject() : new JSONObject(restoredUi.toString());
            nextUi.put("sourceHash", PatchTextCodec.sha256(text));
            nextUi.put("documentRevision", documentRevision + 1);
            nextUi.put("name", restoredName);
            // Keep both states until source and UI have been committed. Never overwrite old drafts early.
            JSONObject journalState = new JSONObject().put("previous", previousUi).put("next", nextUi).put("committed", false);
            String journal = journalState.toString();
            writeAtomic(uiTransition(application), journal);
            try (InputStream input = uiTransition(application).openRead()) {
                if (!journal.equals(readUtf8(input, 64 * 1024 * 1024)))
                    throw new IOException("The import recovery state could not be verified.");
            }
            try {
                String result = replaceDocument(text, true);
                retainedUi = nextUi;
                try {
                    String committed = journalState.put("committed", true).toString();
                    writeAtomic(uiTransition(application), committed);
                    try (InputStream input = uiTransition(application).openRead()) {
                        if (!committed.equals(readUtf8(input, 64 * 1024 * 1024)))
                            throw new IOException("The import journal could not be verified after loading.");
                    }
                } catch (IOException failure) {
                    importWarning.set(errorMessage(failure));
                }
                return result;
            } catch (RuntimeException | LinkageError refused) {
                retainedUi = previousUi;
                throw refused;
            }
        }, () -> {
            patchName = restoredName;
            selectedBlock = -1; selectedCircuit = null; selectedSection = 0; activePane = 1;
            editDrafts.clear(); orphanDrafts.clear(); appliedCells.clear(); foldedBlocks.clear(); parameterPages.clear();
            pendingConnection = null; annotationDraft = null; sourceDraft = null; sourceDraftBase = null;
            if (restoredUi != null) restoreUi(restoredUi, false);
            if (!importWarning.get().isEmpty())
                showError("Incomplete recovery backup", importWarning.get() + " Copies of the previous document have been kept.");
        }, EditMode.RESET);
    }

    private void recoverBeforeDroidImport() {
        if (!loaded || operations != 0) return;
        operations++; updateEnabled();
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            try {
                File directory = new File(application.getFilesDir(), "droid-import-backups");
                JSONObject record;
                try (InputStream input = new AtomicFile(new File(directory, "latest.json")).openRead()) {
                    record = new JSONObject(readUtf8(input, 16384));
                }
                String identity = record.getString("id");
                if (!identity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw new IOException("Invalid backup identifier.");
                String text;
                JSONObject ui;
                try (InputStream input = new AtomicFile(new File(directory, identity + ".ini")).openRead()) { text = readUtf8(input); }
                try (InputStream input = new AtomicFile(new File(directory, identity + ".json")).openRead()) {
                    ui = new JSONObject(readUtf8(input, 32 * 1024 * 1024));
                }
                if (!PatchTextCodec.sha256(text).equals(ui.optString("sourceHash")))
                    throw new IOException("The saved document and its drafts do not match.");
                String name = safeDocumentName(record.optString("name", "droid.ini"));
                deliver(() -> {
                    operations--; updateEnabled(); updateDiagnostics();
                    showDialog(new AlertDialog.Builder(this).setTitle("Recover the previous document?")
                            .setMessage("The document and drafts saved before the DROID import will be restored. The current document will be backed up in turn. The DROID card will remain unchanged.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Recover", (dialog, which) -> importDroidSource(text, name, ui)));
                });
            } catch (Exception | OutOfMemoryError exception) {
                deliver(() -> { operations--; updateEnabled(); updateDiagnostics(); showError("Unable to recover", errorMessage(exception)); });
            }
        });
    }

    private void newPatch() {
        showDialog(new AlertDialog.Builder(this).setTitle("Create a new patch?")
                .setMessage("The current local autosave and unapplied drafts will be replaced. Export your patch first if you want to keep it, and recover your drafts. Undo/Redo history will be reset.")
                .setNegativeButton("Keep current patch", null)
                .setNeutralButton("Export first", (dialog, which) -> confirmExport())
                .setPositiveButton("New patch", (dialog, which) -> mutate("Creating patch…", -1,
                        () -> replaceDocument("# New patch droid Forge mobile\n# LABELS: master=18; firmware=blue-7\n", true),
                        () -> { patchName = "new-droid.ini"; activePane = 1; editDrafts.clear(); orphanDrafts.clear(); appliedCells.clear(); pendingConnection = null; annotationDraft = null; sourceDraft = null; sourceDraftBase = null; }, EditMode.RESET))
                );
    }

    private void addController() {
        if (!loaded || operations != 0 || !patch.analysisComplete || recoveryRequired) return;
        showDialog(new AlertDialog.Builder(this).setTitle("Add a DROID controller")
                .setItems(CONTROLLERS, (dialog, position) -> {
                    String name = CONTROLLERS[position];
                    int index = patch.blocks.size();
                    for (Block block : patch.blocks) if (!block.controller) { index = block.index; break; }
                    mutate("Adding controller " + name + "…", index, () -> NativeBridge.addCircuit(name));
                })
                .setNegativeButton("Close", null));
    }

    private void confirmExport() {
        confirmExport(false);
    }

    private void confirmExport(boolean draftsAcknowledged) {
        if (!draftsAcknowledged && (!editDrafts.isEmpty() || !orphanDrafts.isEmpty() || annotationDraft != null && annotationDraft.dirty() || (sourceDraft != null && sourceSnapshot.equals(sourceDraftBase)))) {
            showDialog(new AlertDialog.Builder(this).setTitle("Some edits have not been applied")
                    .setMessage("The export contains the applied patch. Your drafts are saved locally. Apply the values or source text to include them in the exported file.")
                    .setNegativeButton("Return to patch", null)
                    .setPositiveButton("Export applied patch", (dialog, which) -> confirmExport(true)));
            return;
        }
        if (patch.errorCount() > 0) {
            showDialog(new AlertDialog.Builder(this).setTitle("Export with local errors?")
                    .setMessage(getResources().getQuantityString(R.plurals.forge_error_count, patch.errorCount(), patch.errorCount())
                            + " reported. The export preserves the current text. Check the patch before using it on DROID.")
                    .setNegativeButton("Return to patch", null)
                    .setNeutralButton("Diagnostics", (dialog, which) -> showDiagnostics())
                    .setPositiveButton("Export", (dialog, which) -> createPatch()));
        } else createPatch();
    }

    private void createPatch() {
        if (!loaded || operations != 0) return;
        pendingExport = sourceSnapshot;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // DocumentsUI appends .txt for text/plain even when EXTRA_TITLE already ends in .ini.
        // Binary MIME leaves the supplied .ini filename intact; the payload remains UTF-8 text.
        intent.setType("application/octet-stream");
        String name = patchName.toLowerCase(Locale.ROOT).endsWith(".ini") ? patchName : patchName + ".ini";
        intent.putExtra(Intent.EXTRA_TITLE, name);
        String snapshot = pendingExport;
        Context application = getApplicationContext();
        operations++; updateEnabled();
        statusLabel.setText(R.string.forge_prepare_export);
        WORKER.execute(() -> {
            try {
                writeAtomic(exportSnapshot(application), snapshot);
                deliver(() -> {
                    operations--; updateEnabled(); updateDiagnostics();
                    if (!launchDocument(intent, CREATE_PATCH)) clearExportSnapshot(application);
                });
            } catch (IOException | OutOfMemoryError exception) {
                deliver(() -> {
                    operations--; updateEnabled(); updateDiagnostics(); pendingExport = null;
                    showError("Unable to prepare export", errorMessage(exception));
                });
            }
        });
    }

    private boolean launchDocument(Intent intent, int request) {
        try { startActivityForResult(intent, request); return true; }
        catch (Exception exception) { showError("File picker unavailable", errorMessage(exception)); return false; }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (droidActions != null && droidActions.onActivityResult(request, result, data)) return;
        if (request != OPEN_PATCH && request != CREATE_PATCH) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == CREATE_PATCH) clearExportSnapshot(getApplicationContext());
            return;
        }
        Uri uri = data.getData();
        if (request == OPEN_PATCH) importPatch(uri);
        else {
            String snapshot = pendingExport;
            pendingExport = null;
            exportPatch(uri, snapshot);
        }
    }

    private void importPatch(Uri uri) {
        operations++;
        updateEnabled();
        statusLabel.setText(R.string.forge_read_patch);
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            String importedInput = null, importedName = "droid.ini";
            try {
                String text;
                try (InputStream input = application.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("The document provider could not open the file.");
                    text = readUtf8(input);
                }
                String name = documentName(application, uri);
                importedInput = text; importedName = name;
                Patch result = parsePatch(replaceDocument(text, true));
                String snapshot = NativeBridge.source();
                long revision = documentRevision;
                String autosaveFailure = persistAppliedPatch(application, snapshot);
                deliver(() -> {
                    operations--; patch = result; sourceSnapshot = snapshot;
                    activityDocumentRevision = revision; recoveryRequired = false;
                    saveError = autosaveFailure;
                    patchName = name; selectedBlock = -1; selectedCircuit = null;
                    selectedSection = 0; foldedBlocks.clear(); editDrafts.clear(); orphanDrafts.clear(); appliedCells.clear(); pendingConnection = null; annotationDraft = null;
                    activePane = 1;
                    renderPatch(); arrangePanels();
                    sourceDraft = null; sourceDraftBase = null;
                    toast("Patch imported.");
                });
            } catch (Exception | LinkageError | OutOfMemoryError exception) {
                String recoveredSource = null;
                Patch recoveredPatch = null;
                if (exception instanceof OutOfMemoryError) {
                    try {
                        recoveredSource = NativeBridge.source();
                        recoveredPatch = parsePatch(NativeBridge.state());
                    } catch (Exception | LinkageError | OutOfMemoryError ignored) { /* Keep the last available source for export. */ }
                }
                final String recoverySource = recoveredSource;
                final Patch recoveryPatch = recoveredPatch;
                final boolean imported = importedInput != null && importedInput.equals(recoverySource);
                final String recoveryName = importedName;
                final long revision = documentRevision;
                final String recoverySaveError = recoverySource == null ? saveError : persistAppliedPatch(application, recoverySource);
                deliver(() -> {
                    operations--;
                    activityDocumentRevision = revision;
                    if (exception instanceof OutOfMemoryError) {
                        if (recoverySource != null) sourceSnapshot = recoverySource;
                        if (recoveryPatch != null) patch = recoveryPatch;
                        recoveryRequired = recoveryPatch == null;
                        saveError = recoverySaveError;
                        if (imported) {
                            patchName = recoveryName; selectedBlock = -1; selectedSection = 0;
                            editDrafts.clear(); orphanDrafts.clear(); appliedCells.clear(); foldedBlocks.clear(); pendingConnection = null; annotationDraft = null; sourceDraft = null; sourceDraftBase = null;
                        }
                    }
                    renderPatch();
                    showError(exception instanceof OutOfMemoryError ? "Import interrupted" : "Unable to import", errorMessage(exception));
                });
            }
        });
    }

    private void exportPatch(Uri uri, String snapshot) {
        operations++;
        updateEnabled();
        statusLabel.setText(R.string.forge_export_patch);
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            try {
                String text = snapshot;
                if (text == null) {
                    try (InputStream input = exportSnapshot(application).openRead()) { text = readUtf8(input); }
                    catch (FileNotFoundException missingSnapshot) {
                        throw new IOException("The export snapshot is no longer available. Export the patch again.", missingSnapshot);
                    }
                }
                byte[] bytes = PatchTextCodec.encode(text);
                try (OutputStream output = application.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("The document provider could not open the file for writing.");
                    output.write(bytes);
                    output.flush();
                }
                deliver(() -> { operations--; renderPatch(); toast("Patch snapshot exported as UTF-8."); });
            } catch (Exception | OutOfMemoryError exception) {
                deliver(() -> {
                    operations--; renderPatch();
                    showError("Unable to export", errorMessage(exception));
                });
            } finally {
                exportSnapshot(application).delete();
            }
        });
    }

    private static AtomicFile exportSnapshot(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "patch-export-snapshot.ini"));
    }

    private static void clearExportSnapshot(Context context) {
        pendingExport = null;
        WORKER.execute(() -> exportSnapshot(context).delete());
    }

    private void editSource(int line) {
        if (!loaded || operations != 0) return;
        if (sourceDialog != null) return;
        String textToEdit = sourceDraft != null && sourceSnapshot.equals(sourceDraftBase) ? sourceDraft : sourceSnapshot;
        if (!PatchTextCodec.canEditInTextView(textToEdit)) {
            dialogType = "";
            showDialog(new AlertDialog.Builder(this).setTitle("Edit this large file externally")
                    .setMessage("The full text is preserved and can still be exported. To keep the interface responsive, the built-in text editor supports up to 256K characters, 4,096 lines and 16,384 characters per line. Export this patch, edit it in an external editor, then import it again.")
                    .setNegativeButton("Close", null).setPositiveButton("Export", (dialog, which) -> confirmExport()));
            requestUiSave();
            return;
        }
        dialogType = "source"; sourceLine = line;
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView hint = label("UTF-8 editing · comments and patch order are preserved.\nLocal diagnostics do not replace validation on DROID.", 12, MUTED);
        body.addView(hint);
        EditText source = edit(".ini patch text");
        source.setTypeface(Typeface.MONOSPACE);
        source.setTextSize(13);
        source.setGravity(Gravity.TOP | Gravity.START);
        source.setSingleLine(false);
        source.setHorizontallyScrolling(true);
        source.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        source.setId(android.R.id.edit);
        source.setFilters(new InputFilter[]{(inserted, start, end, destination, replaceStart, replaceEnd) -> {
            int length = destination.length() - (replaceEnd - replaceStart) + end - start;
            boolean accepted = length <= 256 * 1024;
            if (accepted) {
                String candidate = new StringBuilder(length).append(destination, 0, replaceStart)
                        .append(inserted, start, end).append(destination, replaceEnd, destination.length()).toString();
                accepted = PatchTextCodec.canEditInTextView(candidate);
            }
            if (accepted) return null;
            source.setError("Built-in editor limits: 256K characters, 4,096 lines, 16,384 characters per line. The existing text is preserved.");
            return destination.subSequence(replaceStart, replaceEnd);
        }});
        source.setText(textToEdit);
        source.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s.toString();
                sourceDraft = text.equals(sourceSnapshot) ? null : text;
                sourceDraftBase = sourceSnapshot;
                requestUiSave();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        source.setContentDescription("DROID patch source");
        int editorHeight = Math.max(120, Math.min(440, getResources().getConfiguration().screenHeightDp - 210));
        body.addView(source, new LinearLayout.LayoutParams(-1, dp(editorHeight)));
        AlertDialog dialog = trackDialog(new AlertDialog.Builder(this).setTitle("Source · " + patchName)
                .setView(body).setNegativeButton("Close", null).setPositiveButton("Apply", null).create());
        sourceDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            sourceSelection = source.getSelectionStart();
            sourceDialog = null;
            if (!destroyed) { dialogType = ""; requestUiSave(); }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String text = source.getText().toString();
                try {
                    if (PatchTextCodec.encode(text).length > MAX_FILE_BYTES) {
                        source.setError("The patch exceeds the 4 MiB limit."); return;
                    }
                } catch (IOException invalidText) {
                    source.setError(errorMessage(invalidText)); return;
                }
                mutate("Reading source…", -1, () -> replaceDocument(text, false), () -> {
                    sourceDraft = null; sourceDraftBase = null; sourceSelection = 0;
                    dialog.dismiss();
                }, EditMode.SOURCE);
            });
            if (sourceDraft != null && sourceSelection > 0) source.setSelection(Math.min(sourceSelection, source.length()));
            else if (line > 0) {
                int offset = 0;
                for (int current = 1; current < line; current++) {
                    int next = sourceSnapshot.indexOf('\n', offset);
                    if (next < 0) break;
                    offset = next + 1;
                }
                source.setSelection(Math.min(offset, source.length()));
            }
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(1000)), -2);
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
        requestUiSave();
    }

    private void updateDiagnostics() {
        if (recoveryRequired) {
            statusLabel.setText(R.string.forge_recovery_required);
            statusLabel.setTextColor(RED);
            return;
        }
        if (!saveError.isEmpty()) {
            statusLabel.setText(R.string.forge_autosave_failed);
            statusLabel.setTextColor(RED);
            return;
        }
        if (!draftSaveError.isEmpty()) {
            statusLabel.setText(R.string.forge_drafts_save_failed);
            statusLabel.setTextColor(RED);
            return;
        }
        if (!orphanDrafts.isEmpty()) {
            statusLabel.setText(getResources().getQuantityString(R.plurals.forge_orphan_drafts,
                    orphanDrafts.size(), orphanDrafts.size()));
            statusLabel.setTextColor(CYAN);
            statusLabel.setContentDescription(statusLabel.getText());
            return;
        }
        if (pendingConnection != null && currentPoint(pendingConnection)) {
            statusLabel.setText(getString(R.string.forge_connection_pending, pendingConnection.blockName.toUpperCase(Locale.ROOT), pendingConnection.parameter));
            statusLabel.setTextColor(CYAN);
            statusLabel.setContentDescription(statusLabel.getText() + ". Cancel the connection.");
            return;
        }
        int errors = patch.errorCount();
        int warnings = patch.diagnostics.size() - errors;
        if (!patch.analysisComplete) {
            statusLabel.setText(R.string.forge_analysis_partial);
            statusLabel.setTextColor(RED);
        } else if (simplifiedNavigation) {
            statusLabel.setText(getString(R.string.forge_large_patch_status,
                    getResources().getQuantityString(R.plurals.forge_error_count, errors, errors),
                    getResources().getQuantityString(R.plurals.forge_notice_count, warnings, warnings)));
            statusLabel.setTextColor(errors > 0 ? RED : MUTED);
        } else if (errors == 0 && warnings == 0) {
            statusLabel.setText(R.string.forge_validation_clean);
            statusLabel.setTextColor(MUTED);
        } else {
            statusLabel.setText(getString(R.string.forge_validation_issues,
                    getResources().getQuantityString(R.plurals.forge_error_count, errors, errors),
                    getResources().getQuantityString(R.plurals.forge_notice_count, warnings, warnings)));
            statusLabel.setTextColor(errors > 0 ? RED : COPPER);
        }
        statusLabel.setContentDescription(statusLabel.getText() + ". Open diagnostics.");
    }

    private void showDiagnostics() {
        if (!loaded) return;
        if (patch.diagnostics.isEmpty()) {
            showDialog(new AlertDialog.Builder(this).setTitle("Local validation")
                    .setMessage("No local errors were detected. This engine checks the patch structure using the available catalog. It does not guarantee how the patch will behave on DROID.")
                    .setPositiveButton("Close", null));
            return;
        }
        String[] messages = new String[patch.diagnostics.size()];
        for (int i = 0; i < messages.length; i++) {
            Diagnostic diagnostic = patch.diagnostics.get(i);
            messages[i] = (diagnostic.isError() ? "Error" : "Notice") + " · line " + diagnostic.line + "\n" + diagnostic.message;
        }
        showDialog(new AlertDialog.Builder(this).setTitle("Local diagnostics · open a line")
                .setItems(messages, (dialog, position) -> editSource(patch.diagnostics.get(position).line))
                .setNegativeButton("Close", null));
    }

    private void showOrphanDrafts() {
        if (orphanDrafts.isEmpty()) { toast("All retained drafts have been recovered."); return; }
        List<OrphanDraft> snapshot = new ArrayList<>(orphanDrafts);
        String[] choices = new String[snapshot.size()];
        for (int index = 0; index < choices.length; index++) {
            OrphanDraft orphan = snapshot.get(index);
            choices[index] = displayText(orphan.key, 160) + "\n" + displayText(orphan.draft.value, 128);
        }
        showDialog(new AlertDialog.Builder(this).setTitle("Retained drafts · choose a value")
                .setItems(choices, (dialog, position) -> {
                    OrphanDraft orphan = snapshot.get(position);
                    TextView value = label(orphan.draft.value, 16, TEXT);
                    value.setTextIsSelectable(true); value.setPadding(dp(16), dp(12), dp(16), dp(12));
                    ScrollView scroll = new ScrollView(this); scroll.addView(value);
                    LinearLayout body = vertical();
                    TextView explanation = label("The original circuit can no longer be identified reliably. Copy this value and paste it into the circuit you choose. This draft is kept until you discard it.", 14, TEXT);
                    explanation.setPadding(dp(16), dp(12), dp(16), dp(8)); body.addView(explanation);
                    body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(200)));
                    showDialog(new AlertDialog.Builder(this).setTitle(displayText(orphan.key, 160)).setView(body)
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Copy value", (detail, which) -> {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText(displayText(orphan.key, 160), orphan.draft.value)); toast("Value copied. The draft is still saved."); }
                            })
                            .setNeutralButton("Discard this draft", (detail, which) -> {
                                orphanDrafts.remove(orphan); requestUiSave(); updateDiagnostics();
                            }));
                }).setNegativeButton("Close", null));
    }

    private void scheduleAutosave(Context context, String snapshot) {
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = WORKER.schedule(() -> {
            try {
                writeAutosave(context, snapshot);
                deliver(() -> { saveError = ""; if (loaded && operations == 0) updateDiagnostics(); });
            }
            catch (IOException | OutOfMemoryError exception) { reportSaveError(exception); }
        }, 450, TimeUnit.MILLISECONDS);
    }

    private static String persistAppliedPatch(Context context, String snapshot) {
        if (pendingSave != null) pendingSave.cancel(false);
        try { writeAutosave(context, snapshot); return ""; }
        catch (IOException | OutOfMemoryError exception) { return errorMessage(exception); }
    }

    private void reportSaveError(Throwable exception) {
        deliver(() -> {
            saveError = errorMessage(exception);
            statusLabel.setText(R.string.forge_autosave_failed);
            statusLabel.setTextColor(RED);
            toast("Local autosave: " + errorMessage(exception));
        });
    }

    private static AtomicFile autosave(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "patch-autosave.ini"));
    }

    private static AtomicFile uiAutosave(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "patch-ui-state.json"));
    }

    private static AtomicFile uiTransition(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "droid-import-ui-transition.json"));
    }

    private void requestUiSave() {
        if (!loaded || destroyed || restoringUi) return;
        main.removeCallbacks(saveUi);
        main.postDelayed(saveUi, 450);
    }

    /** Capture current accepted drafts before replacing a document. Call on the UI thread. */
    private JSONObject captureUi() throws JSONException {
        if (sourceDialog != null) {
            View source = sourceDialog.findViewById(android.R.id.edit);
            if (source instanceof EditText) sourceSelection = ((EditText) source).getSelectionStart();
        }
        JSONObject state = new JSONObject();
        state.put("sourceHash", sourceFingerprint());
        state.put("documentRevision", activityDocumentRevision);
        JSONArray identities = new JSONArray();
        for (Block block : patch.blocks) {
            JSONObject identity = new JSONObject();
            identity.put("index", block.index); identity.put("name", block.name); identity.put("controller", block.controller);
            identity.put("signature", blockSignature(block));
            identities.put(identity);
        }
        state.put("blocks", identities);
        state.put("name", patchName); state.put("query", query);
        state.put("selected", selectedBlock); state.put("section", selectedSection); state.put("pane", activePane);
        state.put("initialSelection", initialSelection);
        state.put("rackZoom", rackViewport.zoom()); state.put("rackLeft", rackViewport.left()); state.put("rackTop", rackViewport.top());
        if (annotationDraft != null) state.put("annotationDraft", annotationDraft.toJson());
        JSONArray pages = new JSONArray();
        for (Map.Entry<Integer, Integer> page : parameterPages.entrySet())
            pages.put(new JSONObject().put("block", page.getKey()).put("page", page.getValue()));
        state.put("parameterPages", pages);
        if (pendingConnection != null) state.put("connection", pendingConnection.toJson());
        JSONArray folded = new JSONArray();
        for (int index : foldedBlocks) folded.put(index);
        state.put("folded", folded);
        JSONArray drafts = new JSONArray();
        for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) {
            JSONObject draft = new JSONObject();
            draft.put("key", entry.getKey()); draft.put("base", entry.getValue().base); draft.put("value", entry.getValue().value);
            if (entry.getValue().cells != null) draft.put("cells", cellsJson(entry.getValue().cells));
            drafts.put(draft);
        }
        state.put("drafts", drafts);
        JSONArray remembered = new JSONArray();
        for (Map.Entry<String, AppliedCells> entry : appliedCells.entrySet())
            remembered.put(new JSONObject().put("key", entry.getKey()).put("value", entry.getValue().value).put("cells", cellsJson(entry.getValue().cells)));
        state.put("appliedCells", remembered);
        JSONArray orphans = new JSONArray();
        for (OrphanDraft orphan : orphanDrafts) {
            JSONObject draft = new JSONObject();
            draft.put("key", orphan.key); draft.put("base", orphan.draft.base); draft.put("value", orphan.draft.value);
            if (orphan.draft.cells != null) draft.put("cells", cellsJson(orphan.draft.cells));
            orphans.put(draft);
        }
        state.put("orphanDrafts", orphans);
        if (sourceDraft != null && sourceSnapshot.equals(sourceDraftBase)) state.put("sourceDraft", sourceDraft);
        state.put("sourceLine", sourceLine); state.put("sourceSelection", sourceSelection);
        state.put("dialog", dialogType); state.put("dialogBlock", dialogBlock); state.put("dialogCircuit", dialogCircuit);
        if (cellDialog != null) state.put("cellDialog", cellDialog.toJson());
        state.put("newParameterName", newParameterName); state.put("newParameterValue", newParameterValue);
        return state;
    }

    /** Large editor drafts belong in a private file, never in Binder saved-state parcels. */
    private void rememberUi() {
        if (!loaded || destroyed || restoringUi) return;
        main.removeCallbacks(saveUi);
        try {
            JSONObject state = captureUi();
            Context application = getApplicationContext();
            WORKER.execute(() -> {
                // A rotating activity may still describe the document preceding an import.
                if (state.optLong("documentRevision", -1) != documentRevision) return;
                retainedUi = state;
                try {
                    writeAtomic(uiAutosave(application), state.toString());
                    try (InputStream input = uiAutosave(application).openRead()) {
                        if (!state.toString().equals(readUtf8(input, 32 * 1024 * 1024)))
                            throw new IOException("The saved drafts could not be verified.");
                    }
                    uiTransition(application).delete();
                    deliver(() -> { draftSaveError = ""; if (operations == 0) updateDiagnostics(); });
                } catch (IOException | OutOfMemoryError exception) {
                    deliver(() -> { draftSaveError = errorMessage(exception); if (operations == 0) updateDiagnostics(); });
                }
            });
        } catch (JSONException | OutOfMemoryError exception) {
            draftSaveError = errorMessage(exception);
            updateDiagnostics();
        }
    }

    private String sourceFingerprint() {
        if (fingerprintSource != sourceSnapshot) {
            sourceFingerprint = PatchTextCodec.sha256(sourceSnapshot);
            fingerprintSource = sourceSnapshot;
        }
        return sourceFingerprint;
    }

    private static JSONObject cellsJson(ForgeCells cells) throws JSONException {
        return new JSONObject().put("base", cells.base).put("factor", cells.factor).put("offset", cells.offset);
    }

    private static Draft readDraft(JSONObject json) {
        JSONObject cells = json.optJSONObject("cells");
        ForgeCells values = cells == null ? null : new ForgeCells(cells.optString("base"), cells.optString("factor"), cells.optString("offset"));
        return new Draft(json.optString("base"), json.optString("value"), values);
    }

    private void restoreUi(JSONObject state, boolean fromMemory) {
        if (state == null) return;
        boolean exactSource = sourceFingerprint().equals(state.optString("sourceHash"));
        boolean liveDocument = fromMemory && state.optLong("documentRevision", -1) == activityDocumentRevision;
        if (fromMemory && !liveDocument) return;
        if (!exactSource && !liveDocument) return;
        restoringUi = true;
        patchName = state.optString("name", patchName); query = state.optString("query", query);
        if (catalogueSearch != null) catalogueSearch.setText(query);
        selectedBlock = state.optInt("selected", selectedBlock); selectedSection = state.optInt("section", selectedSection);
        activePane = state.optInt("pane", activePane);
        initialSelection = Math.max(0, Math.min(3, state.optInt("initialSelection", 1)));
        rackViewport.restore((float) state.optDouble("rackZoom", 1), (float) state.optDouble("rackLeft", 0), (float) state.optDouble("rackTop", 0));
        annotationDraft = AnnotationDraft.fromJson(state.optJSONObject("annotationDraft"));
        parameterPages.clear();
        JSONArray pages = state.optJSONArray("parameterPages");
        if (pages != null) for (int index = 0; index < pages.length(); index++) {
            JSONObject page = pages.optJSONObject(index);
            if (page != null) parameterPages.put(page.optInt("block"), Math.max(0, page.optInt("page")));
        }
        pendingConnection = ConnectionPoint.fromJson(state.optJSONObject("connection"));
        appliedCells.clear();
        JSONArray remembered = exactSource ? state.optJSONArray("appliedCells") : null;
        long rememberedCharacters = 0;
        if (remembered != null && remembered.length() <= MAX_APPLIED_CELLS) for (int index = 0; index < remembered.length(); index++) {
            JSONObject entry = remembered.optJSONObject(index);
            if (entry == null) continue;
            JSONObject cells = entry.optJSONObject("cells");
            if (cells == null) continue;
            AppliedCells value = new AppliedCells(entry.optString("value"),
                    new ForgeCells(cells.optString("base"), cells.optString("factor"), cells.optString("offset")));
            if (!value.cells.hasEmptyBaseRoles()) continue;
            String key = entry.optString("key");
            rememberedCharacters += value.characters(key);
            if (rememberedCharacters > MAX_APPLIED_CHARACTERS) {
                appliedCells.clear(); draftSaveError = "The remembered cells exceed the recovery limit."; break;
            }
            appliedCells.put(key, value);
        }
        foldedBlocks.clear();
        JSONArray folded = state.optJSONArray("folded");
        if (folded != null) for (int i = 0; i < folded.length(); i++) foldedBlocks.add(folded.optInt(i, -1));
        editDrafts.clear();
        orphanDrafts.clear();
        JSONArray orphans = state.optJSONArray("orphanDrafts");
        if (orphans != null) for (int i = 0; i < orphans.length(); i++) {
            JSONObject draft = orphans.optJSONObject(i);
            if (draft != null) orphanDrafts.add(new OrphanDraft(draft.optString("key"), readDraft(draft)));
        }
        JSONArray drafts = state.optJSONArray("drafts");
        if (drafts != null) for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.optJSONObject(i);
            if (draft != null) editDrafts.put(draft.optString("key"), readDraft(draft));
        }
        dialogType = state.optString("dialog"); dialogBlock = state.optInt("dialogBlock", -1); dialogCircuit = state.optString("dialogCircuit");
        cellDialog = CellDialogState.fromJson(state.optJSONObject("cellDialog"));
        if (!exactSource) {
            try {
                int[] mapping = BLOCK_TRANSITIONS.lookup(state.optString("sourceHash"), sourceFingerprint());
                if (mapping == null) {
                    List<String> oldSignatures = new ArrayList<>();
                    JSONArray identities = state.optJSONArray("blocks");
                    if (identities != null) for (int index = 0; index < identities.length(); index++) {
                        JSONObject identity = identities.optJSONObject(index);
                        oldSignatures.add(identity == null || !identity.has("signature") ? null : identity.optString("signature"));
                    }
                    mapping = BlockTransitions.inferUnique(oldSignatures, blockSignatures(patch));
                }
                rebaseDrafts(new Patch(state), patch, mapping);
            } catch (JSONException ignored) {
                for (Map.Entry<String, Draft> entry : editDrafts.entrySet()) orphanDrafts.add(new OrphanDraft(entry.getKey(), entry.getValue()));
                editDrafts.clear(); foldedBlocks.clear(); selectedBlock = -1; dialogBlock = -1;
            }
        }
        if (exactSource && state.has("sourceDraft")) { sourceDraft = state.optString("sourceDraft"); sourceDraftBase = sourceSnapshot; }
        sourceLine = state.optInt("sourceLine"); sourceSelection = state.optInt("sourceSelection");
        newParameterName = state.optString("newParameterName"); newParameterValue = state.optString("newParameterValue");
        restoringUi = false;
    }

    private void restoreDialog() {
        String type = dialogType;
        if ("annotation".equals(type) && annotationDraft != null) showAnnotationDraft();
        else if (cellDialog != null && currentPoint(cellDialog.point)) {
            CellDialogState state = cellDialog;
            if ("raw_value".equals(type)) editRawValue(state.point);
            else if ("cable_name".equals(type)) chooseCableName(state.displayTitle(), state.existing, state.point, state.from, state.operation);
        } else if ("source".equals(type)) editSource(sourceLine);
        else if ("catalogue".equals(type)) showCatalogue();
        else if ("circuit".equals(type)) {
            Circuit circuit = circuitByName.get(dialogCircuit);
            if (circuit != null) showCircuitPreview(circuit);
        } else if ("parameter".equals(type) || "controller".equals(type)) {
            Block block = findBlock(dialogBlock);
            if (block != null) { if ("parameter".equals(type)) showParameterDialog(block); else showController(block); }
        }
    }

    private AlertDialog trackDialog(AlertDialog dialog) {
        openDialogs.add(dialog);
        return dialog;
    }

    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = trackDialog(builder.create());
        dialog.show();
        return dialog;
    }

    private void showAbout() {
        String version = "unknown version";
        try {
            String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (installed != null && !installed.trim().isEmpty()) version = installed;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // Retain an honest fallback if package metadata cannot be resolved.
        }
        showDialog(new AlertDialog.Builder(this).setTitle(getString(R.string.mobile_forge_name) + " · " + version)
                .setMessage(getString(R.string.mobile_about_text, patch.catalogueFirmware))
                .setNegativeButton("Close", null)
                .setPositiveButton("Credits and licenses", (dialog, which) -> showDialog(new AlertDialog.Builder(this)
                        .setTitle("Credits and licenses")
                        .setItems(new String[]{getString(R.string.mobile_android_credits), "DROID Forge · credits", "Code · GNU GPL version 3", "Official graphics", "C++ libraries · LLVM / Apache"}, (choice, index) -> {
                            if (index == 0) showLicence(getString(R.string.mobile_android_credits), "legal/MOBILE-CREDITS.txt");
                            else if (index == 1) showLicence("DROID Forge credits", "forge/CREDITS");
                            else if (index == 2) showLicence("GNU GPL version 3", "legal/GPL-3.0.txt");
                            else if (index == 3) showLicence("Graphics license", "legal/GRAPHICS-LICENSE.txt");
                            else showLicence("C++ runtime licenses", "legal/NDK-RUNTIME-LICENSES.txt");
                        }).setNegativeButton("Close", null))));
    }

    private void showLicence(String title, String asset) {
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            try (InputStream input = application.getAssets().open(asset)) {
                String text = readUtf8(input);
                deliver(() -> {
                    ScrollView scroll = new ScrollView(this);
                    TextView licence = label(text, 12, TEXT);
                    licence.setTextIsSelectable(true);
                    licence.setPadding(dp(18), dp(12), dp(18), dp(12));
                    scroll.addView(licence);
                    showDialog(new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                            .setPositiveButton("Close", null));
                });
            } catch (IOException | OutOfMemoryError exception) {
                deliver(() -> showError("License unavailable", errorMessage(exception)));
            }
        });
    }

    private static void writeAutosave(Context context, String snapshot) throws IOException {
        writeAtomic(autosave(context), snapshot);
    }

    private static void writeAtomic(AtomicFile file, String snapshot) throws IOException {
        java.io.FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(PatchTextCodec.encode(snapshot));
            file.finishWrite(output);
        } catch (IOException | OutOfMemoryError exception) {
            if (output != null) file.failWrite(output);
            throw exception;
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        return readUtf8(input, MAX_FILE_BYTES);
    }

    private static String readUtf8(InputStream input, int limit) throws IOException {
        return PatchTextCodec.read(input, limit);
    }

    private static String documentName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return safeDocumentName(name);
            }
        } catch (Exception ignored) { /* Some document providers do not expose a name. */ }
        return "droid.ini";
    }

    private static String safeDocumentName(String name) {
        String safe = name.trim().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (safe.length() > 240) {
            int end = Character.isHighSurrogate(safe.charAt(235)) ? 235 : 236;
            safe = safe.substring(0, end) + ".ini";
        }
        return safe.isEmpty() ? "droid.ini" : safe;
    }

    private static Bitmap loadAssetBitmap(Context context, String path) {
        // Asset names come from the bundled official schema, never from imported patch text.
        if (path.length() > 100 || !path.matches("(?:modules|circuits|forge/cables)/[a-zA-Z0-9_-]+\\.png")) return null;
        Bitmap cached = IMAGES.get(path);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(path)) { BitmapFactory.decodeStream(input, null, bounds); }
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            int maxHeight = path.startsWith("modules/") ? 1100 : 640;
            while (bounds.outHeight / sample > maxHeight || bounds.outWidth / sample > 1000) sample *= 2;
            options.inSampleSize = sample;
            try (InputStream input = context.getAssets().open(path)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap != null) IMAGES.put(path, bitmap);
                return bitmap;
            }
        } catch (IOException | OutOfMemoryError ignored) { return null; }
    }

    private void deliver(Runnable runnable) { main.post(() -> { if (!destroyed) runnable.run(); }); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void showError(String title, String message) {
        if (!destroyed && !isFinishing()) showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Close", null));
    }
    private static String errorMessage(Throwable exception) {
        if (exception instanceof OutOfMemoryError) return "Not enough memory to complete this operation. Export the preserved source and restart the app if needed.";
        String message = exception.getMessage();
        return message == null || message.isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private Block findBlock(int index) {
        for (Block block : patch.blocks) if (block.index == index) return block;
        return null;
    }

    private void addSection(String text) {
        TextView heading = label(text, 14, COPPER);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(0, dp(18), 0, dp(10));
        inspectorBody.addView(heading);
    }
    private void addParagraph(String text) {
        TextView paragraph = label(text, 13, MUTED);
        paragraph.setPadding(0, dp(8), 0, dp(12));
        inspectorBody.addView(paragraph);
    }
    private LinearLayout parameterCard() {
        LinearLayout card = vertical();
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setBackground(background(PANEL, BORDER, 0));
        return card;
    }
    private LinearLayout.LayoutParams spacedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        return params;
    }
    private LinearLayout panel() {
        LinearLayout panel = vertical();
        panel.setPadding(dp(8), dp(2), dp(8), dp(8));
        panel.setBackgroundColor(BG);
        return panel;
    }
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }
    private TextView label(String text, float size, int color) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        return label;
    }
    private static String displayText(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
    private EditText edit(String hint) {
        EditText edit = new CableEditText(this);
        edit.setTextColor(TEXT); edit.setHintTextColor(MUTED); edit.setTextSize(14);
        edit.setHint(hint); edit.setMinHeight(dp(48));
        edit.setPadding(dp(10), dp(6), dp(10), dp(6));
        edit.setBackground(background(0xff282828, BORDER, 0));
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setFilters(new InputFilter[]{(source, start, end, destination, replaceStart, replaceEnd) -> {
            if (destination.length() - (replaceEnd - replaceStart) + (end - start) <= MAX_EDITOR_CHARACTERS) return null;
            edit.setError("This field is limited to 4,096 characters. Use the source editor for a longer value.");
            return destination.subSequence(replaceStart, replaceEnd);
        }});
        return edit;
    }
    private Button action(String text, String description, boolean engineAction) {
        Button button = new Button(this);
        button.setText(text); button.setTextColor(CYAN); button.setTextSize(14);
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), background(RAISED, BORDER, 0), null));
        button.setContentDescription(description);
        if (engineAction) engineButtons.add(button);
        return button;
    }
    private ListView list() {
        return list(false);
    }

    private ListView list(boolean editing) {
        ListView list = editing ? new EditingListView(this) : new ListView(this);
        list.setDividerHeight(dp(10));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setSelector(background(RAISED, TEXT, 0));
        list.setCacheColorHint(Color.TRANSPARENT);
        return list;
    }
    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        if (sourceDialog != null || previewDialog != null || catalogueDialog != null) return super.onKeyShortcut(keyCode, event);
        if (event.isCtrlPressed() && loaded && operations == 0) {
            if (keyCode == KeyEvent.KEYCODE_Z) {
                mutateHistory(event.isShiftPressed());
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_Y) { mutateHistory(true); return true; }
            if (keyCode == KeyEvent.KEYCODE_O) { openPatch(); return true; }
            if (keyCode == KeyEvent.KEYCODE_S) { confirmExport(); return true; }
        }
        return super.onKeyShortcut(keyCode, event);
    }

    private static final class Section {
        final String title;
        final int startLine;
        final List<Block> blocks = new ArrayList<>();
        Section(String title, int startLine) { this.title = title; this.startLine = startLine; }
    }

    private static final class Draft {
        final String base, value;
        final ForgeCells cells;
        Draft(String base, String value) { this(base, value, null); }
        Draft(String base, String value, ForgeCells cells) { this.base = base; this.value = value; this.cells = cells; }
        long characters(String key) { return (long) key.length() + base.length() + value.length() + (cells == null ? 0 : cells.characters()); }
    }

    private static final class AppliedCells {
        final String value;
        final ForgeCells cells;
        AppliedCells(String value, ForgeCells cells) { this.value = value; this.cells = cells; }
        long characters(String key) { return (long) key.length() + value.length() + cells.characters(); }
    }

    private static final class AnnotationDraft {
        final String kind, moduleName, register, baseFirst, baseSecond;
        final int controller;
        int block;
        String first, second;
        boolean orphan;
        AnnotationDraft(String kind, int block, String moduleName, int controller, String register, String first, String second) {
            this.kind = kind; this.block = block; this.moduleName = moduleName; this.controller = controller; this.register = register;
            baseFirst = first; baseSecond = second; this.first = first; this.second = second;
        }
        boolean matches(String kind, int block, String register) { return this.kind.equals(kind) && this.block == block && this.register.equals(register) && !orphan; }
        boolean dirty() { return !first.equals(baseFirst) || !second.equals(baseSecond); }
        String readable() { return ("label".equals(kind) ? register : "[" + moduleName + "]") + "\n" + first + (second.isEmpty() ? "" : "\n" + second); }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("kind", kind).put("block", block).put("module", moduleName).put("controller", controller)
                    .put("register", register).put("baseFirst", baseFirst).put("baseSecond", baseSecond)
                    .put("first", first).put("second", second).put("orphan", orphan);
        }
        static AnnotationDraft fromJson(JSONObject state) {
            if (state == null) return null;
            String kind = state.optString("kind"), first = state.optString("first"), second = state.optString("second");
            if (!"label".equals(kind) && !"comment".equals(kind) || first.length() > 16384 || second.length() > 4096
                    || state.optString("baseFirst").length() > 16384 || state.optString("baseSecond").length() > 4096) return null;
            AnnotationDraft draft = new AnnotationDraft(kind, state.optInt("block", -1), state.optString("module"),
                    state.optInt("controller"), state.optString("register"), state.optString("baseFirst"), state.optString("baseSecond"));
            draft.first = first; draft.second = second; draft.orphan = state.optBoolean("orphan");
            return draft;
        }
    }

    private static final class ConnectionPoint {
        final int block, column;
        final String blockName, parameter, direction;
        ConnectionPoint(int block, String blockName, String parameter, int column, String direction) {
            this.block = block; this.blockName = blockName; this.parameter = parameter; this.column = column; this.direction = direction;
        }
        ConnectionPoint atBlock(int index) { return new ConnectionPoint(index, blockName, parameter, column, direction); }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("block", block).put("blockName", blockName).put("parameter", parameter).put("column", column).put("direction", direction);
        }
        static ConnectionPoint fromJson(JSONObject json) {
            if (json == null || json.optInt("column", -1) < 0 || json.optInt("column", -1) > 2) return null;
            return new ConnectionPoint(json.optInt("block", -1), json.optString("blockName"), json.optString("parameter"), json.optInt("column"), json.optString("direction"));
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ConnectionPoint)) return false;
            ConnectionPoint point = (ConnectionPoint) other;
            return block == point.block && column == point.column && blockName.equals(point.blockName) && parameter.equals(point.parameter) && direction.equals(point.direction);
        }
        @Override public int hashCode() { return java.util.Objects.hash(block, blockName, parameter, column, direction); }
    }

    private static final class CellDialogState {
        final String operation, title, existing;
        String name;
        ConnectionPoint point, from;
        CellDialogState(String operation, String title, String existing, ConnectionPoint point, ConnectionPoint from) {
            this.operation = operation; this.title = title; this.existing = existing; this.point = point; this.from = from;
            name = existing.startsWith("_") ? existing.substring(1) : existing;
        }
        String displayTitle() {
            if ("rename".equals(operation)) return "Rename cable " + existing;
            return "connect".equals(operation) ? "New cable name" : "Create a cable";
        }
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject().put("operation", operation).put("title", title).put("existing", existing)
                    .put("name", name).put("point", point.toJson());
            if (from != null) json.put("from", from.toJson());
            return json;
        }
        static CellDialogState fromJson(JSONObject json) {
            if (json == null) return null;
            ConnectionPoint point = ConnectionPoint.fromJson(json.optJSONObject("point"));
            if (point == null) return null;
            CellDialogState state = new CellDialogState(json.optString("operation"), json.optString("title"), json.optString("existing"),
                    point, ConnectionPoint.fromJson(json.optJSONObject("from")));
            if (!"raw".equals(state.operation) && !"create".equals(state.operation) && !"rename".equals(state.operation) && !"connect".equals(state.operation)) return null;
            if ("connect".equals(state.operation) && state.from == null) return null;
            state.name = json.optString("name");
            return state;
        }
    }

    private static final class OrphanDraft {
        final String key;
        final Draft draft;
        OrphanDraft(String key, Draft draft) { this.key = key; this.draft = draft; }
    }

    private final class SectionAdapter extends BaseAdapter {
        @Override public int getCount() { return sections.size(); }
        @Override public Section getItem(int position) { return sections.get(position); }
        @Override public long getItemId(int position) { return getItem(position).startLine; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = vertical();
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setMinimumHeight(dp(64));
            Section section = getItem(position);
            TextView title = label(section.title, 15, TEXT);
            title.setMaxLines(3);
            row.addView(title);
            row.addView(label(getResources().getQuantityString(R.plurals.forge_circuit_count, section.blocks.size(), section.blocks.size()), 11, MUTED));
            row.setBackground(background(BG, position == selectedSection ? TEXT : BORDER, 0));
            row.setContentDescription("Section " + section.title + ", " + section.blocks.size() + " circuits");
            return row;
        }
    }

    /** Recycles complete visible cards; large patches do not construct off-screen editors. */
    private final class CircuitCardAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, visibleBlocks.size()); }
        @Override public Block getItem(int position) { return visibleBlocks.isEmpty() ? null : visibleBlocks.get(position); }
        @Override public long getItemId(int position) { return visibleBlocks.isEmpty() ? -1 : getItem(position).index; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card = convertView instanceof LinearLayout ? (LinearLayout) convertView : vertical();
            Block bound = getItem(position);
            String binding = cardGeneration + ":" + (bound == null ? "empty" : bound.index + ":" + foldedBlocks.contains(bound.index));
            if (bound != null) binding += ":" + parameterPages.getOrDefault(bound.index, 0);
            if (binding.equals(card.getTag())) {
                if (bound != null && card.getChildCount() > 0)
                    card.getChildAt(0).setBackground(background(RAISED, bound.index == selectedBlock ? TEXT : RAISED, 0));
                enableEditors(card, loaded && operations == 0);
                return card;
            }
            card.removeAllViews();
            card.setTag(binding);
            card.setPadding(0, 0, 0, 0);
            card.setBackgroundColor(PANEL);
            if (visibleBlocks.isEmpty()) {
                TextView empty = label("This section has no circuits.\nUse the Circuit button to add one.", 15, MUTED);
                empty.setPadding(dp(16), dp(24), dp(16), dp(24));
                card.addView(empty);
                return card;
            }
            Block block = getItem(position);
            Circuit circuit = circuitByName.get(block.name);
            LinearLayout heading = horizontal();
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setBackground(background(RAISED, block.index == selectedBlock ? TEXT : RAISED, 0));
            ImageView icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setContentDescription("Icon for circuit " + displayText(block.name, 128));
            heading.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            setCircuitIcon(circuit == null ? block.name : circuit.name, icon);
            TextView name = label(getString(R.string.forge_block_heading, displayText(block.name.toUpperCase(Locale.ROOT), 128), block.disabled ? getString(R.string.forge_disabled) : ""), 16, block.disabled ? MUTED : TEXT);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setPadding(dp(8), 0, dp(4), 0);
            name.setContentDescription("Circuit " + displayText(block.name, 128) + ", line " + block.line);
            heading.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button fold = action(foldedBlocks.contains(block.index) ? "▸" : "▾", "Collapse or expand circuit " + displayText(block.name, 128), false);
            fold.setTextColor(TEXT);
            fold.setOnClickListener(v -> {
                if (!foldedBlocks.remove(block.index)) foldedBlocks.add(block.index);
                notifyDataSetChanged();
            });
            heading.addView(fold, new LinearLayout.LayoutParams(dp(48), dp(48)));
            Button more = action("⋮", "Actions for circuit " + displayText(block.name, 128), false);
            more.setTextColor(TEXT);
            more.setOnClickListener(v -> showBlockMenu(v, block));
            heading.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            name.setOnClickListener(v -> {
                selectedBlock = block.index;
                heading.setBackground(background(RAISED, TEXT, 0));
            });
            card.addView(heading);
            if (foldedBlocks.contains(block.index)) return card;

            String comment = commentExcerpt(block.comment);
            if (!comment.isEmpty()) {
                TextView note = label(comment, 13, MUTED);
                note.setPadding(dp(10), dp(8), dp(10), dp(8));
                note.setBackgroundColor(0xff303030);
                card.addView(note);
            }
            Map<String, Parameter> effective = new LinkedHashMap<>();
            Map<String, Integer> occurrences = new HashMap<>();
            for (Parameter parameter : block.parameters) {
                Parameter previous = effective.get(parameter.name);
                if (previous == null || !parameter.disabled || previous.disabled) effective.put(parameter.name, parameter);
                occurrences.put(parameter.name, occurrences.getOrDefault(parameter.name, 0) + 1);
            }
            int width = getResources().getConfiguration().screenWidthDp;
            int nameWidth = width >= 1000 ? 172 : width >= 640 ? 120 : 86;
            int pageCount = Math.max(1, (effective.size() + PARAMETER_PAGE_SIZE - 1) / PARAMETER_PAGE_SIZE);
            int parameterPage = Math.max(0, Math.min(pageCount - 1, parameterPages.getOrDefault(block.index, 0)));
            int parameterOrdinal = 0;
            for (Parameter parameter : effective.values()) {
                int ordinal = parameterOrdinal++;
                if (ordinal < parameterPage * PARAMETER_PAGE_SIZE || ordinal >= (parameterPage + 1) * PARAMETER_PAGE_SIZE) continue;
                Definition definition = circuit == null ? null : circuit.find(parameter.name);
                LinearLayout row = horizontal();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundColor(0xff282828);
                TextView jack = label(displayText(parameter.name, 128), 14, parameterColor(definition, parameter.disabled || block.disabled));
                jack.setPadding(dp(10), 0, dp(4), 0);
                jack.setMaxLines(2);
                row.addView(jack, new LinearLayout.LayoutParams(dp(nameWidth), dp(48)));
                TextView equals = label("=", 16, MUTED);
                equals.setGravity(Gravity.CENTER);
                row.addView(equals, new LinearLayout.LayoutParams(dp(22), dp(48)));
                if (parameter.value.length() > MAX_EDITOR_CHARACTERS || parameter.name.length() > 256 || block.name.length() > 256) {
                    TextView longValue = label(displayText(parameter.value, 256), 13, MUTED);
                    longValue.setMaxLines(2);
                    longValue.setContentDescription("The full long value is preserved. Edit it in the source.");
                    row.addView(longValue, new LinearLayout.LayoutParams(0, dp(48), 1));
                    Button source = action("Source", "Edit this long value in the source", false);
                    source.setOnClickListener(v -> editSource(block.line));
                    row.addView(source, new LinearLayout.LayoutParams(dp(80), dp(48)));
                    card.addView(row);
                    continue;
                }
                renderValueCells(row, block, parameter);
                jack.setOnClickListener(v -> showDialog(new AlertDialog.Builder(MainActivity.this).setTitle(displayText(parameter.name, 256))
                        .setMessage((definition == null ? "Undocumented parameter." : parameterMeta(definition)
                                + "\n\n" + definition.description) + (parameter.disabled ? "\n\nDisabled in the patch source." : "")
                                + (occurrences.get(parameter.name) > 1 ? "\n\nMultiple occurrences: editing the last active one. Use the source editor for the others." : ""))
                        .setPositiveButton("Close", null)));
                card.addView(row);
                View line = new View(MainActivity.this); line.setBackgroundColor(0xff353535);
                card.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            if (pageCount > 1) {
                LinearLayout paging = horizontal();
                paging.setGravity(Gravity.CENTER_VERTICAL);
                Button previous = action("‹", "Previous parameters of " + displayText(block.name, 128), false);
                previous.setEnabled(parameterPage > 0);
                previous.setOnClickListener(v -> { parameterPages.put(block.index, parameterPage - 1); notifyDataSetChanged(); });
                paging.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
                TextView count = label(getString(R.string.forge_parameter_page, parameterPage * PARAMETER_PAGE_SIZE + 1,
                        Math.min(effective.size(), (parameterPage + 1) * PARAMETER_PAGE_SIZE), effective.size()), 12, MUTED);
                count.setGravity(Gravity.CENTER);
                paging.addView(count, new LinearLayout.LayoutParams(0, dp(48), 1));
                Button next = action("›", "Next parameters of " + displayText(block.name, 128), false);
                next.setEnabled(parameterPage + 1 < pageCount);
                next.setOnClickListener(v -> { parameterPages.put(block.index, parameterPage + 1); notifyDataSetChanged(); });
                paging.addView(next, new LinearLayout.LayoutParams(dp(48), dp(48)));
                card.addView(paging);
            }
            if (effective.isEmpty()) {
                Button add = action("+ Parameter", "Add a parameter to circuit " + displayText(block.name, 128), false);
                add.setOnClickListener(v -> { selectedBlock = block.index; showParameterDialog(block); });
                card.addView(add, new LinearLayout.LayoutParams(-1, dp(48)));
            }
            return card;
        }
    }

    private String commentExcerpt(String text) {
        int end = 0, lines = 1;
        while (end < text.length() && end < 4096 && lines <= 24) { if (text.charAt(end) == '\n') lines++; end++; }
        if (end == text.length()) return text;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n" + getString(R.string.mobile_comment_excerpt);
    }

    private void setCircuitIcon(String name, ImageView image) {
        if (name.length() > 80) { image.setImageDrawable(null); image.setTag(null); return; }
        String asset = "circuits/" + name + ".png";
        image.setTag(asset);
        image.setImageDrawable(null);
        Bitmap cached = IMAGES.get(asset);
        if (cached != null) { image.setImageBitmap(cached); return; }
        Context application = getApplicationContext();
        WORKER.execute(() -> {
            Bitmap bitmap = loadAssetBitmap(application, asset);
            if (bitmap != null) deliver(() -> { if (asset.equals(image.getTag())) image.setImageBitmap(bitmap); });
        });
    }

    private final class CatalogueAdapter extends BaseAdapter {
        @Override public int getCount() { return filteredCatalogue.size(); }
        @Override public Circuit getItem(int position) { return filteredCatalogue.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                row = new Row(true); convertView = row.view; convertView.setTag(row);
            } else row = (Row) convertView.getTag();
            Circuit circuit = getItem(position);
            row.title.setText(circuit.name.toUpperCase(Locale.ROOT));
            row.subtitle.setText(circuit.title.isEmpty() ? circuit.category : circuit.title);
            row.detail.setText(circuit.description.isEmpty() ? circuit.category : circuit.description);
            setCircuitIcon(circuit.name, row.icon);
            row.plus.setEnabled(loaded && operations == 0 && patch.analysisComplete && !recoveryRequired);
            row.plus.setContentDescription("Add " + circuit.name + " to the patch");
            row.plus.setOnClickListener(v -> addCircuit(circuit));
            boolean selected = selectedCircuit == circuit;
            row.view.setBackground(background(selected ? RAISED : PANEL, selected ? TEXT : BORDER, 0));
            return convertView;
        }
    }

    private final class BlockAdapter extends BaseAdapter {
        @Override public int getCount() { return patch.blocks.size(); }
        @Override public Block getItem(int position) { return patch.blocks.get(position); }
        @Override public long getItemId(int position) { return getItem(position).index; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                row = new Row(false); convertView = row.view; convertView.setTag(row);
            } else row = (Row) convertView.getTag();
            Block block = getItem(position);
            row.title.setText(getString(R.string.forge_indexed_block, block.index + 1, displayText(block.name, 256)));
            Circuit circuit = circuitByName.get(block.name);
            row.subtitle.setText(circuit == null ? getString(block.controller ? R.string.forge_controller : R.string.forge_patch_block) : circuit.title);
            row.detail.setText(getResources().getQuantityString(R.plurals.forge_parameter_details, block.parameters.size(),
                    block.parameters.size(), block.line, block.disabled ? getString(R.string.forge_disabled) : ""));
            row.title.setTextColor(block.disabled ? MUTED : TEXT);
            boolean selected = block.index == selectedBlock;
            row.view.setBackground(background(selected ? RAISED : PANEL, selected ? COPPER : BORDER, 8));
            return convertView;
        }
    }

    private final class Row {
        final LinearLayout view;
        final TextView title, subtitle, detail;
        final Button plus;
        final ImageView icon;
        Row(boolean catalogueRow) {
            view = horizontal(); view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(10), dp(10), dp(6), dp(10)); view.setMinimumHeight(dp(86));
            if (catalogueRow) {
                icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                view.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            } else icon = null;
            LinearLayout text = vertical();
            title = label("", 15, TEXT); title.setTypeface(Typeface.MONOSPACE);
            subtitle = label("", 12, MUTED); subtitle.setMaxLines(2);
            detail = label("", 11, MUTED); detail.setMaxLines(catalogueRow ? 2 : 1);
            text.addView(title); text.addView(subtitle); text.addView(detail);
            view.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            if (catalogueRow) {
                plus = action("+", "Add a circuit", false);
                plus.setFocusable(false);
                view.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
            } else plus = null;
        }
    }

    private static List<Circuit> parseCatalogue(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Circuit> circuits = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) circuits.add(new Circuit(array.getJSONObject(i)));
        return circuits;
    }

    private static Patch parsePatch(String json) throws JSONException { return new Patch(new JSONObject(json)); }

    private static final class Patch {
        final String firmware, catalogueFirmware;
        final int masterType;
        final boolean canUndo, canRedo, analysisComplete, rackComplete;
        final List<Block> blocks = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<RackModule> rackModules = new ArrayList<>();
        final Map<String, RegisterLabel> registerLabels = new LinkedHashMap<>();
        Patch(JSONObject json) throws JSONException {
            firmware = json.optString("firmware", "");
            catalogueFirmware = json.optString("catalogueFirmware", firmware);
            masterType = json.optInt("masterType", 16);
            canUndo = json.optBoolean("canUndo"); canRedo = json.optBoolean("canRedo");
            analysisComplete = json.optBoolean("analysisComplete", true);
            rackComplete = json.optBoolean("rackComplete", true);
            JSONArray blockArray = json.optJSONArray("blocks");
            if (blockArray != null) for (int i = 0; i < blockArray.length(); i++) blocks.add(new Block(blockArray.getJSONObject(i)));
            JSONArray diagnosticArray = json.optJSONArray("diagnostics");
            if (diagnosticArray != null) for (int i = 0; i < diagnosticArray.length(); i++) diagnostics.add(new Diagnostic(diagnosticArray.getJSONObject(i)));
            JSONArray moduleArray = json.optJSONArray("rackModules");
            if (moduleArray != null) for (int index = 0; index < moduleArray.length(); index++) rackModules.add(new RackModule(moduleArray.getJSONObject(index)));
            JSONArray labelArray = json.optJSONArray("registerLabels");
            if (labelArray != null) for (int index = 0; index < labelArray.length(); index++) {
                RegisterLabel label = new RegisterLabel(labelArray.getJSONObject(index)); registerLabels.put(label.register, label);
            }
        }
        static Patch empty() {
            try { return new Patch(new JSONObject()); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        int errorCount() { int count = 0; for (Diagnostic diagnostic : diagnostics) if (diagnostic.isError()) count++; return count; }
    }

    private static final class Block {
        final int index, line;
        final String name, comment;
        final boolean controller, disabled, commentEditable;
        final List<Parameter> parameters = new ArrayList<>();
        final LinkedHashMap<String, Parameter> effective = new LinkedHashMap<>();
        Block(JSONObject json) throws JSONException {
            index = json.getInt("index"); line = json.optInt("line"); name = json.getString("name");
            comment = json.optString("comment");
            commentEditable = json.optBoolean("commentEditable", true);
            controller = json.optBoolean("controller"); disabled = json.optBoolean("disabled");
            JSONArray array = json.optJSONArray("parameters");
            if (array != null) for (int i = 0; i < array.length(); i++) {
                Parameter parameter = new Parameter(array.getJSONObject(i));
                parameters.add(parameter);
                Parameter previous = effective.get(parameter.name);
                if (previous == null || !parameter.disabled || previous.disabled) effective.put(parameter.name, parameter);
            }
        }
        boolean hasParameter(String name) { for (Parameter parameter : parameters) if (parameter.name.equals(name)) return true; return false; }
    }

    private static final class RackModule {
        final String name;
        final int block, controller, g8, rgbOffset;
        RackModule(JSONObject json) {
            name = json.optString("name"); block = json.optInt("block", -1); controller = json.optInt("controller");
            g8 = json.optInt("g8"); rgbOffset = json.optInt("rgbOffset");
        }
    }

    private static final class RegisterLabel {
        final String register, shortName, description;
        RegisterLabel(JSONObject json) { register = json.optString("register"); shortName = json.optString("short"); description = json.optString("description"); }
    }

    private static final class Example {
        final String title, file, category, firmware, sha256, kind, source, sourcePath, note;
        final int page;
        final boolean contextRequired;
        Example(JSONObject json, String firmware) {
            title = displayText(json.optString("title", json.optString("id")), 256);
            file = json.optString("file"); category = json.optString("category", "Manual");
            this.firmware = firmware; sha256 = json.optString("sha256"); page = json.optInt("page");
            kind = json.optString("kind"); source = displayText(json.optString("source"), 512);
            sourcePath = displayText(json.optString("source_path"), 512); note = displayText(json.optString("note"), 1024);
            contextRequired = json.optBoolean("context_required") || !"full".equals(kind);
        }
    }

    private static final class Parameter {
        final String name, value, direction;
        final int line;
        final boolean disabled, editable;
        final ForgeCells cells;
        Parameter(JSONObject json) throws JSONException {
            line = json.optInt("line");
            name = json.getString("name"); value = json.optString("value", ""); disabled = json.optBoolean("disabled");
            direction = json.optString("direction", "unknown");
            JSONObject expression = json.optJSONObject("expression");
            editable = expression != null && expression.optBoolean("editable");
            cells = expression == null ? null : new ForgeCells(expression.optString("base"), expression.optString("factor"), expression.optString("offset"));
        }
    }

    private static final class Diagnostic {
        final int line;
        final String severity, message;
        Diagnostic(JSONObject json) { line = json.optInt("line"); severity = json.optString("severity"); message = json.optString("message"); }
        boolean isError() { return severity.equalsIgnoreCase("error") || severity.equalsIgnoreCase("erreur"); }
    }

    private static final class Circuit {
        final String name, shortName, title, category, description;
        final int manualPage;
        final List<Definition> parameters = new ArrayList<>();
        Circuit(JSONObject json) throws JSONException {
            name = json.getString("name"); title = json.optString("title", name); category = json.optString("category", "Circuits");
            shortName = json.optString("short");
            description = json.optString("description", ""); manualPage = json.optInt("manualPage");
            JSONArray array = json.optJSONArray("parameters");
            if (array != null) for (int i = 0; i < array.length(); i++) parameters.add(new Definition(array.getJSONObject(i)));
        }
        Definition find(String name) {
            for (Definition definition : parameters)
                if (definition.name.equals(name) || (!definition.shortName.isEmpty() && definition.shortName.equals(name))) return definition;
            return null;
        }
    }

    private static final class Definition {
        final String name, shortName, type, direction, defaultValue, description;
        Definition(JSONObject json) throws JSONException {
            name = json.getString("name"); type = json.optString("type"); direction = json.optString("direction");
            shortName = json.optString("short");
            defaultValue = json.optString("default", ""); description = json.optString("description", "");
        }
    }
}
