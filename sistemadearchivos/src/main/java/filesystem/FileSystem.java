package filesystem;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author dylan
 */
public class FileSystem {

    private String fsFilePath;
    private RandomAccessFile fsFile;
    private Superblock superblock;
    private Bitmap inodeBitmap;
    private Bitmap dataBlockBitmap;

    // Tablas en memoria
    private Map<Integer, User> userTable; // userID -> User
    private Map<String, User> userByName; // username -> User
    private Map<Integer, Group> groupTable; // groupID -> Group
    private Map<String, Group> groupByName; // groupname -> Group

    // Archivos abiertos
    private Map<String, Inode> openFileTable; // path -> inode

    public FileSystem(String fsFilePath) {
        this.fsFilePath = fsFilePath;
        this.userTable = new HashMap<>();
        this.userByName = new HashMap<>();
        this.groupTable = new HashMap<>();
        this.groupByName = new HashMap<>();
        this.openFileTable = new HashMap<>();
    }

    /**
     * Calcula el offset de un bloque en el archivo
     */
    private long getBlockOffset(int blockNumber) {
        return (long) blockNumber * superblock.getBlockSize();
    }

    /**
     * Lee un bloque completo del disco
     */
    private byte[] readBlock(int blockNumber) throws IOException {
        int blockSize = superblock.getBlockSize();
        byte[] block = new byte[blockSize];
        fsFile.seek(getBlockOffset(blockNumber));
        fsFile.readFully(block);

        return block;
    }

    /**
     * Escribe un bloque completo al disco
     */
    private void writeBlock(int blockNumber, byte[] data) throws IOException {
        int blockSize = superblock.getBlockSize();
        if (data.length != blockSize) {
            throw new IllegalArgumentException("El bloque debe tener " + blockSize + " bytes");
        }
        fsFile.seek(getBlockOffset(blockNumber));
        fsFile.write(data);
    }

    /**
     * Lee un inode de la tabla
     */
    public Inode readInode(int inodeNumber) throws IOException {
        if (inodeNumber < 0 || inodeNumber >= superblock.getTotalInodes()) {
            throw new IllegalArgumentException("Número de inode inválido: " + inodeNumber);
        }

        // offset
        long inodeTableOffset = getBlockOffset(superblock.getInodeTableStart());
        long inodeOffset = inodeTableOffset + (inodeNumber * FSConstants.INODE_SIZE);

        byte[] inodeData = new byte[FSConstants.INODE_SIZE];
        fsFile.seek(inodeOffset);
        fsFile.readFully(inodeData);

        return Inode.fromBytes(inodeData);
    }

    /**
     * Escribe un inode en la tabla
     */
    public void writeInode(Inode inode) throws IOException {
        int inodeNumber = inode.getInodeNumber();
        if (inodeNumber < 0 || inodeNumber >= superblock.getTotalInodes()) {
            throw new IllegalArgumentException("Número de inode inválido: " + inodeNumber);
        }

        long inodeTableOffset = getBlockOffset(superblock.getInodeTableStart());
        long inodeOffset = inodeTableOffset + (inodeNumber * FSConstants.INODE_SIZE);

        fsFile.seek(inodeOffset);
        fsFile.write(inode.toBytes());
    }

    /**
     * Asigna un inode libre
     */
    public int allocateInode() throws IOException {
        int inodeNumber = inodeBitmap.findFirstFree();
        if (inodeNumber == -1) {
            throw new IOException("No hay inodes disponibles");
        }

        inodeBitmap.allocate(inodeNumber);
        superblock.setFreeInodes(superblock.getFreeInodes() - 1);
        writeSuperblock();
        writeInodeBitmap();

        return inodeNumber;
    }

    /**
     * Libera un inode
     */
    public void freeInode(int inodeNumber) throws IOException {
        if (inodeNumber < 0 || inodeNumber >= superblock.getTotalInodes()) {
            return;
        }

        inodeBitmap.free(inodeNumber);
        superblock.setFreeInodes(superblock.getFreeInodes() + 1);
        writeSuperblock();
        writeInodeBitmap();
    }

    /**
     * Asigna un bloque de datos libre
     */
    public int allocateDataBlock() throws IOException {
        int blockNumber = dataBlockBitmap.findFirstFree();
        if (blockNumber == -1) {
            throw new IOException("No hay bloques disponibles");
        }

        dataBlockBitmap.allocate(blockNumber);
        superblock.setFreeBlocks(superblock.getFreeBlocks() - 1);
        writeSuperblock();
        writeDataBlockBitmap();

        return superblock.getDataBlocksStart() + blockNumber;
    }

    /**
     * Libera un bloque de datos
     */
    public void freeDataBlock(int absoluteBlockNumber) throws IOException {
        int relativeBlock = absoluteBlockNumber - superblock.getDataBlocksStart();

        if (relativeBlock < 0 || relativeBlock >= dataBlockBitmap.getSize()) {
            return;
        }

        dataBlockBitmap.free(relativeBlock);
        superblock.setFreeBlocks(superblock.getFreeBlocks() + 1);
        writeSuperblock();
        writeDataBlockBitmap();
    }

    /**
     * Libera todos los bloques de datos asociados a un inode
     */
    public void releaseInodeBlocks(Inode inode) throws IOException {
        // 1. Liberar bloques directos
        for (int i = 0; i < FSConstants.DIRECT_POINTERS; i++) {
            int blockNum = inode.getDirectBlocks()[i];
            if (blockNum != -1) {
                freeDataBlock(blockNum);
                inode.setDirectBlock(i, -1);
            }
        }

        // 2. Liberar indirección simple
        if (inode.getSingleIndirect() != -1) {
            freeIndirectBlock(inode.getSingleIndirect(), 0); // 0 indicates leaf pointers
            inode.setSingleIndirect(-1);
        }

        writeInode(inode);
    }

    /**
     * Libera recursivamente bloques indirectos
     */
    private void freeIndirectBlock(int blockNum, int level) throws IOException {
        // Leer bloque de punteros
        byte[] blockData = readBlock(blockNum);
        ByteBuffer buffer = ByteBuffer.wrap(blockData);

        int pointersPerBlock = superblock.getBlockSize() / 4;
        for (int i = 0; i < pointersPerBlock; i++) {
            int ptr = buffer.getInt();
            if (ptr != -1 && ptr != 0) { // 0 might be default empty in some contexts, but -1 is standard
                if (level == 0) {
                    freeDataBlock(ptr);
                } else {
                    freeIndirectBlock(ptr, level - 1);
                }
            }
        }

        // Liberar el bloque de punteros actual
        freeDataBlock(blockNum);
    }

    /**
     * Escribe el superblock al disco
     */
    private void writeSuperblock() throws IOException {
        writeBlock(0, superblock.toBytes());
    }

    /**
     * Escribe el inode bitmap al disco
     */
    private void writeInodeBitmap() throws IOException {
        int blockSize = superblock.getBlockSize();
        byte[] bitmapBytes = inodeBitmap.toBytes();
        int blocksNeeded = (bitmapBytes.length + blockSize - 1)
                / blockSize;

        for (int i = 0; i < blocksNeeded; i++) {
            byte[] blockData = new byte[blockSize];
            int copyLength = Math.min(blockSize,
                    bitmapBytes.length - i * blockSize);
            System.arraycopy(bitmapBytes, i * blockSize,
                    blockData, 0, copyLength);
            writeBlock(superblock.getInodeBitmapStart() + i, blockData);
        }
    }

    /**
     * Escribe el data block bitmap al disco
     */
    private void writeDataBlockBitmap() throws IOException {
        int blockSize = superblock.getBlockSize();
        byte[] bitmapBytes = dataBlockBitmap.toBytes();
        int blocksNeeded = (bitmapBytes.length + blockSize - 1)
                / blockSize;

        for (int i = 0; i < blocksNeeded; i++) {
            byte[] blockData = new byte[blockSize];
            int copyLength = Math.min(blockSize,
                    bitmapBytes.length - i * blockSize);
            System.arraycopy(bitmapBytes, i * blockSize,
                    blockData, 0, copyLength);
            writeBlock(superblock.getDataBitmapStart() + i, blockData);
        }
    }

    /**
     * Lee las entradas de un directorio
     */
    public List<DirectoryEntry> readDirectoryEntries(Inode dirInode) throws IOException {
        if (!dirInode.isDirectory()) {
            throw new IllegalArgumentException("El inode no es un directorio");
        }

        List<DirectoryEntry> entries = new ArrayList<>();

        // SOLO LEE DEL PRIMER BLOQUE DIRECTO
        int blockNumber = dirInode.getDirectBlocks()[0];
        if (blockNumber == -1) {
            return entries; // Directorio vacío
        }

        byte[] blockData = readBlock(blockNumber);
        int blockSize = superblock.getBlockSize();
        int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;

        for (int i = 0; i < entriesPerBlock; i++) {
            int offset = i * FSConstants.DIR_ENTRY_SIZE;
            byte[] entryData = new byte[FSConstants.DIR_ENTRY_SIZE];
            // Verificar si hay suficientes bytes restantes en el bloque
            if (offset + FSConstants.DIR_ENTRY_SIZE > blockSize) {
                break;
            }
            System.arraycopy(blockData, offset, entryData, 0, FSConstants.DIR_ENTRY_SIZE);

            DirectoryEntry entry = DirectoryEntry.fromBytes(entryData);
            entries.add(entry);
        }

        return entries;
    }

    /**
     * Escribe las entradas de un directorio
     */
    public void writeDirectoryEntries(Inode dirInode, List<DirectoryEntry> entries)
            throws IOException {
        if (!dirInode.isDirectory()) {
            throw new IllegalArgumentException("El inode no es un directorio");
        }

        int blockSize = superblock.getBlockSize();
        int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;

        if (entries.size() > entriesPerBlock) {
            throw new IOException("Demasiadas entradas para un solo bloque (máximo " + entriesPerBlock + ")");
        }

        int blockNumber = dirInode.getDirectBlocks()[0];
        if (blockNumber == -1) {
            // Necesitamos asignar un bloque
            blockNumber = allocateDataBlock();
            dirInode.setDirectBlock(0, blockNumber);
            dirInode.setFileSize(blockSize);
            writeInode(dirInode);
        }

        byte[] blockData = new byte[blockSize];

        for (int i = 0; i < entries.size() && i < entriesPerBlock; i++) {
            byte[] entryData = entries.get(i).toBytes();
            System.arraycopy(entryData, 0, blockData,
                    i * FSConstants.DIR_ENTRY_SIZE, FSConstants.DIR_ENTRY_SIZE);
        }

        writeBlock(blockNumber, blockData);
    }

    // Getters
    public Superblock getSuperblock() {
        return superblock;
    }

    public Bitmap getInodeBitmap() {
        return inodeBitmap;
    }

    public Bitmap getDataBlockBitmap() {
        return dataBlockBitmap;
    }

    public Map<Integer, User> getUserTable() {
        return userTable;
    }

    public Map<String, User> getUserByName() {
        return userByName;
    }

    public Map<Integer, Group> getGroupTable() {
        return groupTable;
    }

    public Map<String, Group> getGroupByName() {
        return groupByName;
    }

    // Métodos para tabla de archivos abiertos
    public void addOpenFile(String path, Inode inode) {
        openFileTable.put(path, inode);
    }

    public void removeOpenFile(String path) {
        openFileTable.remove(path);
    }

    public Inode getOpenFile(String path) {
        return openFileTable.get(path);
    }

    public boolean isFileOpen(String path) {
        return openFileTable.containsKey(path);
    }

    /**
     * Formatea y crea el sistema de archivos
     *
     * @param sizeMB             Tamaño del disco
     * @param blockSizeKB        Tamaño del bloque en KB
     * @param allocationStrategy Estrategia de asignación (1=Contigua,
     *                           2=Enlazada, 3=Indexada)
     * @param rootPassword       Contraseña del usuario root
     */
    public void format(int sizeMB, int blockSizeKB, int allocationStrategy, String rootPassword) throws IOException {
        System.out.println("iniciando formateo del sistema de archivos...");
        System.out.println("Tamaño: " + sizeMB + " MB");
        System.out.println("Tamaño de bloque: " + blockSizeKB + " KB");
        System.out.println("Estrategia: " + getStrategyName(allocationStrategy));

        int blockSize = blockSizeKB * 1024;

        // Paso 1: Calcular estructuras
        long totalBytes = (long) sizeMB * 1024 * 1024;
        int totalBlocks = (int) (totalBytes / blockSize);

        // Calcular total de inodes (1 inode por cada 16 KB, o ajustar según necesidad)
        // Mantener política de inodes: 1 inode cada 16KB de espacio es un buen promedio
        int totalInodes = (int) (totalBytes / (16 * 1024));

        // Calcular bloques necesarios para el inode bitmap
        int inodeBitmapBits = totalInodes;
        int inodeBitmapBytes = (inodeBitmapBits + 7) / 8;
        int inodeBitmapBlocks = (inodeBitmapBytes + blockSize - 1) / blockSize;

        // Calcular bloques para la tabla de inodes
        int inodeTableBytes = totalInodes * FSConstants.INODE_SIZE;
        int inodeTableBlocks = (inodeTableBytes + blockSize - 1) / blockSize;

        // Calcular bloques de datos provisionales
        int metadataBlocksWithoutDataBitmap = 1 + inodeBitmapBlocks + inodeTableBlocks;
        int provisionalDataBlocks = totalBlocks - metadataBlocksWithoutDataBitmap;

        // Calcular bloques para data bitmap
        int dataBitmapBits = provisionalDataBlocks;
        int dataBitmapBytes = (dataBitmapBits + 7) / 8;
        int dataBitmapBlocks = (dataBitmapBytes + blockSize - 1) / blockSize;

        // Calcular bloques de datos reales
        int actualDataBlocks = totalBlocks - 1 - inodeBitmapBlocks - dataBitmapBlocks - inodeTableBlocks;

        System.out.println("\nCálculos del sistema de archivos:");
        System.out.println("  Total de bloques: " + totalBlocks);
        System.out.println("  Total de inodes: " + totalInodes);
        System.out.println("  Bloques para inode bitmap: " + inodeBitmapBlocks);
        System.out.println("  Bloques para data bitmap: " + dataBitmapBlocks);
        System.out.println("  Bloques para tabla de inodes: " + inodeTableBlocks);
        System.out.println("  Bloques de datos: " + actualDataBlocks);

        // Paso 2: Crear el archivo
        File fsFileObj = new File(fsFilePath);
        if (fsFileObj.exists()) {
            System.out.println("\nAdvertencia: El archivo ya existe. Será sobreescrito.");
        }

        fsFile = new RandomAccessFile(fsFilePath, "rw");
        fsFile.setLength(totalBytes);

        // Paso 3: Crear y escribir el superblock
        System.out.println("\nCreando Superblock...");
        superblock = new Superblock();
        superblock.setFsName("myFS");
        superblock.setBlockSize(blockSize);
        superblock.setTotalBlocks(totalBlocks);
        superblock.setTotalInodes(totalInodes);
        superblock.setFreeBlocks(actualDataBlocks - 4); // -4 por "/", "/user", "/user/root", "/user/root/home"
        superblock.setFreeInodes(totalInodes - 4); // -4 por "/", "/user", "/user/root", "/user/root/home"
        superblock.setRootInode(FSConstants.ROOT_INODE);
        superblock.setAllocationStrategy(allocationStrategy);

        // Calcular posiciones de inicio
        superblock.setInodeBitmapStart(1);
        superblock.setDataBitmapStart(1 + inodeBitmapBlocks);
        superblock.setInodeTableStart(1 + inodeBitmapBlocks + dataBitmapBlocks);
        superblock.setDataBlocksStart(1 + inodeBitmapBlocks + dataBitmapBlocks + inodeTableBlocks);

        // OJO: writeSuperblock usa 'superblock.blockSize' para el buffer.
        // Como 'superblock' ya está inicializado con el nuevo blockSize, esto funciona.
        writeSuperblock();
        System.out.println(" Superblock escrito en el bloque 0");

        // Paso 4: Inicializar Inode Bitmap
        System.out.println("\nInicializando Inode Bitmap...");
        inodeBitmap = new Bitmap(totalInodes);
        // inodes 0, 1, 2, 3 ocupados (/, /user, /user/root, /user/root/home)
        inodeBitmap.allocate(0);
        inodeBitmap.allocate(1);
        inodeBitmap.allocate(2);
        inodeBitmap.allocate(3);
        writeInodeBitmap();
        System.out.println(" Inode Bitmap escrito");

        // Paso 5: inicializar Data Block Bitmap
        System.out.println("\nInicializando Data Block Bitmap...");
        dataBlockBitmap = new Bitmap(actualDataBlocks);
        // 0, 1, 2, 3 ocupados (/, /user, /user/root, /user/root/home)
        dataBlockBitmap.allocate(0);
        dataBlockBitmap.allocate(1);
        dataBlockBitmap.allocate(2);
        dataBlockBitmap.allocate(3);
        writeDataBlockBitmap();
        System.out.println(" Data Block Bitmap escrito");

        // Paso 6: Crear inode del directorio raíz "/"
        System.out.println("\nCreando directorio raíz '/'...");
        Inode rootInode = new Inode(
                FSConstants.ROOT_INODE,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                FSConstants.ROOT_UID,
                FSConstants.ROOT_GID);

        rootInode.setName("/");
        rootInode.setFileSize(blockSize);
        rootInode.setLinkCount(3); // ".", ".." y "user"
        rootInode.setDirectBlock(0, superblock.getDataBlocksStart());
        writeInode(rootInode);
        System.out.println(" Inode de '/' creado (inode 0)");

        // Paso 7: Crear contenido del directorio raíz
        System.out.println(" Creando entradas de directorio para '/'...");
        List<DirectoryEntry> rootEntries = new ArrayList<>();

        // Entrada "." (apunta a sí mismo)
        rootEntries.add(new DirectoryEntry(0, FSConstants.TYPE_DIRECTORY, "."));

        // Entrada ".." (apunta a sí mismo porque es la raíz)
        rootEntries.add(new DirectoryEntry(0, FSConstants.TYPE_DIRECTORY, ".."));

        // Entrada "user" (apunta al directorio /user)
        rootEntries.add(new DirectoryEntry(1, FSConstants.TYPE_DIRECTORY, "user"));

        // Rellenar con entradas vacías
        int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;
        for (int i = 3; i < entriesPerBlock; i++) {
            rootEntries.add(new DirectoryEntry());
        }

        writeDirectoryEntries(rootInode, rootEntries);
        System.out.println("  Entradas de directorio escritas");

        // Paso 8: Crear inode del directorio "/user"
        System.out.println("\nCreando directorio '/user'...");
        Inode userDirInode = new Inode(
                1,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                FSConstants.ROOT_UID,
                FSConstants.ROOT_GID);
        userDirInode.setName("user");
        userDirInode.setFileSize(blockSize);
        userDirInode.setLinkCount(3); // ".", ".." y "root"
        userDirInode.setDirectBlock(0, superblock.getDataBlocksStart() + 1);
        writeInode(userDirInode);
        System.out.println("  Inode de '/user' creado (inode 1)");

        // Paso 9: Crear contenido del directorio "/user"
        System.out.println("  Creando entradas de directorio para '/user'...");
        List<DirectoryEntry> userDirEntries = new ArrayList<>();

        // Entrada "." (apunta a sí mismo)
        userDirEntries.add(new DirectoryEntry(1, FSConstants.TYPE_DIRECTORY, "."));

        // Entrada ".." (apunta al directorio padre "/")
        userDirEntries.add(new DirectoryEntry(0, FSConstants.TYPE_DIRECTORY, ".."));

        // Entrada "root" (apunta al directorio /user/root)
        userDirEntries.add(new DirectoryEntry(2, FSConstants.TYPE_DIRECTORY, "root"));

        // Rellenar con entradas vacías
        for (int i = 3; i < entriesPerBlock; i++) {
            userDirEntries.add(new DirectoryEntry());
        }

        writeDirectoryEntries(userDirInode, userDirEntries);
        System.out.println("  Entradas de directorio escritas");

        // Paso 10: Crear inode del directorio "/user/root"
        System.out.println("\nCreando directorio '/user/root'...");
        Inode userRootDirInode = new Inode(
                2,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                FSConstants.ROOT_UID,
                FSConstants.ROOT_GID);
        userRootDirInode.setName("root");
        userRootDirInode.setFileSize(blockSize);
        userRootDirInode.setLinkCount(3); // ".", ".." y "home"
        userRootDirInode.setDirectBlock(0, superblock.getDataBlocksStart() + 2);
        writeInode(userRootDirInode);
        System.out.println("  Inode de '/user/root' creado (inode 2)");

        // Paso 11: Crear contenido del directorio "/user/root"
        System.out.println("  Creando entradas de directorio para '/user/root'...");
        List<DirectoryEntry> userRootDirEntries = new ArrayList<>();

        // Entrada "." (apunta a sí mismo)
        userRootDirEntries.add(new DirectoryEntry(2, FSConstants.TYPE_DIRECTORY, "."));

        // Entrada ".." (apunta al directorio padre "/user")
        userRootDirEntries.add(new DirectoryEntry(1, FSConstants.TYPE_DIRECTORY, ".."));

        // Entrada "home" (apunta al directorio /user/root/home)
        userRootDirEntries.add(new DirectoryEntry(3, FSConstants.TYPE_DIRECTORY, "home"));

        // Rellenar con entradas vacías
        for (int i = 3; i < entriesPerBlock; i++) {
            userRootDirEntries.add(new DirectoryEntry());
        }

        writeDirectoryEntries(userRootDirInode, userRootDirEntries);
        System.out.println("  Entradas de directorio escritas");

        // Paso 12: Crear inode del directorio "/user/root/home"
        System.out.println("\nCreando directorio '/user/root/home'...");
        Inode rootHomeInode = new Inode(
                3,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                FSConstants.ROOT_UID,
                FSConstants.ROOT_GID);
        rootHomeInode.setName("home");
        rootHomeInode.setFileSize(blockSize);
        rootHomeInode.setLinkCount(2); // "." y ".."
        rootHomeInode.setDirectBlock(0, superblock.getDataBlocksStart() + 3);
        writeInode(rootHomeInode);
        System.out.println("  Inode de '/user/root/home' creado (inode 3)");

        // Paso 13: Crear contenido del directorio "/user/root/home"
        System.out.println("  Creando entradas de directorio para '/user/root/home'...");
        List<DirectoryEntry> rootHomeEntries = new ArrayList<>();

        // Entrada "." (apunta a sí mismo)
        rootHomeEntries.add(new DirectoryEntry(3, FSConstants.TYPE_DIRECTORY, "."));

        // Entrada ".." (apunta al directorio padre "/user/root")
        rootHomeEntries.add(new DirectoryEntry(2, FSConstants.TYPE_DIRECTORY, ".."));

        // Rellenar con entradas vacías
        for (int i = 2; i < entriesPerBlock; i++) {
            rootHomeEntries.add(new DirectoryEntry());
        }

        writeDirectoryEntries(rootHomeInode, rootHomeEntries);
        System.out.println("  Entradas de directorio escritas");

        // Paso 14: Crear usuario root
        System.out.println("\nCreando usuario root...");
        User rootUser = new User(
                FSConstants.ROOT_UID,
                "root",
                rootPassword,
                "Root Admin",
                "/user/root/home",
                FSConstants.ROOT_GID);
        userTable.put(rootUser.getUserId(), rootUser);
        userByName.put(rootUser.getUsername(), rootUser);
        System.out.println("  Usuario root creado");

        // Paso 15: Crear grupo root
        System.out.println("\nCreando grupo root...");
        Group rootGroup = new Group(FSConstants.ROOT_GID, "root");
        rootGroup.addMember(FSConstants.ROOT_UID);
        groupTable.put(rootGroup.getGroupId(), rootGroup);
        groupByName.put(rootGroup.getGroupName(), rootGroup);
        System.out.println("  Grupo root creado");

        // Crear grupo "users" por defecto (GID = 1)
        System.out.println("\nCreando grupo users...");
        Group usersGroup = new Group(1, "users");
        groupTable.put(usersGroup.getGroupId(), usersGroup);
        groupByName.put(usersGroup.getGroupName(), usersGroup);
        System.out.println("  Grupo users creado");

        // Paso 16: Guardar usuarios y grupos en bloques especiales
        saveUsersAndGroups();

        // Paso 17: Sincronizar y cerrar
        fsFile.getFD().sync();
        System.out.println("\n¡Sistema de archivos formateado exitosamente!");
        System.out.println("Archivo: " + fsFilePath);
        System.out.println("Usuario root creado con directorio home: /user/root/home");
    }

    /**
     * Guarda las tablas de usuarios y grupos en bloques especiales del FS
     * (Bloques reservados despúes de los metadatos)
     */
    private void saveUsersAndGroups() throws IOException {
        System.out.println("\nGuardando tablas de usuarios y grupos...");
        int blockSize = superblock.getBlockSize();

        // Bloque especial para usuarios (después del último bloque de datos usado)
        // OJO: Aquí asumimos asignación contigua simple para estos bloques especiales
        // al momento de formatear.
        int userBlockNumber = superblock.getDataBlocksStart() + 4;

        // Bloque especial para grupos
        int groupBlockNumber = superblock.getDataBlocksStart() + 5;

        // Marcar estos bloques como ocupados
        dataBlockBitmap.allocate(4);
        dataBlockBitmap.allocate(5);
        superblock.setFreeBlocks(superblock.getFreeBlocks() - 2);
        writeSuperblock();
        writeDataBlockBitmap();

        // Guardar usuarios
        byte[] userBlock = new byte[blockSize];
        int offset = 0;

        // Guardar cantidad de usuarios
        ByteBuffer userBuffer = ByteBuffer.wrap(userBlock);
        userBuffer.putInt(userTable.size());
        offset = 4;

        // Guardar cada usuario (512 bytes por usuario)
        for (User user : userTable.values()) {
            byte[] userData = user.toBytes();
            if (offset + userData.length <= blockSize) {
                System.arraycopy(userData, 0, userBlock, offset, userData.length);
                offset += userData.length;
            }
        }

        writeBlock(userBlockNumber, userBlock);
        System.out.println("  Usuarios guardados en bloque " + userBlockNumber);

        // Guardar grupos
        byte[] groupBlock = new byte[blockSize];
        offset = 0;

        ByteBuffer groupBuffer = ByteBuffer.wrap(groupBlock);
        groupBuffer.putInt(groupTable.size());
        offset = 4;

        for (Group group : groupTable.values()) {
            byte[] groupData = group.toBytes();
            if (offset + groupData.length <= blockSize) {
                System.arraycopy(groupData, 0, groupBlock, offset, groupData.length);
                offset += groupData.length;
            }
        }

        writeBlock(groupBlockNumber, groupBlock);
        System.out.println("  Grupos guardados en bloque " + groupBlockNumber);
    }

    /**
     * Monta un sistema de archivos existente
     */
    public void mount() throws IOException {
        System.out.println("Montando sistema de archivos: " + fsFilePath);

        File fsFileObj = new File(fsFilePath);
        if (!fsFileObj.exists()) {
            throw new IOException("El archivo del sistema de archivos no existe: " + fsFilePath);
        }

        fsFile = new RandomAccessFile(fsFilePath, "rw");

        // Leer Superblock. Para esto necesitamos saber el tamaño.
        // El Superblock siempre se lee del offset 0, pero necesitamos saber cuánto
        // leer.
        // Asumimos un tamaño mínimo de lectura para sacar los datos iniciales, o mejor
        // aún,
        // leemos un bloque por defecto (4KB) inicialmente para obtener la estructura,
        // ya que el Superblock siempre cabe en 1KB pero usamos un bloque completo.

        System.out.println("Leyendo Superblock...");

        // Leemos temporalmente con tamaño por defecto para obtener el verdadero
        byte[] tempBlock = new byte[FSConstants.DEFAULT_BLOCK_SIZE];
        fsFile.seek(0);
        fsFile.read(tempBlock); // read parcial si es más pequeño

        superblock = Superblock.fromBytes(tempBlock);

        // Validar magic number
        if (!superblock.isValid()) {
            throw new IOException("Sistema de archivos inválido o corrupto (magic number incorrecto)");
        }

        int blockSize = superblock.getBlockSize();

        System.out.println("  Sistema de archivos: " + superblock.getFsName());
        System.out.println("  Versión: " + superblock.getFsVersion());
        System.out.println("  Tamaño de bloque: " + blockSize);
        System.out.println("  Total de bloques: " + superblock.getTotalBlocks());
        System.out.println("  Bloques libres: " + superblock.getFreeBlocks());
        System.out.println("  Total de inodes: " + superblock.getTotalInodes());
        System.out.println("  Inodes libres: " + superblock.getFreeInodes());

        // Actualizar last mount time
        superblock.setLastMountTime(System.currentTimeMillis());
        writeSuperblock();

        // Leer Inode Bitmap
        System.out.println("\nCargando Inode Bitmap...");
        int inodeBitmapBytes = (superblock.getTotalInodes() + 7) / 8;
        int inodeBitmapBlocks = (inodeBitmapBytes + blockSize - 1)
                / blockSize;

        byte[] inodeBitmapData = new byte[inodeBitmapBytes];
        for (int i = 0; i < inodeBitmapBlocks; i++) {
            byte[] block = readBlock(superblock.getInodeBitmapStart() + i);
            int copyLength = Math.min(blockSize,
                    inodeBitmapBytes - i * blockSize);
            System.arraycopy(block, 0, inodeBitmapData,
                    i * blockSize, copyLength);
        }
        inodeBitmap = Bitmap.fromBytes(inodeBitmapData, superblock.getTotalInodes());
        System.out.println("  Inode Bitmap cargado");

        // Leer Data Block Bitmap
        System.out.println("\nCargando Data Block Bitmap...");
        int dataBlocks = superblock.getTotalBlocks() - superblock.getDataBlocksStart();
        int dataBitmapBytes = (dataBlocks + 7) / 8;
        int dataBitmapBlocks = (dataBitmapBytes + blockSize - 1)
                / blockSize;

        byte[] dataBitmapData = new byte[dataBitmapBytes];
        for (int i = 0; i < dataBitmapBlocks; i++) {
            byte[] block = readBlock(superblock.getDataBitmapStart() + i);
            int copyLength = Math.min(blockSize,
                    dataBitmapBytes - i * blockSize);
            System.arraycopy(block, 0, dataBitmapData,
                    i * blockSize, copyLength);
        }
        dataBlockBitmap = Bitmap.fromBytes(dataBitmapData, dataBlocks);
        System.out.println("  Data Block Bitmap cargado");

        // Cargar usuarios y grupos
        loadUsersAndGroups();

        System.out.println("\n¡Sistema de archivos montado exitosamente!");
    }

    /**
     * Carga las tablas de usuarios y grupos desde el disco
     */
    private void loadUsersAndGroups() throws IOException {
        System.out.println("\nCargando usuarios y grupos...");

        int userBlockNumber = superblock.getDataBlocksStart() + 4;
        int groupBlockNumber = superblock.getDataBlocksStart() + 5;

        // Cargar usuarios
        byte[] userBlock = readBlock(userBlockNumber);
        ByteBuffer userBuffer = ByteBuffer.wrap(userBlock);

        int userCount = userBuffer.getInt();
        System.out.println("  Cargando " + userCount + " usuarios...");

        userTable.clear();
        userByName.clear();

        for (int i = 0; i < userCount; i++) {
            byte[] userData = new byte[512];
            userBuffer.get(userData);
            User user = User.fromBytes(userData);

            if (user.getUserId() != -1) { // Usuario válido
                userTable.put(user.getUserId(), user);
                userByName.put(user.getUsername(), user);
                System.out.println("    - " + user.getUsername() + " (" + user.getFullName() + ")");
            }
        }

        // Cargar grupos
        byte[] groupBlock = readBlock(groupBlockNumber);
        ByteBuffer groupBuffer = ByteBuffer.wrap(groupBlock);

        int groupCount = groupBuffer.getInt();
        System.out.println("  Cargando " + groupCount + " grupos...");

        groupTable.clear();
        groupByName.clear();

        for (int i = 0; i < groupCount; i++) {
            byte[] groupData = new byte[512];
            groupBuffer.get(groupData);
            Group group = Group.fromBytes(groupData);

            if (group.getGroupId() != -1) { // Grupo válido
                groupTable.put(group.getGroupId(), group);
                groupByName.put(group.getGroupName(), group);
                System.out.println("    - " + group.getGroupName());
            }
        }
    }

    /**
     * Desmonta el sistema de archivos
     */
    public void unmount() throws IOException {
        if (fsFile != null) {
            System.out.println("Desmontando sistema de archivos...");

            // Guardar usuarios y grupos
            saveUsersAndGroups();

            // Sincronizar cambios
            fsFile.getFD().sync();

            // Cerrar archivo
            fsFile.close();
            fsFile = null;

            System.out.println("Sistema de archivos desmontado correctamente");
        }
    }

    /**
     * Lee todo el contenido de un archivo
     */
    public byte[] readFile(Inode inode) throws IOException {
        long fileSize = inode.getFileSize();
        if (fileSize == 0)
            return new byte[0];

        // Bloques necesarios
        long blockSize = superblock.getBlockSize(); // 1KB = 1024 bytes (ejemplo)
        int numBlocks = (int) ((fileSize + blockSize - 1) / blockSize);

        ByteBuffer fileContent = ByteBuffer.allocate((int) fileSize);

        // Helper para leer N bloques lógicos
        for (int i = 0; i < numBlocks; i++) {
            int blockNum = getBlockNumber(inode, i); // Obtener bloque físico
            if (blockNum == -1)
                break; // Error o fin

            byte[] blockData = readDataBlock(blockNum);

            // Si es el último bloque, solo tomamos lo necesario
            int bytesToRead = (int) blockSize;
            if (i == numBlocks - 1) {
                bytesToRead = (int) (fileSize % blockSize);
                if (bytesToRead == 0 && fileSize > 0)
                    bytesToRead = (int) blockSize;
            }

            fileContent.put(blockData, 0, bytesToRead);
        }

        return fileContent.array();
    }

    /**
     * Escribe contenido a un archivo (sobrescribe)
     */
    public void writeFile(Inode inode, byte[] data) throws IOException {
        long newSize = data.length;
        long blockSize = superblock.getBlockSize(); // 1KB = 1024
        int neededBlocks = (int) ((newSize + blockSize - 1) / blockSize);
        if (newSize == 0)
            neededBlocks = 0;

        // Liberar bloques anteriores (simplificación: liberar todo y reasignar)
        // En un sistema real optimizaríamos, pero para este proyecto es más seguro
        // resetear
        releaseInodeBlocks(inode);

        // Asignar nuevos bloques y escribir
        for (int i = 0; i < neededBlocks; i++) {
            int blockNum = allocateDataBlock();
            // Asignar bloque físico al bloque lógico i del inode
            setBlockNumber(inode, i, blockNum);

            // Preparar datos del bloque
            byte[] blockData = new byte[(int) blockSize];
            int start = i * (int) blockSize;
            int length = Math.min((int) blockSize, data.length - start);
            System.arraycopy(data, start, blockData, 0, length);

            writeDataBlock(blockNum, blockData);
        }

        inode.setFileSize(newSize);
        inode.setModificationTime(System.currentTimeMillis());
        writeInode(inode);
    }

    /**
     * Obtiene el número de bloque físico dado un índice lógico
     */
    private int getBlockNumber(Inode inode, int logicalBlockIndex) throws IOException {
        // Directos
        if (logicalBlockIndex < FSConstants.DIRECT_POINTERS) {
            return inode.getDirectBlocks()[logicalBlockIndex];
        }

        // Indirecto Simple (apunta a 1 bloque que contiene punteros)
        // Capacidad: BlockSize / 4 bytes (int)
        int blockSize = superblock.getBlockSize();
        int ptrsPerBlock = blockSize / 4;

        int indirectIndex = logicalBlockIndex - FSConstants.DIRECT_POINTERS;

        if (indirectIndex < ptrsPerBlock) {
            int indirectBlock = inode.getSingleIndirect();
            if (indirectBlock == -1)
                return -1;

            byte[] data = readDataBlock(indirectBlock);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            return buffer.getInt(indirectIndex * 4);
        }

        // Indirecto Doble y Triple aquí si fuera necesario
        return -1; // No implementado fully o fuera de rango
    }

    /**
     * Asigna un bloque físico a un índice lógico
     */
    private void setBlockNumber(Inode inode, int logicalBlockIndex, int physicalBlock) throws IOException {
        // Directos
        if (logicalBlockIndex < FSConstants.DIRECT_POINTERS) {
            inode.setDirectBlock(logicalBlockIndex, physicalBlock);
            return;
        }

        int blockSize = superblock.getBlockSize();
        int ptrsPerBlock = blockSize / 4;
        int indirectIndex = logicalBlockIndex - FSConstants.DIRECT_POINTERS;

        if (indirectIndex < ptrsPerBlock) {
            int indirectBlock = inode.getSingleIndirect();
            if (indirectBlock == -1) {
                indirectBlock = allocateDataBlock();
                inode.setSingleIndirect(indirectBlock);
                // Inicializar con -1
                byte[] initData = new byte[blockSize];
                ByteBuffer buf = ByteBuffer.wrap(initData);
                for (int j = 0; j < ptrsPerBlock; j++)
                    buf.putInt(-1);
                writeDataBlock(indirectBlock, initData);
            }

            // Leer bloque indirecto
            byte[] data = readDataBlock(indirectBlock);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            // Actualizar puntero
            buffer.putInt(indirectIndex * 4, physicalBlock);

            // Escribir cambios
            writeDataBlock(indirectBlock, buffer.array());
            return;
        }
    }

    /**
     * Retorna el nombre de la estrategia de asignación
     */
    private String getStrategyName(int strategy) {
        switch (strategy) {
            case FSConstants.ALLOC_CONTIGUOUS:
                return "Asignación Contigua";
            case FSConstants.ALLOC_LINKED:
                return "Asignación Enlazada";
            case FSConstants.ALLOC_INDEXED:
                return "Asignación Indexada";
            default:
                return "Desconocida";
        }
    }

    /**
     * Verifica si el sistema de archivos está montado
     */
    public boolean isMounted() {
        return fsFile != null;
    }

    /**
     * Lee un bloque de datos físico
     */
    private byte[] readDataBlock(int blockNum) throws IOException {
        int blockSize = superblock.getBlockSize();
        byte[] buffer = new byte[blockSize]; // Usamos el tamaño de bloque real
        long offset = (long) blockNum * blockSize;

        fsFile.seek(offset);
        fsFile.read(buffer);

        return buffer;
    }

    /**
     * Escribe un bloque de datos físico
     */
    private void writeDataBlock(int blockNum, byte[] data) throws IOException {
        int blockSize = superblock.getBlockSize();
        long offset = (long) blockNum * blockSize;

        fsFile.seek(offset);
        // Si data es menor que blockSize, rellenar ceros? No necesariamente,
        // fsFile.write escribe lo que hay.
        // Pero para consistencia de bloques, mejor escribir blockSize.
        if (data.length != blockSize) {
            byte[] buffer = new byte[blockSize];
            System.arraycopy(data, 0, buffer, 0, Math.min(data.length, blockSize));
            fsFile.write(buffer);
        } else {
            fsFile.write(data);
        }
    }

    /**
     * Obtiene la lista de bloques físicos asignados a un inodo
     */
    public List<Integer> getAllocatedBlocks(Inode inode) throws IOException {
        List<Integer> blocks = new ArrayList<>();

        // Bloques directos
        for (int block : inode.getDirectBlocks()) {
            if (block != -1) {
                blocks.add(block);
            }
        }

        // Bloque indirecto simple
        int indirectBlock = inode.getSingleIndirect();
        if (indirectBlock != -1) {
            // Leemos el bloque indirecto para sacar los punteros
            byte[] data = readDataBlock(indirectBlock);
            ByteBuffer buffer = ByteBuffer.wrap(data);
            int ptrsPerBlock = superblock.getBlockSize() / 4;

            for (int i = 0; i < ptrsPerBlock; i++) {
                int ptr = buffer.getInt();
                if (ptr != -1) {
                    blocks.add(ptr);
                }
            }
        }

        return blocks;
    }

    /**
     * Obtiene los punteros almacenados en el bloque indirecto simple
     */
    public List<Integer> getIndirectBlockPointers(Inode inode) throws IOException {
        List<Integer> pointers = new ArrayList<>();

        int indirectBlock = inode.getSingleIndirect();
        if (indirectBlock == -1) {
            return pointers; // No hay bloque indirecto
        }

        // Leer el bloque indirecto
        byte[] data = readDataBlock(indirectBlock);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int ptrsPerBlock = superblock.getBlockSize() / 4;

        for (int i = 0; i < ptrsPerBlock; i++) {
            int ptr = buffer.getInt();
            if (ptr != -1) {
                pointers.add(ptr);
            }
        }

        return pointers;
    }
}
