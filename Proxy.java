/**
 * @author Andrew Koulogeorge | 15440 CMU
 * The role of the proxy node in the distributed file system is to perform caching on behalf of the connected clients to improve their
 * end to end latency time on requests. 
 */

import java.io.*;
import java.nio.file.OpenOption;
import java.util.concurrent.ConcurrentHashMap; 
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque; 
import java.util.concurrent.Semaphore;
import java.util.ArrayList; 		

/* Copying File Support */
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/* imports for Java RMI */
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;

/* import objects from shared package */
import common.FilePacket;
import common.ServerInterface;
import common.CacheEntry;
import common.ByteChunk;

class Proxy {
	
	private static ServerInterface server; 							// remote object to server
	private static String cacheDir;	   								// directory name to store cached files
	private static final Semaphore countLock = new Semaphore(1); 	// lock for creating new fds
	private static final Semaphore cacheLock = new Semaphore(1);   	// semaphore for acessing cache entries
	private static int global_fd = 10000; 							// fd shared across all clients to this proxy instance


	private static ConcurrentHashMap<String, Integer> globalVersions = new ConcurrentHashMap<String, Integer>();
	private static ConcurrentHashMap<String, ArrayList<CacheEntry>> cacheSubdirectorys = new ConcurrentHashMap<String, ArrayList<CacheEntry>>();
	private static ConcurrentLinkedDeque<CacheEntry> lruCache = new ConcurrentLinkedDeque<>(); 
	private static int currCacheSize;								// only ever touched when the cacheLock is held
	private static int maxCacheSize;

	/* constants for open error handling */
    public static final int _READ = 1;
    public static final int _WRITE = 2;
    public static final int _CREATE = 3;
    public static final int _CREATE_NEW = 4;
    
	/* unlink error handling */
	public static final String _FILE_DNE = "file does not exist";
	
	/* size of packets used when sending files to and from server */
	public static final int _CHUNK_SIZE = 50000;

	/* Helper function that ensures no 2 clients connected to this proxy get the same fd */
	private static int getSyncfd(){
		int newFd;
		try{
			countLock.acquire();
			newFd = global_fd;
			global_fd++;			
			countLock.release();
		}
		catch(InterruptedException e){e.printStackTrace(); return -1; }
		return newFd;
	}

						/* LRU HELPER METHODS */

	/**
	 * Helper function to free up needed space in the LRU Cache for a newly requested file
	 * 
	 * Scan over LRU cache from least to most recently used. If the file is evictable, 
	 * 
	 * There exists two cases when we need to free up space on the cache: when we are copying an existing file
	 * in the cache for writing or when we are fetching an updated/new file from the server
	 * Note that this function assumes we have the cacheLock
	 * @param numBytesToRemove number of bytes needed to remove from cache to make space for new file
	 * @return numBytes we actually removed from cache (will be at least as large as numBytesToRemove)
	 */

	private static long makeRoomInLRUCache(long numBytesToRemove){
		long numBytesRemoved = 0;
		for (CacheEntry cacheEntry : lruCache){ // loop over the LRU cache 
			if(evictable(cacheEntry)){
				long evictStatus = evictFromLRUCache(cacheEntry);
				if(evictStatus == -1) return -1;
				numBytesRemoved += evictStatus;
			}
			if(numBytesRemoved >= numBytesToRemove)
				return numBytesRemoved;  		
		}
		throw new RuntimeException("Assumption that cache will always have enough space broken");
	}

	/**
	 * Helper function for makeRoomInLRUCache. 
	 * A CacheEntry is evictable if there are no observers to that file
	 * Note that this function assumes we have the cacheLock
	 * @return true if the file is evictable and false otherwise
	 */
	private static boolean evictable(CacheEntry entryToEvict){
		return (entryToEvict.numReaders == 0 && !entryToEvict.writerPresent);

	}
	
	/**
	 * Helper function to remove entry from LRU Cache.
	 * Eviction can occur for two reasons:
	 * 1) The cache is at capacity and this file has been chosen by the LRU policy (called in open)
	 * 2) This file has become stale and we are cleaning out useless files form the cache (called in close)
	 * 
	 * We dont have to worry about concurrency issues when removing with other reads and writes since we only
	 * remove from the cache when the file has no readers/writers
	 * 
	 * Note that after we remove cacheObject from lru, its still stored in the directoryStructure
	 * Note that this function assumes we have the cacheLock
	 * @param entryToEvict CacheEntry we are removing from Cache
	 * @return number of bytes contained in the file we just removed
	 */
	private static long evictFromLRUCache(CacheEntry entryToEvict){
		String cachePathname = entryToEvict.cachePathname;
		File file = new File(cachePathname); 				// remove the physical file from cache directory
		long fileSize = 0;
        if(file.exists()){
			fileSize = file.length();
			boolean deleted = file.delete();
            if(!deleted) return -1; 							// failed to delete file
		}
		lruCache.remove(entryToEvict); 		 				// remove from LRU structure
		entryToEvict.hasBeenEvicted = true; 				// set flag so we remove from cacheDir on next check
		currCacheSize -= fileSize;							// update size of cache
		return fileSize;
	}

	/**
	 * Helper function that marks the given cache entry as the most recently used (called in close)
	 * 1) Scan the Cache looking for the entry of interest
	 * 2) Remove the entry from the Cache
	 * 3) Insert the entry back at the end of the Cache (end is warmest, start is coldest)
	 * 
	 * NOTE: Function assumes that the cache semaphore has been obtained
	 * @param entryToUpdate: Cache Entry to update order
	 */
	private static void updateLRUOrder(CacheEntry entryToUpdate){
		lruCache.remove(entryToUpdate);
		lruCache.add(entryToUpdate);
	}

	/**
	 * Performs linear scan of the cache to extract entry
	 * @param cachePathname: location in cache where item is stored
	 * NOTE: This function assumes the cacheLock is held by the executing thread
	 */
	private static CacheEntry queryCache(String cachePathname){
		for (CacheEntry cacheEntry : lruCache){
			if(cacheEntry.cachePathname.equals(cachePathname))
				return cacheEntry;
		}
		return null;
	}	

	/* Helper function to create location for new file in cache */
	private static String makeNewCachePath(String path, int newFd){
		return Proxy.cacheDir + "/" + path + "-" + String.valueOf(newFd); 		
	}

	/* Helper function to copy files to avoid extra RPCs */
	private static boolean copyCacheFile(String src, String dest){
		Path source = Paths.get(src); // file to copy
		Path destination = Paths.get(dest); // destination path
		try{Files.copy(source, destination);} 
		catch(IOException e) {e.printStackTrace();return false;}				
		return true;
	}

					/* LRU HELPER METHODS */

	/**
	 * FileHandler class implements file handling functions for the client. 
	 * 
	 * Each client gets its own instance of the FileHandler class.
	 * 
	 * Each class contains: 
	 * - map between client fd and the clients local copy of the file
	 * - map between local file copy and global copy (LATER FOR DEALING WITH CONCURRENCY)
	 * 
	 * LATER FOR DEALING WITH CONCURRENCY:
	 * Once a file is closed, the entry is removed from the clients fd table and the local copy replaces the global copy
	 * 
	 */
	private static class FileHandler implements FileHandling {
		
		private HashMap<Integer, RandomAccessFile> openFiles = new HashMap<Integer, RandomAccessFile>();
		private HashMap<Integer, Integer> openDirs = new HashMap<Integer, Integer>();
		private HashMap<Integer, String> openFilePermissions = new HashMap<Integer, String>();
		private HashMap<Integer, String> openFileCachePaths = new HashMap<Integer, String>();

		/**
		 * Need to implement the C behavior in a custom manner in Java since RAF constructor does not
		 * directly support C open semantics 
		 * 
		 * OpenOption Semantics:
		 * o = READ -> read 
		 * o = WRITE -> read/write 
		 * o = CREATE -> read/write, create if needed
		 * o = CREATE_NEW -> read/write, file must not already exist
		 * 
		 * Error Handling:
		 * - [OpenOption isnt a valid case] return EINVAL (invalid parameter)
		 * - [CREATE_NEW flag is set but  file exists] return EEXIST (Already Exists)
		 * - [READ/WRITE but no file exists] returns ENOENT (Not Found)
		 * - [Open a file we dont have permission to] returns EPERM
		 * 
		 * NOTE: CacheFilePath is the string used to specify each file in the cache. It uses the name of the file
		 * along with the fd number used to refference that file
		 * 
		 */
		public int open( String path, OpenOption o) {
			String mode = null;
			int option = -1;
			int newFd = getSyncfd();	// fd corresponding to newly opened file 
			switch(o) // establish mode from OpenOption enum
			{
				case READ:
					mode = "r";
					option = _READ;
					break;
				case WRITE:
					mode = "rw";
					option = _WRITE;
					break;
				case CREATE:
					mode = "rw";
					option = _CREATE;
					break;
				case CREATE_NEW:
					mode = "rw";
					option = _CREATE_NEW;
					break;
				default: 
					return Errors.EINVAL; // OpenOption not handled correctly
			}
											// GET CACHE LOCK BEFORE TOUCHING CACHE ENTRIES //
			try{cacheLock.acquire();}
			catch(InterruptedException e){ e.printStackTrace(); return -1; }

			/* check the cache for file */

			int queryStatus = 0;
			if(cacheSubdirectorys.containsKey(path))
				queryStatus = queryCacheDirectory(path, newFd, mode);
			if(queryStatus > 0) return newFd; 				// cache hit!
			else if(queryStatus == -1) return -1;			// cache error
			/* file not in cache; fetch from server */
			FilePacket filePacket = null;
			String newCachePath;
			try{filePacket = server.establishDownloadSession(path, mode, option);}
			catch(RemoteException e){e.printStackTrace(); cacheLock.release(); return -1; }
			
			/* Error Handling for missing files, directories, ect. */
			int errno = openErrors(filePacket.errorMsg);
			if(errno!=0){cacheLock.release(); return errno;}

			path = filePacket.normalizedCachePath; // ensure path normalized from server

			/* ensure cache has enough room to fit the incoming file from the server */
			if(currCacheSize + filePacket.size > maxCacheSize){
				// need to make room in the cache for this file with eviction 
				long numBytesRemoved = makeRoomInLRUCache(currCacheSize + filePacket.size - maxCacheSize);
				if(numBytesRemoved == -1){
					cacheLock.release(); // DROP CACHE LOCK AFTER TOUCHING CACHE ENTRIES //
					return -1;
				} 
			}			
			
			/* No errors -> Writing file to cache */ 			
			newCachePath = makeNewCachePath(path, newFd);
			File file = new File(newCachePath); 
			RandomAccessFile raFile = null; // ensure that the file exists before we create a RAF; store pointer in CacheEntry
			try{
				File parentDir = file.getParentFile();
				if (parentDir != null && !parentDir.exists()) {
					boolean dirCreated = parentDir.mkdirs();
					if (!dirCreated) {
						cacheLock.release();
						return -1;
					}
				}							
				if(!file.exists()) file.createNewFile();
				raFile = new RandomAccessFile(file, "rw");
				}
			catch(IOException e){ cacheLock.release(); return -1;}		

			// stream in file information from the server in chunks; if server does not have the file, fetched_size=0
			int num_bytes_recv = 0;
			while(num_bytes_recv < filePacket.size){
				ByteChunk file_chunk;  
				try{ 
					file_chunk = server.downloadFile(filePacket.serverPathname, num_bytes_recv, _CHUNK_SIZE, filePacket.size);
					raFile.write(file_chunk.chunk, 0, file_chunk.chunkSize);				
				}
				catch(IOException e){e.printStackTrace(); cacheLock.release(); return -1;}
				num_bytes_recv += file_chunk.chunkSize;	
			}
			
			/* construct cache entry for this FileObj */
			if(!cacheSubdirectorys.containsKey(path)) // create new list for file if it has never been open before
				cacheSubdirectorys.put(path, new ArrayList<CacheEntry>());

			CacheEntry new_cache_entry = new CacheEntry(0, false, filePacket.versionNumber, 
														filePacket.serverPathname, newCachePath, path);			
			globalVersions.put(path, filePacket.versionNumber); 				// new file -> inital version in global versions
			if(mode.equals("r")) new_cache_entry.numReaders = 1; 
			else new_cache_entry.writerPresent = true;

			RandomAccessFile client_ra_file = null;
			try{client_ra_file = new RandomAccessFile(file, mode);} // make raf for this file with the correct permissions
			catch(IOException e){
				e.printStackTrace();
				cacheLock.release(); 
				return -1;
			}		
			cacheSubdirectorys.get(path).add(new_cache_entry); 	// add to global cache information
			lruCache.add(new_cache_entry);			
			currCacheSize += num_bytes_recv;			
			
			openFiles.put(newFd, client_ra_file);               // add to local information to each client
			openFileCachePaths.put(newFd, newCachePath);
			openFilePermissions.put(newFd, mode);
			
			cacheLock.release(); 
			return newFd; 
		}
	

		/**
		 * If the file being closed had write permissions, propogate the contents of this file to the server in chunks
		 * by leveraging several RPC calls. See uploadWithChunking for details on how chunking is implemented
		 * 
		 * If the file was just for reading, remove it from [fd -> CacheEntry] 
		 * 
		 * When a file is closed, we need to update its version number locally. This way, future
		 * readers/writers who want to use the same cache copy can know if the file is up to date
		 * 
		 * After closing the file and getting the updates version number, we perform cleanup on the cache by scanning
		 * for any copies of the file that we just closed which are out of data and which have no readers or writers.
		 * Files of this kind will never be used again so we can help take load off the cache by evicting them early
		 * 
		 * @param fd: file descriptor used by client to interact with file
		 */
		public int close( int fd ) {
			
			/* Error Handling */
			if(openDirs.containsKey(fd)){
				openDirs.remove(fd);
				return 0;
			}
			if(!openFiles.containsKey(fd)) return Errors.EBADF;
			

			String filePermissions = openFilePermissions.get(fd);
			CacheEntry cacheEntry = queryCache(openFileCachePaths.get(fd)); // CLAIM: dont need concurrency control when pushing to server. This Cache Entry is not shared. Sole Writer
			if(filePermissions.equals("rw")){ // only push updates back to the server if we wrote to this file	
				int chunkingErrno = uploadWithChunking(cacheEntry, openFiles.get(fd));
				if(chunkingErrno!=0) return -1;
				cacheEntry.writerPresent = false;
			}
			else{ cacheEntry.numReaders--; }
			
											// GET CACHE LOCK BEFORE TOUCHING CACHE ENTRIES //
			try{cacheLock.acquire();}
			catch(InterruptedException e){ e.printStackTrace(); return -1; }

			/* Update LRU order in cache */
			updateLRUOrder(cacheEntry);

			ArrayList<CacheEntry> cachedFiles = cacheSubdirectorys.get(cacheEntry.path);
			for(int idx = cachedFiles.size()-1; idx >=0 ; idx--){ // need to count index backwards to enable deletion
				CacheEntry cachedFileToEvict = cachedFiles.get(idx); // get this file

				if(cachedFileToEvict.hasBeenEvicted){ // never consider old evictions still stored in cacheSubdirectorys
					cachedFiles.remove(cachedFileToEvict);
					continue;
				}
		
				if(cachedFileToEvict.versionNumber < globalVersions.get(cacheEntry.path) && 
					cachedFileToEvict.numReaders == 0 && cachedFileToEvict.writerPresent == false){
					evictFromLRUCache(cachedFileToEvict);
				}					
			}			
											// RELEASE CACHE LOCK AFTER TOUCHING CACHE ENTRIES //
			cacheLock.release();

			// clean up client information 
			openFiles.remove(fd);
			openFilePermissions.remove(fd);
			openFileCachePaths.remove(fd);
			return 0;
		}

		/**
		 * Write buf of bytes to the file. 
		 * Assuming that all buf.length bytes get written to fd
		 * 
		 * Should not synchronized at the level of the proxy because should never have more then one writer on the same file
		 * @param fd: file descriptor used by client to interact with file
		 * @param buf: byte array containing bytes client will write into the file pointed to by fd
		 * @return number of bytes written to the file from the buffer 
		 */
		public long write( int fd, byte[] buf ) {
			/* Error Handling */
			if(openDirs.containsKey(fd))
				return Errors.EISDIR; // fd invalid 
			if(!openFiles.containsKey(fd) || openFilePermissions.get(fd).equals("r"))
				return Errors.EBADF; 

			RandomAccessFile raFile = openFiles.get(fd); // get entry from cache and writ to file
			try{
				// file size can change after a user writes to a file; need to reflect changes in currCacheSize
				long sizeBeforeWrite = raFile.length();
				raFile.write(buf); 
				long sizeAfterWrite = raFile.length();
				
				cacheLock.acquire();

				currCacheSize += (sizeAfterWrite - sizeBeforeWrite); 			
				if(currCacheSize > maxCacheSize) // if cache overflowed, evict 
					makeRoomInLRUCache(currCacheSize-maxCacheSize);
				
				cacheLock.release();
				return buf.length;
			}
			catch(InterruptedException e){ e.printStackTrace();return -1; }
			catch (IOException e){ e.printStackTrace();return -1;}
		}

		/**
		 * @param fd: file descriptor used by client to interact with file
		 * @param buf: byte array containing bytes client will write into the file pointed to by fd
		 * @return number of bytes read from the file
		 * 
		 * NOTE: Java read returns -1 at end of file, C read return 0
		 */
		public long read( int fd, byte[] buf ) {
			
			/* Handle Errors */
			int errno = readErrors(fd, buf);
			if(errno != 0) return errno;

			RandomAccessFile raFile = openFiles.get(fd);
			try{
				int num_bytes_read = raFile.read(buf); // check if this fd is even valid
				if(num_bytes_read == -1) return 0;
				else return num_bytes_read;	
			}
			catch(IOException e){ e.printStackTrace();return -1; }
		}
	
		/**
		 * Returns new offset position in the file pointed to by fd
		 * 
		 * seek method in Java sets the file pointer offset measured from the beggining of the file
		 * so we use a switch statement on the LSeekOption to compute the needed offset 
		 */
		public long lseek( int fd, long pos, LseekOption o ) {
			long file_length = 0;
			long file_pointer = 0;
			if(openDirs.containsKey(fd))return Errors.EISDIR; 
		
			RandomAccessFile raFile = openFiles.get(fd);
			try{
				file_length = raFile.length();
				file_pointer = raFile.getFilePointer();
			}
			catch (IOException e){
				if(!openFiles.containsKey(fd)){return Errors.EBADF;}				
				return Errors.EPERM;				
			}
			long offset = 0;
			switch(o){
				case FROM_CURRENT:
					offset = file_pointer;
					break;
				case FROM_START:
					offset = 0;
					break;
				case FROM_END:
					offset = file_length;
					break;
			}
			long new_location = offset + pos;
			try{raFile.seek(new_location);}
			catch(IOException e){ return Errors.EINVAL; } // if(new_location < 0)
			return new_location;
		}

		/**
		 * Make RPC to server to delete file from server's filesystem
		 * If this file exists as a cache copy on some proxy somewhere else with write permissions, this file 
		 * could be restored upon that files closing. 
		 * 
		 * Any subsequent attempt to open the file at this path from this proxy will fail since the file no longer exists
		 * but any user that current has this file open for either reading/writing can continue 
		 * 
		 */
		public int unlink( String path) {
			String return_msg;
			try{return_msg = server.unlinkFile(path);}
			catch(RemoteException e){ e.printStackTrace(); return -1;}
			if(return_msg.equals(_FILE_DNE))
				return Errors.ENOENT;
			return 0;
		}
		

		/**
		 * Helper function called during open to check for an existing file that can satisfy user query
		 * @param path name of file user attempted to open
		 * @return newFd if file was found; 0 if no file was found; -1 on error
		 */
		private int queryCacheDirectory(String path, int newFd, String mode){
			String newCachePath;		// path in cache of file to be opened;

			ArrayList<CacheEntry> cachedFiles = cacheSubdirectorys.get(path); // get list of Cache Entries associated with this path
			for(int idx = cachedFiles.size()-1; idx >= 0; idx--){ // loop performed backwards to enable evictions within the loop
				CacheEntry cachedFile = cachedFiles.get(idx);

				if(cachedFile.hasBeenEvicted){ 
					cachedFiles.remove(cachedFile); // never consider old evictions
					continue;
				}

				if(!cachedFile.writerPresent){ 
					boolean isStale = true;
					try{ isStale = server.isStale(cachedFile.serverPathname, cachedFile.versionNumber); }
					catch(RemoteException e){e.printStackTrace(); return -1;}

					if(!isStale) {
						newCachePath = cachedFile.cachePathname;
						if(mode.equals("r")) cachedFile.numReaders++;
						else{ // file found has readers refferencing it; make a copy and place it into the cache
							newCachePath = makeNewCachePath(path, newFd);

							// before we make a copy of this file, we need to (1) check if we will go over the limit (2) evict if we are over
							File fileToCopy = new File(cachedFile.cachePathname);
							boolean copyStatus = copyCacheFile(cachedFile.cachePathname, newCachePath);

							if(!copyStatus){ cacheLock.release(); return -1;} 								
							
							long numBytesInFile = fileToCopy.length();

							// need to add this new cache entry into the cache [COPY FIRST THEN EVICT]
							CacheEntry newCacheEntry = new CacheEntry(0, true, cachedFile.versionNumber, cachedFile.serverPathname, newCachePath, path);
							cacheSubdirectorys.get(path).add(newCacheEntry); 	
							lruCache.add(newCacheEntry);
							currCacheSize += numBytesInFile;
							
							// Did this copy put us over the limit? If so, evict
							if(currCacheSize > maxCacheSize){
								long numBytesRemoved = makeRoomInLRUCache(currCacheSize - maxCacheSize);
								if(numBytesRemoved == -1){
									cacheLock.release(); // DROP CACHE LOCK AFTER TOUCHING CACHE ENTRIES //
									return -1;
								}
							}								
						}
						RandomAccessFile raFile  = null; // open a new RAF and give to client
						try{raFile = new RandomAccessFile(new File(newCachePath), mode);}
						catch(IOException e){ e.printStackTrace(); return -1; }					
					
						openFiles.put(newFd, raFile);
						openFileCachePaths.put(newFd, newCachePath);
						openFilePermissions.put(newFd, mode);
					
						cacheLock.release(); // DROP CACHE LOCK AFTER TOUCHING CACHE ENTRIES //
						return newFd;							
					}
				}
			}
			return 0; // not found in cache
		}				
	
		/**
		 * Hepler function to upload writable file to server in chunks
		 * This entry in the cache gets the updated version number from the server for future reads/writes in the cache 
		 * @param cacheEntry: entry of cache that stores file being uploaded to server
		 */
		private int uploadWithChunking(CacheEntry cacheEntry, RandomAccessFile raFile){
				/* Perform Chunking */
				byte[] file_data = new byte[_CHUNK_SIZE]; 								
				int num_bytes_read;
				long totalFileSize;
				int file_location = 0; 

				// send RPC call to establish lock on file we are sending to the server (TO DO: IMPLEMENT SEMAPHORES)
				int updatedVersionNumber = -1;
				try{updatedVersionNumber = server.establishUploadSession(cacheEntry.serverPathname);}
				catch(IOException e){e.printStackTrace(); return -1;}
				if(updatedVersionNumber == -1) return -1; 				// failed to establish Upload Session
				
				cacheEntry.versionNumber = updatedVersionNumber;
				globalVersions.put(cacheEntry.path, updatedVersionNumber); // track most up to date version for each file;

				try{ // still upload file to the server if the file is empty
					raFile.seek(0);					
					totalFileSize = raFile.length();
					if(totalFileSize == 0) // send single RPC to free lock 
						server.uploadFile(new ByteChunk(file_data, 0), cacheEntry.serverPathname, -1, totalFileSize);
					else{ // loop until read all bytes from the file
						while((num_bytes_read = raFile.read(file_data, 0, _CHUNK_SIZE)) != -1){
							server.uploadFile(new ByteChunk(file_data, num_bytes_read), cacheEntry.serverPathname, file_location, totalFileSize);
							file_location += num_bytes_read; // update file location based on number of bytes we read
						}
					}
				}
				catch(IOException e){e.printStackTrace(); return -1;}
				return 0;
		}


		/* Helper function for error handling when opening a file */
		private int openErrors(String errorMessage){
			if(errorMessage.equals("File Not Found"))
				return Errors.ENOENT; // read or write to file that does not exist					
			else if(errorMessage.equals("File exists and we are trying to create it"))
				return Errors.EEXIST; // trying to create new file while it already exists
			else if(errorMessage.equals("not allowed to open dir with write permissions"))
				return Errors.EISDIR;
			else if(errorMessage.equals("read directory special case")){
				int dir_fd = getSyncfd();
				openDirs.put(dir_fd, dir_fd); // assuming we just need to keep track of the fd
				return dir_fd;
			}			
			else if(errorMessage.equals("Trying to acess outside of rootdir"))
				return Errors.EPERM;
			return 0;
		}

		/* Helper function for read to handle bad user behavior */
		private int readErrors(int fd, byte[] buf){
			if(openDirs.containsKey(fd)) // cant read from dir
				return Errors.EISDIR; 
			if(!openFiles.containsKey(fd))
				return Errors.EBADF;
			if(buf == null)
				return Errors.EINVAL;				
			return 0;
		}

		public void clientdone() {
			return;
		}
	}
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	/**
	 * main method for Proxy class:
	 * 
	 * Handle 4 command line args that define the Proxy class
	 * 
	 * serverip: server IP adress for connection
	 * port: server port for connection
	 * cachedir: directory proxy will use to cache files
	 * cachesize: max size of cache in bytes
	 */
	public static void main(String[] args) throws Exception {

		/* extract command line args */
		String ip_adress = args[0]; 		   			   		   // extract IP 
		int port = Integer.parseInt(args[1]); 	   		   		   // extract port
		String local_cache_dir = args[2];				   		   // extract directory where cache will live
		Proxy.maxCacheSize = Integer.parseInt(args[3]);		   	   // extract size of cache in bytes

		// make an empty directory at the cachedir path
		String currentDir = System.getProperty("user.dir");
        File cacheDir = new File(currentDir, local_cache_dir);
		Proxy.cacheDir = cacheDir.getAbsolutePath();
        
        if(!cacheDir.exists()) cacheDir.mkdirs();

		// establish connection with server (1 connection to server per proxy) 
		String url = "//"+ip_adress+":"+port+"/Server";
		Proxy.server = (ServerInterface) Naming.lookup(url); 

		(new RPCreceiver(new FileHandlingFactory())).run();  // proxy now sitting and waiting to make connections with client
	}
}		