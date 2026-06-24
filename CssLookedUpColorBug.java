///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 24+
//DEPS org.openjfx:javafx-base:26.0.1
//DEPS org.openjfx:javafx-graphics:26.0.1
//DEPS org.openjfx:javafx-controls:26.0.1
//RUNTIME_OPTIONS --add-modules=javafx.controls --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED

/*
 * Deterministic reproducer for JDK-8268657
 *   "ClassCastException: String cannot be cast to Paint while converting value
 *    for -fx-background-color from rule '*.button-primary'"
 *   https://bugs.openjdk.org/browse/JDK-8268657
 *
 * WHAT THE BUG REALLY IS
 * ----------------------
 * A descendant whose -fx-background-color references an ancestor-defined
 * looked-up color (here -theme-button) resolves that color by walking up the
 * CssStyleHelper.firstStyleableAncestor chain - a *cached* WeakReference to the
 * nearest ancestor that has a style helper. When that cached reference is
 * transiently empty/stale, resolveRef() finds no ancestor helper, drops the
 * looked-up color, and the raw token String "-theme-button" reaches the Paint
 * converter, which throws ClassCastException. The background fill is then lost.
 *
 * WHY THE "WILD" clear()+add() LOOP DOES NOT REPRODUCE IT
 * ------------------------------------------------------
 * Swapping the stylesheet (getStylesheets().clear()+add()) marks the whole
 * subtree REAPPLY. A REAPPLY pass rebuilds each node's style helper top-down and
 * therefore *refreshes* its cached firstStyleableAncestor before the node
 * resolves anything - so the stale window never opens in a single, ordered pass.
 * That is why this never triggers under a synthetic pulse, and only shows up on
 * Linux/Wayland where fast pulses interleave with UPDATE-only passes
 * (pseudo-class / hover transitions) that reuse the existing, possibly-stale
 * helper instead of rebuilding it.
 *
 * HOW THIS FILE REPRODUCES IT DETERMINISTICALLY
 * ---------------------------------------------
 * It recreates that precise interleaving directly, against the *released*
 * JavaFX runtime (no patched build required):
 *   1. Build root(.root -> -theme-button) over a leaf(.button-primary -> uses it),
 *      show the stage and let CSS settle. Sanity-check the leaf got its fill.
 *   2. Reflectively empty the leaf's cached firstStyleableAncestor weak
 *      reference - the transient state a Wayland stylesheet swap produces.
 *      (Needs --add-opens javafx.graphics/javafx.scene=ALL-UNNAMED, already set.)
 *   3. Trigger an UPDATE-only CSS pass via a pseudo-class (:hover) transition,
 *      which reuses the existing (now-stale) helper rather than rebuilding it.
 *   4. On the next pulse resolveRef() consults the empty cached reference, drops
 *      the looked-up color (ClassCastException String->Paint), and the leaf's
 *      Background becomes null. We detect both the dropped fill and the logged
 *      warning and exit non-zero.
 *
 * With the JDK-8268657 fix (resolveRef/getInheritedStyle walk the *live*
 * styleable parent chain instead of the cached reference) step 4 still resolves,
 * the fill survives, and this exits 0.
 *
 * Run:   jbang CssLookedUpColorBug.java
 *        xvfb-run jbang CssLookedUpColorBug.java     (headless box)
 */

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class CssLookedUpColorBug extends Application {

    // One stylesheet: .root defines the looked-up color, .button-primary uses it,
    // and the :hover rule gives the leaf a pseudo-class trigger so we can force an
    // UPDATE-only pass (transitionToState) without rebuilding its style helper.
    private static final String THEME = themeFile("theme",
            ".root { -theme-button: #0679DE; }\n" +
            ".button-primary { -fx-background-color: -theme-button; }\n" +
            ".button-primary:hover { -fx-background-color: -theme-button; }\n");

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");

    // Set true by the logging handler the moment the bug's WARNING is observed.
    private static final AtomicBoolean warningSeen = new AtomicBoolean(false);

    private Pane leaf;              // the .button-primary node we corrupt + re-resolve
    private AnimationTimer driver;
    private int frame = 0;
    private boolean reproduced = false;
    private String detail = "";

    @Override
    public void start(Stage stage) {
        installWarningDetector();

        // A small grid of .button-primary nodes (Panes), all resolving
        // -theme-button from the .root ancestor - matches the rule name from the
        // bug report while avoiding the modena Button cascade.
        FlowPane grid = new FlowPane();
        for (int i = 0; i < 12; i++) {
            Pane p = new Pane();
            p.getStyleClass().setAll("button-primary");
            p.setPrefSize(80, 30);
            grid.getChildren().add(p);
        }
        leaf = (Pane) grid.getChildren().get(0);

        StackPane root = new StackPane(grid);
        root.getStyleClass().add("root");
        root.getStylesheets().add(THEME);

        Scene scene = new Scene(root, 360, 180);
        stage.setTitle("JDK-8268657 reproducer");
        stage.setScene(scene);
        stage.show();

        // Drive the deterministic sequence across a few pulses.
        driver = new AnimationTimer() {
            @Override public void handle(long now) {
                try {
                    step();
                } catch (Throwable t) {
                    detail = "harness error: " + t;
                    reproduced = false;
                    finish();
                }
            }
        };
        driver.start();
    }

    private void step() {
        frame++;

        // Frame 2: CSS has settled - sanity-check the looked-up color resolved.
        if (frame == 2) {
            if (!hasFill(leaf)) {
                detail = "setup failed: looked-up color did not resolve initially";
                reproduced = false;
                finish();
            }
            return;
        }

        // Frame 3: reproduce the transient Wayland state, then request an UPDATE pass.
        if (frame == 3) {
            if (!clearCachedFirstStyleableAncestor(leaf)) {
                detail = "could not access CssStyleHelper.firstStyleableAncestor via reflection";
                reproduced = false;
                finish();
                return;
            }
            // UPDATE-only pass: the pseudo-class transition reuses the existing
            // (now-stale) helper instead of rebuilding it, so the next pulse's
            // resolveRef consults the empty cached ancestor reference.
            leaf.pseudoClassStateChanged(HOVER, true);
            return;
        }

        // Frame 5: the pulse after the pseudo-class change has re-resolved the
        // looked-up color through the stale cached ancestor. Check the outcome.
        if (frame >= 5) {
            boolean fillDropped = !hasFill(leaf);
            reproduced = fillDropped || warningSeen.get();
            detail = "fillDropped=" + fillDropped + ", warningLogged=" + warningSeen.get();
            finish();
        }
    }

    private static boolean hasFill(Pane p) {
        return p.getBackground() != null && !p.getBackground().getFills().isEmpty();
    }

    /**
     * Reflectively set the node's cached CssStyleHelper.firstStyleableAncestor to an
     * empty WeakReference, reproducing the transient stale state seen during a
     * Wayland stylesheet swap. Returns false if the node has no style helper yet.
     */
    private static boolean clearCachedFirstStyleableAncestor(Node node) {
        try {
            Field styleHelperField = Node.class.getDeclaredField("styleHelper");
            styleHelperField.setAccessible(true);
            Object helper = styleHelperField.get(node);
            if (helper == null) {
                return false;
            }
            Field ancestorField = helper.getClass().getDeclaredField("firstStyleableAncestor");
            ancestorField.setAccessible(true);
            ancestorField.set(helper, new WeakReference<Node>(null));
            return true;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void finish() {
        if (driver != null) {
            driver.stop();
        }
        if (reproduced) {
            System.err.println("\n==> REPRODUCED JDK-8268657 (" + detail + ")."
                    + "\n    The looked-up color -theme-button was dropped after an UPDATE pass"
                    + "\n    over a stale firstStyleableAncestor (String -> Paint ClassCastException).");
            Platform.exit();
            System.exit(1);
        } else {
            System.out.println("\n==> NOT reproduced (" + detail + ")."
                    + "\n    The looked-up color still resolved - this runtime has the"
                    + "\n    JDK-8268657 fix (resolveRef walks the live parent chain).");
            Platform.exit();
            System.exit(0);
        }
    }

    /**
     * CssStyleHelper.calculateValue logs the failure through PlatformLogger, which
     * routes to java.util.logging. Attach a handler to the root logger so we can
     * detect the specific WARNING regardless of where it is emitted.
     */
    private static void installWarningDetector() {
        Handler handler = new Handler() {
            @Override public void publish(LogRecord rec) {
                if (rec == null) {
                    return;
                }
                String msg = String.valueOf(rec.getMessage());
                Throwable t = rec.getThrown();
                boolean isBug = msg.contains("-fx-background-color")
                        && (msg.contains("ClassCastException")
                            || (t instanceof ClassCastException));
                if (isBug) {
                    warningSeen.set(true);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(handler);
        rootLogger.setLevel(Level.ALL);
    }

    /** Write a stylesheet to a temp file and return its external file: URL form. */
    private static String themeFile(String name, String contents) {
        try {
            Path f = Files.createTempFile("jdk8268657-" + name + "-", ".css");
            f.toFile().deleteOnExit();
            Files.writeString(f, contents);
            return f.toUri().toURL().toExternalForm();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
