///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 24+
//DEPS org.openjfx:javafx-base:26.0.1
//DEPS org.openjfx:javafx-graphics:26.0.1
//DEPS org.openjfx:javafx-controls:26.0.1
//RUNTIME_OPTIONS --add-modules=javafx.controls

/*
 * Copyright (c) 2026, Oliver Kopp. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holder designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * Minimal reproducer for JDK-8268657
 *   "ClassCastException: String cannot be cast to Paint while converting value
 *    for -fx-background-color from rule '*.button-primary'"
 *   https://bugs.openjdk.org/browse/JDK-8268657
 *
 * Symptom: when a stylesheet that defines a looked-up color (here -theme-button)
 * is swapped via getStylesheets().clear() + add(), JavaFX may log a WARNING from
 * CssStyleHelper.calculateValue because a *cached* converted value for the
 * looked-up color is reused as a raw String instead of the converted Paint.
 *
 * The original report notes two triggers that this file keeps faithfully:
 *   1. the theme is switched with clear() THEN add() (not add-then-remove), and
 *   2. the rule also sets -fx-font-family, which keeps a second cached entry in
 *      play. Remove either and the warning disappears.
 *
 * The bug is intermittent (most frequent on Linux/Wayland), so this file does
 * not switch once: it toggles the theme on every pulse for a while to provoke
 * the race, and installs a java.util.logging handler so a reproduction is
 * reported explicitly (and the process exits non-zero) instead of only scrolling
 * past in the console.
 *
 * Run:   jbang CssLookedUpColorBug.java          (or: chmod +x and ./CssLookedUpColorBug.java)
 *        Needs a display; on a headless box use:  xvfb-run jbang CssLookedUpColorBug.java
 */

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class CssLookedUpColorBug extends Application {

    // Two themes, both referencing the looked-up color -theme-button, and both
    // also setting -fx-font-family (trigger #2 from the bug report). Written to
    // temp files so JavaFX loads them as ordinary file: URLs (matching the
    // warning shown in the original report).
    private static final String DARK = themeFile("dark",
            ".button-primary { -fx-background-color: -theme-button; -fx-font-family: helvetica; }\n" +
            ".root { -theme-button: #0679DE; }\n");

    private static final String MEDIUM = themeFile("medium",
            ".button-primary { -fx-background-color: -theme-button; -fx-font-family: helvetica; }\n" +
            ".root { -theme-button: #FF0000; }\n");

    // Set true by the logging handler the moment the bug's WARNING is observed.
    private static final AtomicBoolean reproduced = new AtomicBoolean(false);

    private StackPane root;
    private boolean dark = true;
    private int toggles = 0;
    private static final int MAX_TOGGLES = 2000;

    @Override
    public void start(Stage stage) {
        installWarningDetector();

        Button btn = new Button("button-primary");
        btn.getStyleClass().setAll("button-primary");

        root = new StackPane(btn);
        root.getStyleClass().add("root");

        Scene scene = new Scene(root, 320, 240);
        stage.setTitle("JDK-8268657 reproducer");
        stage.setScene(scene);
        stage.show();

        setTheme(DARK);

        // Toggle the theme on every animation pulse to provoke the intermittent race.
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (reproduced.get() || toggles >= MAX_TOGGLES) {
                    stop();
                    finish();
                    return;
                }
                dark = !dark;
                setTheme(dark ? DARK : MEDIUM);
                toggles++;
            }
        }.start();
    }

    /** Swap the theme exactly as in the bug report: clear() then add(). */
    private void setTheme(String theme) {
        root.getStylesheets().clear();
        root.getStylesheets().add(theme);
    }

    private void finish() {
        if (reproduced.get()) {
            System.err.println("\n==> REPRODUCED JDK-8268657 after " + toggles + " theme toggles.");
            Platform.exit();
            System.exit(1);
        } else {
            System.out.println("\n==> No warning observed in " + toggles
                    + " toggles (the bug is intermittent — try running again, ideally on Linux/Wayland).");
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
            @Override
            public void publish(LogRecord rec) {
                if (rec == null) {
                    return;
                }
                String msg = String.valueOf(rec.getMessage());
                Throwable t = rec.getThrown();
                boolean isBug = msg.contains("-fx-background-color")
                        && (msg.contains("ClassCastException")
                            || (t != null && t instanceof ClassCastException));
                if (isBug) {
                    reproduced.set(true);
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        root.addHandler(handler);
        root.setLevel(Level.ALL);
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
