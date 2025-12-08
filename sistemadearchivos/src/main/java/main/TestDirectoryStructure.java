package main;

import filesystem.*;
import commands.*;
import java.io.File;

public class TestDirectoryStructure {
    public static void main(String[] args) throws Exception {
        FileSystem fs = new FileSystem("miDiscoDuro.fs");
        fs.mount();

        System.out.println("=== VERIFICANDO ESTRUCTURA DE DIRECTORIOS ===\n");

        // Verificar directorio raíz
        Inode rootInode = fs.readInode(0);
        System.out.println("Directorio raíz (/):");
        for (DirectoryEntry entry : fs.readDirectoryEntries(rootInode)) {
            if (!entry.isFree()) {
                System.out.println("  - " + entry.getName() + " (inode " + entry.getInodeNumber() + ")");
            }
        }

        // Verificar /user
        Inode userInode = fs.readInode(1);
        System.out.println("\nDirectorio /user:");
        for (DirectoryEntry entry : fs.readDirectoryEntries(userInode)) {
            if (!entry.isFree()) {
                System.out.println("  - " + entry.getName() + " (inode " + entry.getInodeNumber() + ")");
            }
        }

        // Verificar /user/root
        Inode userRootInode = fs.readInode(2);
        System.out.println("\nDirectorio /user/root:");
        for (DirectoryEntry entry : fs.readDirectoryEntries(userRootInode)) {
            if (!entry.isFree()) {
                System.out.println("  - " + entry.getName() + " (inode " + entry.getInodeNumber() + ")");
            }
        }

        // Verificar /user/root/home
        Inode rootHomeInode = fs.readInode(3);
        System.out.println("\nDirectorio /user/root/home:");
        for (DirectoryEntry entry : fs.readDirectoryEntries(rootHomeInode)) {
            if (!entry.isFree()) {
                System.out.println("  - " + entry.getName() + " (inode " + entry.getInodeNumber() + ")");
            }
        }

        // Verificar usuarios
        System.out.println("\n=== USUARIOS ===");
        for (User user : fs.getUserTable().values()) {
            System.out.println("Usuario: " + user.getUsername());
            System.out.println("  Home: " + user.getHomeDirectory());
        }

        fs.unmount();
    }
}
