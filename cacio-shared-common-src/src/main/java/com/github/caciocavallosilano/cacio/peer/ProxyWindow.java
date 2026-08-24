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
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
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

    /** How close to an edge a press has to be to resize rather than move. */
    private static final int BORDER_DRAG_THICKNESS = 5;

    private static final int EDGE_LEFT = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_TOP = 4;
    private static final int EDGE_BOTTOM = 8;

    /** Smallest window a resize will leave behind. */
    private static final Dimension MIN_SIZE = new Dimension(60, 40);

    /**
     * Where the pointer sat relative to the window when a move of the window
     * decoration started, or <code>null</code> while no move is going on.
     */
    private Point decorationDragOffset;

    /** The edges being dragged while resizing, or 0 while not resizing. */
    private int resizeEdges;

    /** Where the pointer and the window were when the resize started. */
    private Point resizeStart;
    private Rectangle resizeBounds;

    /**
     * Moves or resizes the window along with a drag of its decoration.
     *
     * The decoration is drawn by a JRootPane inside this proxy, and the look
     * and feel installs its own drag handling on the window that root pane
     * belongs to -- but that handling only moves and resizes Frames and
     * Dialogs, and this proxy is a plain Window, so it never engages. It is
     * done here instead, and reaches the AWT component through
     * {@link #setBounds}.
     *
     * @param e a mouse event no lightweight child wanted
     */
    private void trackDecorationDrag(MouseEvent e) {
        switch (e.getID()) {
        case MouseEvent.MOUSE_PRESSED:
            decorationDragOffset = null;
            resizeEdges = 0;
            Component pressed = targetComponent();
            if (pressed == null || ! isOnDecoration(e.getPoint())) {
                break;
            }
            resizeEdges = isTargetResizable() ? edgesAt(e.getPoint()) : 0;
            if (resizeEdges != 0) {
                resizeStart = new Point(e.getXOnScreen(), e.getYOnScreen());
                resizeBounds = pressed.getBounds();
            } else {
                decorationDragOffset =
                    new Point(e.getXOnScreen() - pressed.getX(),
                              e.getYOnScreen() - pressed.getY());
            }
            break;
        case MouseEvent.MOUSE_DRAGGED:
            Component dragged = targetComponent();
            if (dragged == null) {
                break;
            }
            if (resizeEdges != 0) {
                resizeTo(dragged, e.getXOnScreen(), e.getYOnScreen());
            } else if (decorationDragOffset != null) {
                dragged.setLocation(e.getXOnScreen() - decorationDragOffset.x,
                                    e.getYOnScreen() - decorationDragOffset.y);
            }
            break;
        case MouseEvent.MOUSE_RELEASED:
            decorationDragOffset = null;
            resizeEdges = 0;
            break;
        default:
            break;
        }
    }

    /**
     * @return the edges of this window the point sits on, as a mask of
     *         EDGE_*, or 0 when it is not close enough to any of them
     */
    private int edgesAt(Point p) {
        Component c = targetComponent();
        int edges = 0;

        if (c == null) {
            return 0;
        }

        if (p.x < BORDER_DRAG_THICKNESS) {
            edges |= EDGE_LEFT;
        } else if (p.x >= c.getWidth() - BORDER_DRAG_THICKNESS) {
            edges |= EDGE_RIGHT;
        }

        if (p.y < BORDER_DRAG_THICKNESS) {
            edges |= EDGE_TOP;
        } else if (p.y >= c.getHeight() - BORDER_DRAG_THICKNESS) {
            edges |= EDGE_BOTTOM;
        }

        return edges;
    }

    private Component targetComponent() {
        return target == null ? null : target.getAWTComponent();
    }

    /** Applies a resize that has been dragged out to the given screen point. */
    private void resizeTo(Component c, int screenX, int screenY) {
        int dx = screenX - resizeStart.x;
        int dy = screenY - resizeStart.y;
        Rectangle r = new Rectangle(resizeBounds);

        if ((resizeEdges & EDGE_LEFT) != 0) {
            r.x += dx;
            r.width -= dx;
        } else if ((resizeEdges & EDGE_RIGHT) != 0) {
            r.width += dx;
        }

        if ((resizeEdges & EDGE_TOP) != 0) {
            r.y += dy;
            r.height -= dy;
        } else if ((resizeEdges & EDGE_BOTTOM) != 0) {
            r.height += dy;
        }

        Dimension min = minimumWindowSize();

        // dragging the left or top edge past the minimum has to stop the edge
        // rather than carry the opposite one along with it
        if (r.width < min.width) {
            if ((resizeEdges & EDGE_LEFT) != 0) {
                r.x = resizeBounds.x + resizeBounds.width - min.width;
            }
            r.width = min.width;
        }
        if (r.height < min.height) {
            if ((resizeEdges & EDGE_TOP) != 0) {
                r.y = resizeBounds.y + resizeBounds.height - min.height;
            }
            r.height = min.height;
        }

        c.setBounds(r.x, r.y, r.width, r.height);
    }

    private Dimension minimumWindowSize() {
        Component c = targetComponent();
        Dimension min = c == null ? null : c.getMinimumSize();
        if (min == null) {
            return MIN_SIZE;
        }
        return new Dimension(Math.max(min.width, MIN_SIZE.width),
                             Math.max(min.height, MIN_SIZE.height));
    }

    private boolean isTargetResizable() {
        Component c = targetComponent();
        if (c instanceof Frame) {
            return ((Frame) c).isResizable();
        }
        if (c instanceof Dialog) {
            return ((Dialog) c).isResizable();
        }
        return false;
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
     * We need to override isShowing() and proxy it to the real AWT component,
     * otherwise we end up and try to draw on non-showing components...
     */
    public boolean isShowing() {
        return target.getAWTComponent().isShowing();
    }
}
