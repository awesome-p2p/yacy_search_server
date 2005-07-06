// plasmaWordIndexAssortment.java
// ------------------------------
// part of YACY
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// last major change: 18.5.2005
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

/*
  An assortment is a set of words that appear exactly on a specific
  number of different web pages. A special case is, when the the word
  appear only on a single web page: this is called a 'singleton'.
  YaCy maintains a word cache for words appearing on x web pages.
  For each 'x' there is an assortment database, where 1<=x<=max
  If a word appears on more than 'max' web pages, the corresponing url-list
  is stored to some kind of back-end database which we consider as the
  'slowest' option to save data. This here is the fastest file-based.
 */

package de.anomic.plasma;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import de.anomic.kelondro.kelondroException;
import de.anomic.kelondro.kelondroRecords;
import de.anomic.kelondro.kelondroTree;
import de.anomic.server.logging.serverLog;

public final class plasmaWordIndexAssortment {
    
    // environment constants
    private static final String assortmentFileName = "indexAssortment";
    public  static final int[] bufferStructureBasis = new int[]{
        plasmaWordIndexEntry.wordHashLength, // a wordHash
        4,                                   // occurrence counter
        8,                                   // timestamp of last access
        plasmaWordIndexEntry.urlHashLength,  // corresponding URL hash
        plasmaWordIndexEntry.attrSpaceLong   // URL attributes
    };
    
    // class variables
    private File assortmentFile;
    private int assortmentCapacity;
    private serverLog log;
    private kelondroTree assortments;
    private long bufferSize;
    private int bufferStructureLength;

    private static String intx(int x) {
	String s = "" + x;
	while (s.length() < 3) s = "0" + s;
	return s;
    }

    private static int[] bufferStructure(int assortmentCapacity) {
	int[] structure = new int[3 + 2 * assortmentCapacity];
	structure[0] = bufferStructureBasis[0];
	structure[1] = bufferStructureBasis[1];
	structure[2] = bufferStructureBasis[2];
	for (int i = 0; i < assortmentCapacity; i++) {
	    structure[3 + 2 * i] = bufferStructureBasis[3];
	    structure[4 + 2 * i] = bufferStructureBasis[4];
	}
	return structure;
    }

    public plasmaWordIndexAssortment(File storagePath, int assortmentCapacity, int bufferkb, serverLog log) {
	if (!(storagePath.exists())) storagePath.mkdirs();
	this.assortmentFile = new File(storagePath, assortmentFileName + intx(assortmentCapacity) + ".db");
	this.assortmentCapacity = assortmentCapacity;
	this.bufferStructureLength = 3 + 2 * assortmentCapacity;
        this.bufferSize = bufferkb * 1024;
        this.log = log;
        if (assortmentFile.exists()) {
            // open existing assortment tree file
            try {
                assortments = new kelondroTree(assortmentFile, bufferSize);
                if (log != null) log.logSystem("Opened Assortment Database, " + assortments.size() + " entries, width " + assortmentCapacity + ", " + bufferkb + "kb buffer"); 
            } catch (IOException e){
                if (log != null) log.logError("unable to open assortment database: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // create new assortment tree file
            try {
                assortments = new kelondroTree(assortmentFile, bufferSize, bufferStructure(assortmentCapacity));
                if (log != null) log.logSystem("Created new Assortment Database, width " + assortmentCapacity + ", " + bufferkb + "kb buffer"); 
            } catch (IOException e){
                if (log != null) log.logError("unable to create assortment database: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void store(String wordHash, plasmaWordIndexEntryContainer newContainer) {
        // stores a word index to assortment database
        // this throws an exception if the word hash already existed
        //log.logDebug("storeAssortment: wordHash=" + wordHash + ", urlHash=" + entry.getUrlHash() + ", time=" + creationTime);
        if (newContainer.size() != assortmentCapacity) throw new RuntimeException("plasmaWordIndexAssortment.store: wrong container size");
        byte[][] row = new byte[this.bufferStructureLength][];
        row[0] = wordHash.getBytes();
        row[1] = kelondroRecords.long2bytes(1, 4);
        row[2] = kelondroRecords.long2bytes(newContainer.updated(), 8);
        Iterator entries = newContainer.entries();
	plasmaWordIndexEntry entry;
        for (int i = 0; i < assortmentCapacity; i++) {
            entry = (plasmaWordIndexEntry) entries.next();
	    row[3 + 2 * i] = entry.getUrlHash().getBytes();
	    row[4 + 2 * i] = entry.toEncodedForm(true).getBytes();
	}
        byte[][] oldrow = null;
        try {
            oldrow = assortments.put(row);
        } catch (IOException e) {
            log.logFailure("storeAssortment/IO-error: " + e.getMessage() + " - reset assortment-DB");
            e.printStackTrace();
            resetDatabase();
        } catch (kelondroException e) {
            log.logFailure("storeAssortment/kelondro-error: " + e.getMessage() + " - reset assortment-DB");
            e.printStackTrace();
            resetDatabase();
        }
        if (oldrow != null) throw new RuntimeException("Store to assortment ambiguous");
    }

    public plasmaWordIndexEntryContainer remove(String wordHash) {
        // deletes a word index from assortment database
	// and returns the content record
        byte[][] row = null;
        try {
            row = assortments.remove(wordHash.getBytes());
        } catch (IOException e) {
            log.logFailure("removeAssortment/IO-error: " + e.getMessage() + " - reset assortment-DB");
            e.printStackTrace();
            resetDatabase();
	    return null;
        } catch (kelondroException e) {
            log.logFailure("removeAssortment/kelondro-error: " + e.getMessage() + " - reset assortment-DB");
            e.printStackTrace();
            resetDatabase();
	    return null;
        }
        if (row == null) return null;
        long updateTime = kelondroRecords.bytes2long(row[2]);
        plasmaWordIndexEntry[] wordEntries = new plasmaWordIndexEntry[this.bufferStructureLength];
        plasmaWordIndexEntryContainer container = new plasmaWordIndexEntryContainer(wordHash);
	for (int i = 0; i < assortmentCapacity; i++) {
            container.add(new plasmaWordIndexEntry[]{new plasmaWordIndexEntry(new String(row[3 + 2 * i]), new String(row[4 + 2 * i]))}, updateTime);
	}
        return container;
    }
    
    private void resetDatabase() {
        // deletes the assortment database and creates a new one
        if (assortments != null) try {
            assortments.close();
        } catch (IOException e) {}
        if (!(assortmentFile.delete())) throw new RuntimeException("cannot delete assortment database");
        try {
            assortments = new kelondroTree(assortmentFile, bufferSize, bufferStructure(assortmentCapacity));
        } catch (IOException e){
            log.logError("unable to re-create assortment database: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public Iterator hashes(String startWordHash, boolean up, boolean rot) {
        try {
            return assortments.keys(up, rot, startWordHash.getBytes());
        } catch (IOException e) {
            log.logFailure("iterateAssortment/IO-error: " + e.getMessage() + " - reset assortment-DB");
            e.printStackTrace();
            resetDatabase();
            return null;
        }
    }

    public int size() {
	return assortments.size();
    }

    public void close() {
        try {
            assortments.close();
        } catch (IOException e){
            log.logError("unable to close assortment database: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
