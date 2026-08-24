/*
 * Copyright 2008-2016 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
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
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.github.caciocavallosilano.cacio.peer.managed;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.github.caciocavallosilano.cacio.peer.CacioComponent;
import com.github.caciocavallosilano.cacio.peer.CacioEventPump;
import com.github.caciocavallosilano.cacio.peer.CacioEventSource;
import com.github.caciocavallosilano.cacio.peer.PlatformToplevelWindow;
import com.github.caciocavallosilano.cacio.peer.PlatformWindow;
import com.github.caciocavallosilano.cacio.peer.PlatformWindowFactory;

public class FullScreenWindowFactory implements PlatformWindowFactory {

    private static final Dimension screenSize;
    static {
        String size = System.getProperty("cacio.managed.screensize", "1024x768");
        int x = size.indexOf('x');
        int width = Integer.parseInt(size.substring(0, x));
        int height = Integer.parseInt(size.substring(x + 1));
        screenSize = new Dimension(width, height);
    }

    /**
     * The colour the screen shows where no window covers it. Backends paint
     * their screen with this before anything else is drawn, and it is what
     * gets cleared to when a window moves away or is hidden.
     */
    private static Color screenBackground;
    static {
        String color = System.getProperty("cacio.managed.background");
        Color c = Color.WHITE;
        if (color != null) {
            try {
                c = Color.decode(color);
            } catch (NumberFormatException ex) {
                // not a colour we understand, keep the default
            }
        }
        screenBackground = c;
    }

    /**
     * This is used to re-source the events coming from the
     * PlatformScreen to the corresponding ManagedWindowContainer.
     */
    private class FullScreenEventSource implements CacioEventSource {
        public EventData getNextEvent() throws InterruptedException {
            EventData d = eventSource.getNextEvent();
            PlatformScreen source = (PlatformScreen) d.getSource();
            d.setSource(screenMap.get(source));
            return d;
        }
    }

    /**
     * We need a selector to select the actual screen in case
     * of multiple screens. A default selector implementation
     * is given if only one screen is available.
     * the selector is used in createPlatformToplevelWindow
     * to define
     */
    private final PlatformScreenSelector selector;

    /**
     * This allows the mappings between PlatformScreen and
     * ScreenManagedWindowContainer, and is needed to re-source events
     * from PlatformScreen to ManagedWindowContainer.
     */
    private final Map<PlatformScreen, ScreenManagedWindowContainer> screenMap;

    /**
     * The event source that generates the basic events.
     * Note: We create and return a FullScreenEventSource that
     * uses this eventSource as 'backend' and infers all the higher
     * level events.
     */
    private CacioEventSource eventSource;

    /**
     * Constructs a new FullScreenWindowFactory that uses the
     * specified container as container for all toplevel windows.
     *
     * The event source is expected to generate the following event types:
     * <ul>
     * <li>{@code MouseEvent.MOUSE_PRESSED}</li>
     * <li>{@code MouseEvent.MOUSE_RELEASED}</li>
     * <li>{@code MouseEvent.MOUSE_MOVED}</li>
     * <li>{@code KeyEvent.KEY_PRESSED}</li>
     * <li>{@code KeyEvent.KEY_RELEASED}</li>
     * </ul>
     *
     * All the other events (component, window, focus, remaining mouse and key
     * events) are inferred and synthesized by the event source that is
     * created from this factory.
     *
     * @param screen the container to be used for toplevel windows
     * @param s the event source to use
     */
    public  FullScreenWindowFactory(PlatformScreen screen,
                                   CacioEventSource s) {

        this(new DefaultScreenSelector(screen), s);
    }

    public FullScreenWindowFactory(PlatformScreenSelector screenSelector,
                                   CacioEventSource s) {

        this.selector = screenSelector;
        this.eventSource = s;
        screenMap =
                Collections.synchronizedMap(new HashMap<PlatformScreen,
                                            ScreenManagedWindowContainer>());
    }

    /**
     * Creates a {@link PlatformWindow} instance.
     *
     * @param awtComponent the corresponding Cacio component
     * @param parent the parent window, or <code>null</code> for top level windows
     *
     * @return the platform window instance
     */
    public final PlatformWindow createPlatformWindow(CacioComponent awtComponent,
                                                     PlatformWindow parent) {
        if (parent == null) {
            throw new IllegalArgumentException("parent cannot be null");
        }

        ManagedWindow p = (ManagedWindow) parent;
        return new ManagedWindow(p, awtComponent);
    }

    @Override
    public final
    PlatformToplevelWindow createPlatformToplevelWindow(CacioComponent comp) {
        PlatformScreen screen = selector.getPlatformScreen(comp);
        ScreenManagedWindowContainer smwc = screenMap.get(screen);
        if (smwc == null) {
            smwc = new ScreenManagedWindowContainer(screen);
            screenMap.put(screen, smwc);
        }

        return new ManagedWindow(smwc, comp);
    }

    /**
     * {@inheritDoc }
     * <strong>Note</strong>: owners are currently ignored in fully managed
     * windows.
     */
    @Override
    public PlatformWindow createPlatformToplevelWindow(CacioComponent component,
                                                       PlatformWindow notUsed) {

        return createPlatformToplevelWindow(component);
    }

    @Override
    public CacioEventPump<?> createEventPump() {
        FullScreenEventSource s = new FullScreenEventSource();
        return new FullScreenEventPump(s);
    }

    public static Dimension getScreenDimension() {
        return screenSize;
    }

    /**
     * Returns the colour the screen shows where no window covers it. Defaults
     * to white, or to the {@code cacio.managed.background} property when that
     * names a colour {@link Color#decode} understands.
     *
     * @return the screen background colour, never <code>null</code>
     */
    public static Color getScreenBackground() {
        return screenBackground;
    }

    /**
     * Sets the colour the screen shows where no window covers it. A backend
     * that paints its own screen should call this before creating windows, or
     * read {@link #getScreenBackground} and paint that.
     *
     * @param c the background colour, ignored when <code>null</code>
     */
    public static void setScreenBackground(Color c) {
        if (c != null) {
            screenBackground = c;
        }
    }

    /**
     * Default implementation for the PlatformScreenSelector. Just return
     * the single screen instance we have.
     */
    private static final class DefaultScreenSelector implements
        PlatformScreenSelector {

        PlatformScreen screen = null;

        DefaultScreenSelector(PlatformScreen screen) {

            this.screen = screen;
        }

        @Override
        public PlatformScreen getPlatformScreen(GraphicsConfiguration config) {

            return this.screen;
        }

        @Override
        public PlatformScreen getPlatformScreen(CacioComponent comp) {
            return getPlatformScreen(comp.getAWTComponent().getGraphicsConfiguration());
        }
    }
    
    public ScreenManagedWindowContainer getScreenManagedWindowContainer(PlatformScreen screen) {
	return screenMap.get(screen);
    }
}
