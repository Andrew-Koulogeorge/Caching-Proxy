/**
 * Debugging Code
*/
#include <stdio.h>
#include <stdbool.h>
#include <unistd.h>
#include <string.h>
#include <sys/stat.h>

#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>

int test1();
int test2();
int test3();
int test4();
int test5();
int test6();
int test7();
int lseek_test();
int unlink_test1();
int unlink_test2();
int unlink_test3();
int big_read1();
int big_read2();
int chunk_1_test();
int clean_on_close();
int evication1();
int evication2();
int evication3();
int subdirectory_test1();
int subdirectory_test2();
int subdirectory_test3();
int write_overflow();
bool file_exists(const char *filename);
int lru_2();

int main(){
    // subdirectory_test1();
    // subdirectory_test2();
    // subdirectory_test3();
    test1();
    test2();
    test3();
    test4();
    test5();
    test6();
    test7();
    lseek_test();
    unlink_test1();
    unlink_test2();
    unlink_test3();
    // big_read1();
    // big_read2();
    clean_on_close();

    // lru_2();
    // write_overflow();
    // evication1();
    // evication2();
    // evication3();
}

int lru_2(){
    int fdA = open("A", O_RDONLY); // slow reader

    int fdB = open("B", O_RDONLY);
    close(fdB);
    int fdC = open("C", O_RDONLY);
    close(fdC);
    int fdD = open("D", O_RDONLY);
    close(fdD);
    int fdE = open("E", O_RDONLY);
    close(fdE);
    
    int fdF = open("F", O_RDONLY);
    close(fdF);
    int fdG = open("G", O_RDONLY);
    close(fdG);
    int fdH = open("H", O_RDONLY);
    close(fdH);


    fdG = open("G", O_RDWR); // slow writes
    fdH = open("H", O_RDWR);
    
    close(fdG);             // slow writes finish
    close(fdH);

    fdG = open("G", O_RDONLY); // read
    close(fdG);
    fdH = open("H", O_RDONLY);
    close(fdH);

}


// make cache limit 120
int write_overflow(){
    // pull one small file into the cache for reading
    int fd1 = open("51bytes.txt", O_RDONLY);
    close(fd1);
    int fd2 = open("51bytes2.txt", O_RDWR);
    int num_bytes_write = write(fd2, "Suprise! I wrote over the old message. Writing to files can be strange!\n", 73);
    close(fd2);    

    char path_10001[256] = "cache/51bytes2.txt-10001";
    char path_10000[256] = "cache/51bytes.txt-10000";
    
    // Check if the first file exists in the cache subdirectory
    bool file10001_exists = file_exists(path_10001);
    
    // Check if the second file doesn't exist
    bool file10000_not_exists = !file_exists(path_10000);
    
    // Return true only if both conditions are met
    if (file10001_exists && file10000_not_exists){
        printf("Passed write overflow!\n");
        unlink("51bytes2.txt");
        fd2 = open("51bytes2.txt", O_CREAT);
        num_bytes_write = write(fd2, "This is exactly 51 bytes of readable text content.", 51);
        close(fd2);    
        return 0;
    }
    printf("Failed write overflow :(\n");

}

// Can I handle edge cases of files that are outside the root directory with ..?
// how does the server know to reject files that go outside its path
int subdirectory_test3(){
    char buf[100];
    int fd1 = open("huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("subdir/huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("subdir/subdir/huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("huge_file/huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("subdir/subdir2/huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("subdir/subdir/../subdir/../../subdir/./subdir/huge_file", O_RDONLY);
    close(fd1);
    fd1 = open("../hammer", O_RDONLY);
    close(fd1);
    
    
}

// Can I add subdirectory structure to the server ?
int subdirectory_test2(){
    char buf[100];
    int fd1 = open("testing_dir2/dne_on_server.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd1, "Pass\n", 5);
    close(fd1);
    fd1 = open("testing_dir2/dne_on_server.txt", O_RDONLY);
    int num_bytes_read = read(fd1, buf, 100);
    buf[num_bytes_read] = '\0';  // Add null terminator
    close(fd1);
    printf("%s",buf);
}

// Can I handle having files in my cache that are at different levels on the server?
int subdirectory_test1(){
    int fd1 = open("testing_dir1/test0.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd1, "Test\n", 5);
    int fd2 = open("51bytes.txt", O_RDWR|O_CREAT);
    num_bytes_write = write(fd1, "Test\n", 5);
    int fd3 = open("51bytes2.txt", O_RDWR|O_CREAT);
    num_bytes_write = write(fd1, "Test\n", 5);
    int fd4 = open("51bytes3.txt", O_RDWR|O_CREAT);
    num_bytes_write = write(fd1, "Test\n", 5);
    int fd5 = open("testing_dir1/deeper/deeperdeeper/sad.txt", O_RDWR|O_CREAT);
    close(fd1);close(fd2);close(fd3);close(fd4);close(fd5);
}

int big_read1(){
    int fd = open("big_test.txt", O_RDONLY);
    close(fd);
    printf("Passed Big Test\n");
}

int big_read2(){
    int fd = open("big_test.txt", O_RDWR);
    int num_bytes_write = write(fd, "Test2\n", 6);
    close(fd);
    printf("Passed Big Test\n");
}

bool file_exists(const char *filename) {
    struct stat buffer;
    return (stat(filename, &buffer) == 0);
}


// set cache size = 100
int evication1(){
    int fd1 = open("51bytes.txt", O_RDWR|O_CREAT);
    close(fd1);
    int fd2 = open("51bytes2.txt", O_RDWR|O_CREAT);
    close(fd2);
    char path_10001[256] = "cache/51bytes2.txt-10001";
    char path_10000[256] = "cache/51bytes.txt-10000";
    
    // Check if the first file exists in the cache subdirectory
    bool file10001_exists = file_exists(path_10001);
    
    // Check if the second file doesn't exist
    bool file10000_not_exists = !file_exists(path_10000);
    
    // Return true only if both conditions are met
    if (file10001_exists && file10000_not_exists){
        printf("Passed evication1!\n");
        return 0;
    }
    printf("Failed evication1 :(\n");
}
// set cache size = 150
int evication2(){
    int fd1 = open("51bytes.txt", O_RDWR|O_CREAT);
    close(fd1);
    int fd2 = open("51bytes2.txt", O_RDWR|O_CREAT);
    close(fd2);
    
    int fd3 = open("51bytes3.txt", O_RDWR|O_CREAT);
    close(fd2);

    char path_10000[256] = "cache/51bytes.txt-10000";
    char path_10001[256] = "cache/51bytes2.txt-10001";
    char path_10002[256] = "cache/51bytes3.txt-10002";
    
    // Check if the first file exists in the cache subdirectory
    bool file10001_notexists = !file_exists(path_10000);
    bool file10002_exists = file_exists(path_10001);
    bool file10003_exists = file_exists(path_10002);
    
    // Return true only if both conditions are met
    if (file10001_notexists && file10002_exists && file10003_exists){
        printf("Passed evication2!\n");
        return 0;
    }
    printf("Failed evication1 :(\n");
}
// set cache size = 150
int evication3(){
    int fd1 = open("51bytes.txt", O_RDWR|O_CREAT);
    close(fd1);
    int fd2 = open("51bytes2.txt", O_RDWR|O_CREAT);
    close(fd2);

    fd1 = open("51bytes.txt", O_RDONLY); // this should change the order of the use
    close(fd1);
    
    int fd3 = open("51bytes3.txt", O_RDWR|O_CREAT);
    close(fd2);

    char path_10000[256] = "cache/51bytes.txt-10000";
    char path_10001[256] = "cache/51bytes2.txt-10001";
    char path_10002[256] = "cache/51bytes3.txt-10003";
    
    // Check if the first file exists in the cache subdirectory
    bool file10001_exists = file_exists(path_10000);
    bool file10002_notexists = !file_exists(path_10001);
    bool file10003_exists = file_exists(path_10002);
    
    // Return true only if both conditions are met
    if (file10001_exists && file10002_notexists && file10003_exists){
        printf("Passed evication3!\n");
        return 0;
    }
    printf("Failed evication1 :(\n");
}



// do we clean stale files from the cache?
int clean_on_close(){
    const char* directory = "cache";
    const char* filename = "test.txt-10000";
    
    /* Create full path by concatenating directory and filename */
    char fullpath[256];
    snprintf(fullpath, sizeof(fullpath), "%s/%s", directory, filename);
    
    int fd = open("test.txt", O_RDWR|O_CREAT);
    close(fd);
    fd = open("test.txt", O_RDONLY);
    int fd2 = open("test.txt", O_RDWR); // will make copy of the read file
    int num_bytes_write = write(fd2, "lru_test1\n", 10);
    close(fd2); // updates go to server; cant evict other file because still reader
    close(fd); // should clean up "test.txt-10000" from the cache

    if (!access(fullpath, F_OK) == 0) {
        printf("Passed clean on close!\n");
    } else {
        printf("Failed clean on close\n");
    }
    unlink("test.txt");

}
int test1(){
    // open and close a file without errors
    int fd = open("test.txt", O_RDWR|O_CREAT);    
    close(fd);
    unlink("test.txt");
    printf("Passed Test 1!\n");
}

int test2(){
    // test2: write and push changes and make sure others can see the changes
    char buf[100];
    printf("A\n");
    int fd = open("test2.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd, "Test0\n", 6);
    int val = close(fd);
    printf("B\n");
    fd = open("test2.txt", O_RDWR);
    num_bytes_write = write(fd, "Test2\n", 6);
    val = close(fd);
    printf("C\n");
    fd = open("test2.txt", O_RDONLY);
    int num_bytes_read = read(fd, buf, 100);
    close(fd);
    unlink("test2.txt");
    if(!strcmp(buf, "Test2\n")){
        printf("Passed Test 2!\n");
    }
    else{
        printf("Failed test2: read from file: do we see the changes from the other user:\n%s\n", buf);
    }
}

int test3(){
    // cache should only have 1 file in it
    char buf[100];
    int num_bytes_read;
    int fd0 = open("test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd0, "Test3\n", 6);
    close(fd0);
    int fd = open("test.txt", O_RDONLY);
    int fd2 = open("test.txt", O_RDONLY);               // reuse cached entry
    int fd3 = open("test.txt", O_RDONLY);
    int fd4 = open("test.txt", O_RDONLY);
    int fd5 = open("test.txt", O_RDONLY);
    int fd6 = open("test.txt", O_RDONLY);

    num_bytes_read = read(fd, buf, 100);
    num_bytes_read = read(fd2, buf, 100);
    num_bytes_read = read(fd3, buf, 100); 
    num_bytes_read = read(fd4, buf, 100); 
    num_bytes_read = read(fd5, buf, 100); 
    num_bytes_read = read(fd6, buf, 100); 
    close(fd);close(fd2);close(fd3);close(fd4);close(fd5);close(fd6);    
    unlink("test.txt");
    printf("Passed Test 3 if there is 1 entry in the cache\n");
}

int test4(){
    // test 4: if we write to a file, it should be marked as stale
    char buf[100];
    int fd0 = open("test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd0, "Test4\n", 6);
    close(fd0);
    int fd = open("test.txt", O_RDONLY);
    int num_bytes_read = read(fd, buf, 100);
    int fd2 = open("test.txt", O_RDWR);                         // when we copy a file, make sure its in the cache
    num_bytes_write = write(fd2, "Suprise! I wrote over the old message. Writing to files can be strange!\n", 73);
    close(fd2); // update the version number of test
    
    fd2 = open("test.txt", O_RDONLY);               // cant reuse cached entry; fetch the new version (should have 3 cache copies)
    close(fd);
    close(fd2);    
    unlink("test.txt");
    printf("Passed Test 4 if the open after write found stale\n");
}

/*test.txt = "hi"*/
int test5(){
    int fd0 = open("test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd0, "Test5\n", 6);
    close(fd0);
    // test 5: last writer wins!
    char buf[100];
    int fd = open("test.txt", O_RDWR);
    int fd2 = open("test.txt", O_RDWR);               // reuse cached entry
    int fd3 = open("test.txt", O_RDWR);
    int fd4 = open("test.txt", O_RDWR);
    int fd5 = open("test.txt", O_RDWR);
    int fd6 = open("test.txt", O_RDWR);

    int num_bytes_read = write(fd, "Im writer 1!\n", 14); 
    num_bytes_read = write(fd2, "Im writer 2!\n", 14);
    num_bytes_read = write(fd3, "Im writer 3!\n", 14);
    num_bytes_read = write(fd4, "Im writer 4!\n", 14);
    num_bytes_read = write(fd5, "Im writer 5!\n", 14);
    num_bytes_read = write(fd6, "Im writer 6!\n", 14);
    // should have 6 cache entries
    close(fd);close(fd2);close(fd3);close(fd4);close(fd5);close(fd6);
    fd = open("test.txt", O_RDONLY);
    num_bytes_read = read(fd, buf, 100);
    if(!strcmp(buf, "Im writer 6!\n")){
        unlink("test.txt");
        printf("Passed Test 5!\n");
    }
    else{
        printf("Did not pass lass writer wins:\n%s\n", buf);
    }    
}

/* newfile should not exist*/
int test6(){
    // test 6: Creating files that dont exist on the server yet
    char buf[100];
    int fd = open("newfile.txt", O_RDONLY|O_CREAT);
    int num_bytes_read = write(fd, "Im writer !!\n", 14); 
    close(fd);

    fd = open("newfile.txt", O_RDONLY);
    num_bytes_read = read(fd, buf, 100);
    if(!strcmp(buf, "Im writer !!\n")){
        unlink("newfile.txt");
        printf("Passed Test 6!\n");
    }
    else{
        printf("Did not pass nwe file test:\n%s\n", buf);
    }    
    close(fd);    
}

/* newfile2 should not exist*/
int test7(){
    char buf[100];
    // test 7: two open a file that does not exist; the last writer should win!
    int fd = open("newfile2.txt", O_RDWR|O_CREAT);
    int num_bytes_read = write(fd, "Im writer 1!\n", 14); 

    int fd2 = open("newfile2.txt", O_RDWR|O_CREAT);
    num_bytes_read = write(fd2, "Im writer 2!\n", 14); 
    close(fd);
    close(fd2);
    
    fd = open("newfile2.txt", O_RDONLY);
    num_bytes_read = read(fd, buf, 100);
    if(!strcmp(buf, "Im writer 2!\n")){
        unlink("newfile2.txt");
        printf("Passed Test 7!\n");
    }
    else{
        printf("failed test 7:\n%s\n", buf);
    }
    close(fd);    
}

/* ensure seek_test dne*/
int lseek_test(){
    char buf[100];
    int fd = open("seek_test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd, "SeekTest\n", 9);
    lseek(fd, 0, SEEK_SET);
    int num_bytes_read = read(fd, buf, 1);
    if(!strcmp(buf, "S")){
        printf("Failed Lseek Test1\n");
        return -1;
    }
    lseek(fd, -1, SEEK_SET);
    lseek(fd, 5, SEEK_SET);
    num_bytes_read = read(fd, buf, 1);
    if(!strcmp(buf, "e")){
        printf("Failed Lseek Test2\n");
        return -1;
    }
    lseek(fd, 100, SEEK_SET);
    num_bytes_read = read(fd, buf, 10);
    if(num_bytes_read)
        return -1;
    unlink("seek_test.txt");
    printf("Passed Lseek Test\n");
}
// unlink a file and ensure we cant acess it. Ensure we can still create a new file after unlinking
int unlink_test1(){
    char buf[100];
    unlink("test.txt");
    int fd = open("test.txt", O_RDWR);
    if(!(fd == -1 && errno == ENOENT)){
        printf("Failed Unlink Test 1!\n");
        return -1;
    }
    fd = open("test.txt", O_RDWR | O_CREAT);
    write(fd, "Unlink1 Passed!\n", 16);
    lseek(fd, 0, SEEK_SET);
    int num_bytes_read = read(fd, buf, 16);
    buf[num_bytes_read] = '\0';  // Add null terminator
    unlink("test.txt");
    close(fd);
    printf("%s\n", buf);
}

// open a file and then have someone else unlink it. 
// we should still be able to read from it due to session semantics
// when we close that file, it should be placed back on the server
int unlink_test2(){
    char buf[100];
    int fd0 = open("test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd0, "Test!\n", 6);
    close(fd0);
    int fd = open("test.txt", O_RDWR);
    write(fd, "Passed only if you see me twice!\n", 33);
    unlink("test.txt");
    lseek(fd, 0, SEEK_SET);
    int num_bytes_read = read(fd, buf, 40);
    printf("%s", buf);
    close(fd);

    fd = open("test.txt", O_RDWR);
    num_bytes_read = read(fd, buf, 100);
    unlink("test.txt");
    printf("%s", buf);
    close(fd);
}

/**
 * open, unlink, open. This is going to check the staleness but no file will exist
 * and the code will crash.
 * I think way to handle it is to define a file that has been deleted as stale bc
 * then the error handling code will do it
*/
int unlink_test3(){
    char buf[100];  
    int fd0 = open("test.txt", O_RDWR|O_CREAT);
    int num_bytes_write = write(fd0, "Test.\n", 6);    
    close(fd0);
    int fd = open("test.txt", O_RDWR);
    unlink("test.txt");
    int fd2 = open("test.txt", O_RDONLY);
    close(fd);
    close(fd2);
    unlink("test.txt");
    printf("Passed unlink test 3!\n");
}