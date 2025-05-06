package common;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;
import java.io.*;

/* Remote interface that the Proxy will be invoking to perform operations on files */
public interface ServerInterface extends Remote{
    /**
     * Checks if the cached file on the proxy is up to date with most recent copy of file stored on the server
     * @param pathname: pathname for file in question
     * @param verison_number: v-number of file on proxy
     * @return true if file is up to date with most recent server copy, false otherwise
     * NOTE: This function will only be called if there exists a cached copy on the proxy
     */
    public boolean isStale(String pathname, int verison_number) throws RemoteException;
    
    /**
     * Establishes sole access of the file to the proxy before downloading
     * @param pathname: pathname of file on server
     * @param permissions: permissions requested on file
     * @param option: numerical value 
     * @return FileObject containing the file content, current version number and its path
     */
    public FilePacket establishDownloadSession(String pathname, String permissions, int option) throws RemoteException;


    /* Sends file to proxy in chunks of size chunkSize.  */
    public ByteChunk downloadFile(String fullPathString, int location, int chunkSize, long totalSize)throws RemoteException;


    /* Receiving file from proxy in chunks of size chunkSize.  */
    public void uploadFile(ByteChunk file_chunk, String fullPathString, int location, long totalFileSize)throws RemoteException;

    /**
     * Client has closed a file and the updated file is being propogated to the server
     * @param file: file to be updated on the server
     * @return true if load was good; false otherwise
     */
    public int establishUploadSession(String serverPathname) throws RemoteException;

    /* Delete a file on the server */
    public String unlinkFile(String path) throws RemoteException;
}