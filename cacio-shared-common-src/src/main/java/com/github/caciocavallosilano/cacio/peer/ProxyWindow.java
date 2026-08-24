/*
 * Copyright 2009 Sun Microsystems, Inc.  All Rights Reserved.
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

package com.github.caciocavallosilano.cacio.peer;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * A specialized Window implementation with the sole purpose of providing
 * the Swing components that back up the AWT widgets a heavyweight parent.
 * From the point of view of the Swing component, this window acts exactly
 * like a usual heavyweight window. But it is infact just a proxy to
 * the AWT widget. This behaviour is achieved by overriding certain methods,
 * and by a special WindowPeer implementation in {@link ProxyWindow}.
 */
public class ProxyWindow extends Window {

    private CacioComponentPeer target;

    ProxyWindow(CacioComponentPeer t, JComponent c) {
        super(null);
        target = t;
        add(c);
    }

    CacioComponentPeer getTargetPeer() {
        return target;
    }

    void handleFocusEvent(FocusEvent e) {
        // TODO: Retarget?
        processFocusEvent(e);
    }

    void handleKeyEvent(KeyEvent e) {
        // TODO: Retarget?
        processKeyEvent(e);
    }

    void handleMouseEvent(MouseEvent e) {
        MouseEvent me = new MouseEvent(this, e.getID(), e.getWhen(),
                                       e.getModifiers(), e.getX(), e.getY(),
                                       e.getXOnScreen(), e.getYOnScreen(),
                                       e.getClickCount(), e.isPopupTrigger(),
                                       e.getButton());
        // IMPORTANT: See comment on the helper method!
        if (! doLightweightDispatching(e)) {
            processMouseEvent(me);
            trackDecorationDrag(me);
        }
    }

    void handleMouseMotionEvent(MouseEvent e) {
        MouseEvent me = new MouseEvent(this, e.getID(), e.getWhen(),
                                       e.getModifiers(), e.getX(), e.getY(),
                                       e.getXOnScreen(), e.getYOnScreen(),
                                       e.getClickCount(), e.isPopupTrigger(),
                                       e.getButton());
        // IMPORTANT: See comment on the helper method!
        if (! doLightweightDispatching(e)) {
            processMouseMotionEvent(me);
            trackDecorationDrag(me);
        }
    }

    private static Field dispatcherField;
    private static Method dispatchMethod;

    private static void initReflection() {
        try {
            dispatcherField = Container.class.getDeclaredField("dispatcher");
            dispatcherField.setAccessible(true);
            Class dispatcherCls = Class.forName("java.awt.LightweightDispatcher");
            dispatchMethod = dispatcherCls.getDeclaredMethod("dispatchEvent", AWTEvent.class);
            dispatchMethod.setAccessible(true);
        } catch (Exception ex) {
            InternalError err = new InternalError();
            err.initCause(ex);
            throw err;
        }
    }

    /**
     * Performs lightweight dispatching for the specified event on this window.
     * This only calls the lightweight dispatcher. We cannot simply
     * call dispatchEvent() because that would also send the event to the
     * Toolkit dispatching mechanism (AWTEventListener, etc), which has ugly
     * side effects, like popups closing too early.
     *
     * @param e the event to be dispatched
     *
     * @return <code>true</code> when a lightweight child took the event,
     *         <code>false</code> when it is left for this window to handle
     */
    private boolean doLightweightDispatching(AWTEvent e) {
        if (dispatcherField == null) {
            initReflection();
        }
        try {
            Object dispatcher = dispatcherField.get(this);
            if (dispatcher != null) {
                return (Boolean) dispatchMethod.invoke(dispatcher, e);
            }
            return false;
        } catch (Exception ex) {
            InternalError err = new InternalError();
            err.initCause(ex);
            throw err;
        }

    }

    /**
     * Where the pointer sat relative to the window when a drag of the window
     * decoration started, or <code>null</code> while no such drag is going on.
     */
    private Point decorationDragOffset;

    /**
     * Moves the window along with a drag of its decoration.
     *
     * The decoration is drawn by a JRootPane inside this proxy, and the look
     * and feel installs its own drag handling on the window that root pane
     * belongs to -- but that handling only moves Frames and Dialogs, and this
     * proxy is a plain Window, so it never engages. The move is done here
     * instead, and reaches the AWT component through {@link #setBounds}.
     *
     * @param e a mouse event no lightweight child wanted
     */
    private void trackDecorationDrag(MouseEvent e) {
        switch (e.getID()) {
        case MouseEvent.MOUSE_PRESSED:
            decorationDragOffset = isOnDecoration(e.getPoint())
                ? new Point(e.getXOnScreen() - getX(), e.getYOnScreen() - getY())
                : null;
            break;
        case MouseEvent.MOUSE_DRAGGED:
            if (decorationDragOffset != null) {
                setLocation(e.getXOnScreen() - decorationDragOffset.x,
                            e.getYOnScreen() - decorationDragOffset.y);
            }
            break;
        case MouseEvent.MOUSE_RELEASED:
            decorationDragOffset = null;
            break;
        default:
            break;
        }
    }

    /**
     * @return whether the point is on the decoration drawn around the window,
     *         rather than on the menu bar or the contents
     */
    private boolean isOnDecoration(Point p) {
        if (getComponentCount() == 0
            || ! (getComponent(0) instanceof JRootPane)) {
            return false;
        }

        JRootPane rootPane = (JRootPane) getComponent(0);
        if (rootPane.getWindowDecorationStyle() == JRootPane.NONE) {
            return false;
        }

        return ! covers(rootPane.getContentPane(), p)
               && ! covers(rootPane.getJMenuBar(), p);
    }

    private boolean covers(Component c, Point p) {
        return c != null && c.isShowing()
               && SwingUtilities.convertRectangle(c.getParent(), c.getBounds(),
                                                  this).contains(p);
    }

    /**
     * Whether the bounds currently being set come from the AWT component, in
     * which case they must not be sent straight back to it.
     */
    private boolean syncingFromTarget;

    /**
     * Takes over the bounds of the AWT component this window stands in for.
     * Unlike {@link #setBounds}, this does not push them back onto it.
     */
    void syncBounds(int x, int y, int width, int height) {
        syncingFromTarget = true;
        try {
            setBounds(x, y, width, height);
        } finally {
            syncingFromTarget = false;
        }
    }

    /**
     * Window decorations move and resize the window they are installed on,
     * which is this proxy rather than the AWT component it stands in for --
     * so a move or resize that did not come from the component itself is
     * passed on to it, and comes back to us through
     * {@link #syncBounds}.
     */
    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);

        if (! syncingFromTarget && target != null) {
            Component c = target.getAWTComponent();
            if (c != null && (c.getX() != x || c.getY() != y
                              || c.getWidth() != width
                              || c.getHeight() != height)) {
                c.setBounds(x, y, width, height);
            }
        }
    }

    /**
     * We need to override isShowing() and proxy it to the real AWT component,
     * otherwise we end up and try to draw on non-showing components...
     */
    public boolean isShowing() {
        return target.getAWTComponent().isShowing();
    }
}
