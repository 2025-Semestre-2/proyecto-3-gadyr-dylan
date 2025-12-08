package main;

import filesystem.*;
import java.io.File;

public class TestBlockSize {
    public static void main(String[] args) throws Exception {
        FileSystem fs = new FileSystem("miDiscoDuro.fs");
        fs.mount();
        System.out.println("VERIFIED_BLOCK_SIZE=" + fs.getSuperblock().getBlockSize());
    }
}
