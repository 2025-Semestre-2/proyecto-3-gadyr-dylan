package main;

import filesystem.*;
import java.io.File;

public class SimpleTest {
    public static void main(String[] args) throws Exception {
        FileSystem fs = new FileSystem("miDiscoDuro.fs");
        fs.mount();

        // Root user
        User rootUser = fs.getUserByName().get("root");
        System.out.println("ROOT_HOME=" + rootUser.getHomeDirectory());

        // User1
        if (fs.getUserByName().containsKey("user1")) {
            User user1 = fs.getUserByName().get("user1");
            System.out.println("USER1_HOME=" + user1.getHomeDirectory());
        }

        fs.unmount();
    }
}
