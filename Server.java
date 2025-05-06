
/**
 * @author Andrew Koulogeorge | 15440 CMU
 * Implementation of the single Server node in a Distributed File System that supports caching
 */

/* imports for Java RMI */
import java.rmi.server.UnicastRemoteObject;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;


/* import objects from shared package */
import common.FilePacket;
import common.ServerInterface;
import common.CacheEntry;
import common.ServerObject;
import common.ByteChunk;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.concurrent.Semaphore;

/**
 * Server class that stores entire files and handles communication with the proxy about file operations
 */
public class Server extends UnicastRemoteObject implements ServerInterface{

    /* Object used to store files on the server; serverPathname -> ServerObject*/
    private static ConcurrentHashMap<String, ServerObject> database = new ConcurrentHashMap<String, ServerObject>();
    private String rootdir; 

    /* constants for open error handling */
    public static final int _READ = 1;
    public static final int _WRITE = 2;
    public static final int _CREATE = 3;
    public static final int _CREATE_NEW = 4;
    public static final String _SUCCESS = "success";
    public static final String _FILE_DNE = "file does not exist";

    public Server(String rootdir) throws RemoteException {
        super(0);
        this.rootdir = rootdir;
       }
    
    /**
     * If versionNumber on client is less then version number on server return true else false
     * serverPathname: expected to include entire root dir
     * 
     * If the file being checked for staleness is no longer on the server, we return that the file is stale
     * This will trigger the error hanlding code later in the open function that will catch the file being
     * opened does not exist
     */
    public synchronized boolean isStale(String serverPathname, int proxyVersionNumber){
        if(!database.containsKey(serverPathname))
            return true;
        ServerObject serverFileObj = database.get(serverPathname);                           // get file from database
        return serverFileObj.versionNumber != proxyVersionNumber;                            // compare version numbers
    }

    /**
     * Establish the existance of the requested file on the server. If the file exists on the server,
     * a session is established that ensures no other proxies can write to the file as its being fetched
     * 
     * @param path: does not include full path; just user query for the file
     * @param permissions: 
     * @param option: encodes state of OpenOption
     *     1 -> READ
     *     2 -> WRITE
     *     3 -> CREATE
     *     4 -> CREATE_NEW
     * @return metadata related to the file being downloaded from the server
     */
    public synchronized FilePacket establishDownloadSession(String path, String permissions, int option) throws RemoteException{
        
        Path rootDirPath = Paths.get(this.rootdir);
        Path full_path = Paths.get(this.rootdir, path).normalize();
        String serverPathname = full_path.toString();
        File file = full_path.toFile();
        
        String normalizedForCache = rootDirPath.relativize(full_path).toString();
        FilePacket return_file_obj = new FilePacket(0, 1, serverPathname, "", normalizedForCache); // construct message to client
        
        /* perform error handling and encode error message in return file object*/

		if(serverPathname.contains("/..") || serverPathname.contains("../")){
			return_file_obj.errorMsg = "Trying to acess outside of rootdir";
            return return_file_obj;
			}
        if(file.exists() && option == _CREATE_NEW){
            return_file_obj.errorMsg = "File exists and we are trying to create it";
            return return_file_obj;
        }	
        if(!file.exists() && (option == _READ || option == _WRITE)){
            return_file_obj.errorMsg = "File Not Found";
            return return_file_obj;
        }
        if(file.isDirectory() && permissions.equals("rw")){ // not allowed to open dir with write permissions
            return_file_obj.errorMsg = "not allowed to open dir with write permissions";
            return return_file_obj;
        }
        if(file.isDirectory() && permissions.equals("r")){ 
            return_file_obj.errorMsg = "read directory special case";
            return return_file_obj;
        }        
        if(!file.exists() && (option == _CREATE || option == _CREATE_NEW)){ // if the file does not exist and we are creating it, dont want to store anything on the server
            return_file_obj.errorMsg = "";
            return return_file_obj;
        }

        // if no proxy has touched this file, but the file exists, load into database
        if(!database.containsKey(serverPathname) && file.exists())
            createNewServerObj(file, serverPathname);
        ServerObject serverObject = database.get(serverPathname);

                    // OBTAIN FILE LOCK; COMMITING TO SEND DATA TO THIS PROXY //
        
        try{serverObject.fileLock.acquire();}
		catch(InterruptedException e){ e.printStackTrace(); return null; }

        return_file_obj.versionNumber = serverObject.versionNumber;
        return_file_obj.size = file.length();

        return return_file_obj;
    }


    /**
     * Send file data to client in chunks when it requests file
     * This function is always called after a call to establishDownloadSession
     */
    public ByteChunk downloadFile(String fullPathString, int location, int chunkSize, long totalSize){
        if(location == -1){
            database.get(fullPathString).fileLock.release();
            return null;
        }

        RandomAccessFile raFile = database.get(fullPathString).raFile; // get raf from file on server                                            
        byte[] file_data = new byte[chunkSize];                            // allocate byte array to hold chunk sized contents
        int num_bytes_read = 0;
        try{
            raFile.seek(location);                                        // set internal pointer to current location
            num_bytes_read = raFile.read(file_data, 0, chunkSize);        // read chunk size bytes into the array
        }
        catch(IOException e){e.printStackTrace(); return null; }        

        // check if this is the last download call that will be made 
        if(totalSize == 0 || num_bytes_read + location == totalSize)    // LET GO OF FILE LOCK; LAST PACKET BEING SENT BACK 
            database.get(fullPathString).fileLock.release();
    
        return new ByteChunk(file_data, num_bytes_read);
    }


    /**
     * method used to lock the file being updated so that the user has sole acess to it while its sending
     * the file to the server. If the file has been unlinked while this file was open, we create a new file 
     * in preparation for incoming byte data.
     * 
     * Upon uploading files to the server, we want the local file to get what the most recent
     * version number is from the server to help the cache understand what is to to date and what is not
     * @param serverPathname: name of file being updated on the server
     * @return most up to date version number of file
     */
    public synchronized int establishUploadSession(String serverPathname){

        if(!database.containsKey(serverPathname)){ 
            File newFile = new File(serverPathname);                    // make new file on the server rootdir
            try{
				File parentDir = newFile.getParentFile();
				if(parentDir != null && !parentDir.exists()){
					boolean dirCreated = parentDir.mkdirs();
					if(!dirCreated) return -1;
				}	                
                newFile.createNewFile();
            }
            catch(IOException e){e.printStackTrace(); return -1;}
            createNewServerObj(newFile, serverPathname);                // make a ServerObject and place it in the database
        }

        ServerObject server_version = database.get(serverPathname);     // get the entry in the server corresponding to this file
        
        // GET FILE LOCK; COMMITING TO RECV ENTIRE FILE IN CHUNKS FROM PROXY 
        try{server_version.fileLock.acquire();}
		catch(InterruptedException e){ e.printStackTrace(); return -1; }
        
        server_version.versionNumber++;                                  // increment the version number        
        return server_version.versionNumber;                             // proxy sole access to file
    }   


    /**
     * Send chunks of file data from the proxy to be written to on the server
     * 
     * Recv ByteChunk from the Proxy containing the byte information and number of bytes in the chunk
     * Write these bytes into raf on the server
     * 
     * NOTE: This function is always called after a call to establish UploadSession
     * 
     * @param fullPathString: name of file stored in server database
     * @param location: integer representing where to start writing into file on server
     */
    public void uploadFile(ByteChunk file_chunk, String fullPathString, int location, long totalFileSize){
        if(location == -1){
            database.get(fullPathString).fileLock.release();
            return;
        }
        RandomAccessFile raFile = database.get(fullPathString).raFile;  // get raf from the database using path string
        try{
            raFile.seek(location);                                     // move internal pointer to ensure alignment with previous calls
            raFile.write(file_chunk.chunk, 0, file_chunk.chunkSize);  // write chunkSize bytes from byte array into raFile starting at index 0 of byte array
        }
        catch(IOException e){ e.printStackTrace(); return;}    

        if(location + file_chunk.chunkSize == totalFileSize) // LET GO FILE LOCK; ENTIRE FILE HAS BEEN SENT //
            database.get(fullPathString).fileLock.release();
    }

    public synchronized String unlinkFile(String path){
        
        Path full_path = Paths.get(this.rootdir, path).normalize(); // build entire path based on root dir
        String fullPathString = full_path.toString();
        File file = full_path.toFile();

        // if path does not exist on server, error 
        if(!file.exists()) return _FILE_DNE;  
        
        ServerObject serverObject = database.get(fullPathString);

        // OBTAIN FILE LOCK
        try{serverObject.fileLock.acquire();}
		catch(InterruptedException e){ e.printStackTrace(); return ""; }
        
        file.delete(); // delete file from the rootdir

        // delete entry from the database object if it exists 
        if(database.containsKey(fullPathString))
            database.remove(fullPathString);
        
        serverObject.fileLock.release(); // DROP FILE LOCK 
        return _SUCCESS;
    }

    /**
     * Helper functiont that loads meta data for a file that either is first being touched
     * by a client or by a file that was created by a client.
     * 
     * No actual bytes are stored in this ServerObject, just meta data about the file
     * and a RAF for reading/writing
     */
    public synchronized void createNewServerObj(File file, String serverPathname){
        RandomAccessFile raFile;
        try{raFile = new RandomAccessFile(file, "rw"); } 
        catch (IOException e){e.printStackTrace();return;}            
        ServerObject newServerObj = new ServerObject(raFile, 1, serverPathname);
        database.put(serverPathname, newServerObj);
    }

    /**
     * main method that takes in 2 command line arguments: 
     * - port number to listen on for proxy connections 
     * - root directory to serve files from  
     */
    public static void main(String[] args) throws IOException{
        
        // Parse command line args
        int port = Integer.parseInt(args[0]);
        String root_dir = args[1];
        Server server = new Server(root_dir);                   // instantiate new Server object

        Registry registry = LocateRegistry.createRegistry(port); // create registry that will listen for client requests
        Naming.rebind("//localhost:"+port+"/Server", server);
    }
} 