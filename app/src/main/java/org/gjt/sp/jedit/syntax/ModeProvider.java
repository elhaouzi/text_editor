/*
 * ModeProvider.java - An edit mode provider.
 * :tabSize=4:indentSize=4:noTabs=false:
 * :folding=explicit:collapseFolds=1:
 *
 * Copyright (C) 2003 Slava Pestov
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.gjt.sp.jedit.syntax;


import android.util.Log;

import com.duy.text.editor.utils.IStreamProvider;
import com.duy.text.editor.utils.StreamProviderFactory;
import com.jecelyin.common.utils.DLog;

import org.gjt.sp.jedit.Catalog;
import org.gjt.sp.jedit.Mode;
import org.gjt.sp.jedit.util.IOUtilities;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;


/**
 * This class works like a singleton, the instance is initialized by jEdit.
 *
 * @author Matthieu Casanova
 * @version $Id: Buffer.java 8190 2006-12-07 07:58:34Z kpouer $
 * @since jEdit 4.3pre10
 */
public class ModeProvider {
    private static final String TAG = "ModeProvider";
    public static ModeProvider instance = new ModeProvider();
    private final LinkedHashMap<String, Mode> modes;

    public ModeProvider() {
        modes = Catalog.modes;
    }

    public void removeAll() {
        modes.clear();
    }

    /**
     * Returns the edit mode with the specified name.
     *
     * @param name The edit mode
     * @since jEdit 4.3pre10
     */
    public Mode getMode(String name) {
        return modes.get(name);
    }

    /**
     * Get the appropriate mode that must be used for the file
     *
     * @param filename  the filename
     * @param firstLine the first line of the file
     * @return the edit mode, or null if no mode match the file
     * @since jEdit 4.3pre12
     */
    public Mode getModeForFile(String filename, String firstLine) {
        return getModeForFile(null, filename, firstLine);
    }

    /**
     * Get the appropriate mode that must be used for the file
     *
     * @param filepath  the filepath, can be {@code null}
     * @param filename  the filename, can be {@code null}
     * @param firstLine the first line of the file
     * @return the edit mode, or null if no mode match the file
     * @since jEdit 4.5pre1
     */
    public Mode getModeForFile(String filepath, String filename, String firstLine) {
        if (filepath != null && filepath.endsWith(".gz"))
            filepath = filepath.substring(0, filepath.length() - 3);
        if (filename != null && filename.endsWith(".gz"))
            filename = filename.substring(0, filename.length() - 3);

        List<Mode> acceptable = new ArrayList<Mode>(1);
        for (Mode mode : modes.values()) {
            if (mode.accept(filepath, filename, firstLine)) {
                acceptable.add(mode);
            }
        }
        if (acceptable.size() == 1) {
            return acceptable.get(0);
        }
        if (acceptable.size() > 1) {
            // The check should be in reverse order so that
            // modes from the user catalog get checked first!
            Collections.reverse(acceptable);

            // the very most acceptable mode is one whose file
            // name doesn't only match the file name as regular
            // expression but which is identical
            for (Mode mode : acceptable) {
                if (mode.acceptIdentical(filepath, filename)) {
                    return mode;
                }
            }

            // most acceptable is a mode that matches both the
            // filepath and the first line glob
            for (Mode mode : acceptable) {
                if (mode.acceptFile(filepath, filename) &&
                        mode.acceptFirstLine(firstLine)) {
                    return mode;
                }
            }
            // next best is filepath match
            for (Mode mode : acceptable) {
                if (mode.acceptFile(filepath, filename)) {
                    return mode;
                }
            }
            // all acceptable choices are by first line glob, and
            // they all match, so just return the first one.
            return acceptable.get(0);
        }
        // no matching mode found for this file
        return null;
    }

    /**
     * Returns an array of installed edit modes.
     *
     * @since jEdit 4.3pre10
     */
    public Mode[] getModes() {
        return modes.values().toArray(new Mode[modes.size()]);
    }

    /**
     * Do not call this method. It is only public so that classes
     * in the org.gjt.sp.jedit.syntax package can access it.
     *
     * @param mode The edit mode
     *             see org.gjt.sp.jedit.jEdit#reloadModes reloadModes
     * @since jEdit 4.3pre10
     */
    public void addMode(Mode mode) {
        String name = mode.getName();

        // The removal makes the "insertion order" in modes
        // (LinkedHashMap) follow the order of addMode() calls.
        modes.remove(name);

        modes.put(name, mode);
    }

    public void loadMode(Mode mode, XModeHandler xmh, IStreamProvider provider) {
        String fileName = mode.getFile();

        DLog.log(Log.DEBUG, this, "Loading edit mode " + fileName);

        XMLReader parser = null;
        try {
            SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
            SAXParser newSAXParser = saxParserFactory.newSAXParser();
            parser = newSAXParser.getXMLReader();
        } catch (SAXException saxe) {
            if (DLog.DEBUG) DLog.e(TAG, "loadMode: ", saxe);
            return;
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }
        mode.setTokenMarker(xmh.getTokenMarker());

        InputStream grammar;

        try {
            grammar = new BufferedInputStream(provider.getFileInputStream(fileName));
        } catch (IOException e1) {
            if (DLog.DEBUG) DLog.w(TAG, "loadMode: ", e1);
            InputStream resource;
            try {
                resource = provider.getAssetInputStream("syntax/" + fileName);
            } catch (IOException e) {
                if (DLog.DEBUG) DLog.e(TAG, "loadMode: ", e);
                return;
            }
            grammar = new BufferedInputStream(resource);
        }

        try {
            InputSource isrc = new InputSource(grammar);
            isrc.setSystemId("jedit.jar");
            parser.setContentHandler(xmh);
            parser.setDTDHandler(xmh);
            parser.setEntityResolver(xmh);
            parser.setErrorHandler(xmh);
            parser.parse(isrc);

            mode.setProperties(xmh.getModeProperties());
        } catch (Throwable e) {
            if (DLog.DEBUG) DLog.e(TAG, "loadMode: ", e);
        } finally {
            IOUtilities.closeQuietly(grammar);
        }
    }

    public void loadMode(Mode mode) {
        XModeHandler xmh = new XModeHandler(mode.getName()) {
            @Override
            public void error(String what, Object subst) {
                if (DLog.DEBUG) DLog.e(TAG, "error: ", subst);
            }

            @Override
            public TokenMarker getTokenMarker(String modeName) {
                Mode mode = getMode(modeName);
                if (mode == null)
                    return null;
                else
                    return mode.getTokenMarker();
            }
        };
        loadMode(mode, xmh, StreamProviderFactory.provider());
    }

    protected void error(String file, Throwable e) {
        DLog.log(Log.ERROR, this, e);
    }

}
