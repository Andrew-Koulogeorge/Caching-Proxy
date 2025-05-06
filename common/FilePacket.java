package common; 
import java.io.*;
import java.io.Serializable;

/**
 * Object used to communicate metadata between the client and the server when files are fetched by the proxy
 * Note that the FileObject contains no actual file data; only information needed to start the downloading in chunks
 */
public class FilePacket implements Serializable{ 
    public long size;				  // total number of bytes in file
	public int versionNumber;
	public String serverPathname;     // location where file is stored in database (index in hashmap)
    public String errorMsg;
    public String normalizedCachePath;
    
    public FilePacket(long size, int versionNumber, String serverPathname, String errorMsg, String normalizedCachePath){ 
        this.size = size;
        this.versionNumber = versionNumber;
        this.serverPathname = serverPathname;               
        this.errorMsg = errorMsg;
        this.normalizedCachePath = normalizedCachePath;
    }    
    
    public FilePacket(){
        this.size = -1;
        this.versionNumber = 1;
        this.serverPathname = null;
        this.errorMsg = null;
        this.normalizedCachePath = normalizedCachePath;
    }    
}