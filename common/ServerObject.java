package common; 
import java.io.*;
import java.util.concurrent.Semaphore;
/**
 * Object used by the server to manage files 
 */
public class ServerObject implements Serializable{
	public int versionNumber;                 // iteration of this file
	public String serverPathname;             // location on rootdir
    public RandomAccessFile raFile;           // for reading and writing from server files
    public Semaphore fileLock;                // prevents conflicts when files are being uploaded/downloaded

    public ServerObject(RandomAccessFile raFile, int versionNumber, String serverPathname){ 
        this.raFile = raFile;                 
        this.versionNumber = versionNumber;
        this.serverPathname = serverPathname;               
        this.fileLock = new Semaphore(1);
    }    
    
    public ServerObject(){
        this.raFile = null;
        this.versionNumber = 1;
        this.serverPathname = null;              
        this.fileLock = new Semaphore(1);
    }    
}