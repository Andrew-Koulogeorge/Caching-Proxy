package common;
import java.io.*;

/**
 * Object to store relevant information about files in the Cache 
 * Each object in the cache represents a physical file in memory
 * A file can have n readers at a single time
 * A file can only have 1 writer at a time
 */
public class CacheEntry {
    public int numReaders;
    public boolean writerPresent;
	public int versionNumber;      
    public String path;                   // path sent from user: "test.txt" 
    public String cachePathname;          // path stored in cache: "cache/test.txt-1000"
    public String serverPathname;         // path including root "afs/akouloge/..../test.txt"
    public boolean hasBeenEvicted;        // extra bit used to ensure deletion of Cache Entry from cacheDirectory
    

    public CacheEntry(int numReaders, boolean writerPresent, int versionNumber, 
    String serverPathname, String cachePathname, String path) {
        this.numReaders = numReaders;
        this.writerPresent = writerPresent;
		this.versionNumber = versionNumber;    
        this.serverPathname = serverPathname;		
        this.cachePathname = cachePathname;
        this.path = path;
        this.hasBeenEvicted = false;
    }

    public CacheEntry() {
        this.numReaders = 0;
        this.writerPresent = false;
		this.versionNumber = -1;
        this.serverPathname = null;		
        this.cachePathname = null;
        this.path = null;
        this.hasBeenEvicted = false;
    }
}