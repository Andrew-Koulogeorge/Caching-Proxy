package common; 
import java.io.*;

/* Class used for sending file information to and from the server in chunks */
public class ByteChunk implements Serializable{
	public byte[] chunk;          // portion of file 
	public int chunkSize;        // number of bytes written into chunk

    public ByteChunk(byte[] chunk, int chunkSize){ 
        this.chunk = chunk;
        this.chunkSize = chunkSize;
    }    
    
    public ByteChunk(){
        this.chunk = null;
        this.chunkSize = -1;
    }    
}