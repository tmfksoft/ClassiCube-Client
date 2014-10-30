/*
 * $ProjectName$
 * $ProjectRevision$
 * -----------------------------------------------------------
 * $Id: UncachedUrlStream.java,v 1.1 2003/04/10 19:48:22 jarnbjo Exp $
 * -----------------------------------------------------------
 *
 * $Author: jarnbjo $
 *
 * Description:
 *
 * Copyright 2002-2003 Tor-Einar Jarnbjo
 * -----------------------------------------------------------
 *
 * Change History
 * -----------------------------------------------------------
 * $Log: UncachedUrlStream.java,v $
 * Revision 1.1  2003/04/10 19:48:22  jarnbjo
 * no message
 *
 */

package de.jarnbjo.ogg;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * Implementation of the <code>PhysicalOggStream</code> interface for reading an
 * Ogg stream from a URL. This class performs only the necessary caching to
 * provide continous playback. Seeking within the stream is not supported.
 */

public class UncachedUrlStream implements PhysicalOggStream {

    public class LoaderThread implements Runnable {

        private InputStream source;
        private LinkedList<OggPage> pageCache;

        private boolean bosDone = false;

        public LoaderThread(InputStream source, LinkedList<OggPage> pageCache) {
            this.source = source;
            this.pageCache = pageCache;
        }

        public boolean isBosDone() {
            return bosDone;
        }

        public void run() {
            try {
                boolean eos = false;
                while (!eos) {
                    OggPage op = OggPage.create(source);
                    synchronized (drainLock) {
                        pageCache.add(op);
                    }

                    if (!op.isBos()) {
                        bosDone = true;
                    }
                    if (op.isEos()) {
                        eos = true;
                    }

                    LogicalOggStreamImpl los = (LogicalOggStreamImpl) getLogicalStream(op
                            .getStreamSerialNumber());
                    if (los == null) {
                        los = new LogicalOggStreamImpl(UncachedUrlStream.this);
                        logicalStreams.put(op.getStreamSerialNumber(), los);
                        los.checkFormat(op);
                    }

                    while (pageCache.size() > PAGECACHE_SIZE) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            } catch (EndOfOggStreamException e) {
                // ok
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean closed = false;
    private URLConnection source;
    private InputStream sourceStream;
    private Object drainLock = new Object();

    private LinkedList<OggPage> pageCache = new LinkedList<>();

    private HashMap<Integer, LogicalOggStreamImpl> logicalStreams = new HashMap<>();

    private LoaderThread loaderThread;

    private static final int PAGECACHE_SIZE = 10;

    /**
     * Creates an instance of the <code>PhysicalOggStream</code> interface
     * suitable for reading an Ogg stream from a URL.
     */

    public UncachedUrlStream(URL source) throws OggFormatException, IOException {

        this.source = source.openConnection();
        this.sourceStream = this.source.getInputStream();

        loaderThread = new LoaderThread(sourceStream, pageCache);
        new Thread(loaderThread,"Ogg-UncachedUrlStream").start();

        while (!loaderThread.isBosDone() || pageCache.size() < PAGECACHE_SIZE) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
            }
            // System.out.print("caching "+pageCache.size()+"/"+PAGECACHE_SIZE+" pages\r");
        }
        // System.out.println();
    }

    public void close() throws IOException {
        closed = true;
        sourceStream.close();
    }

    private LogicalOggStream getLogicalStream(int serialNumber) {
        return logicalStreams.get(new Integer(serialNumber));
    }

    /*
     * public long getCacheLength() { return cacheLength; }
     */

    /*
     * private OggPage getNextPage() throws EndOfOggStreamException,
     * IOException, OggFormatException { return getNextPage(false); }
     *
     * private OggPage getNextPage(boolean skipData) throws
     * EndOfOggStreamException, IOException, OggFormatException { return
     * OggPage.create(sourceStream, skipData); }
     */

    public Collection<LogicalOggStreamImpl> getLogicalStreams() {
        return logicalStreams.values();
    }

    public OggPage getOggPage(int index) throws IOException {
        while (pageCache.size() == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
            }
        }
        synchronized (drainLock) {
            // OggPage page=(OggPage)pageCache.getFirst();
            // pageCache.removeFirst();
            // return page;
            return pageCache.removeFirst();
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    /**
     * @return always <code>false</code>
     */

    public boolean isSeekable() {
        return false;
    }

    public void setTime(long granulePosition) throws IOException {
        throw new UnsupportedOperationException("Method not supported by this class");
    }

}