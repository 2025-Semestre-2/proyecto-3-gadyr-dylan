package commands;

import filesystem.*;
import filesystem.User;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 *
 * @author dylan
 */
public class FileSystemManager {

    private FileSystem fs;
    private String fsFilePath;
    private User currentUser;
    private String currentDirectory;
    private boolean running;

    public FileSystemManager(String fsFilePath) {
        this.fsFilePath = fsFilePath;
        this.currentDirectory = "/";
        this.running = true;
    }

    /**
     * Formatea el sistema de archivos
     */
    /**
     * Formatea el sistema de archivos
     */
    public void format(int sizeMB) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n=== FORMATEO DEL SISTEMA DE ARCHIVOS ===\n");

        int strategy = FSConstants.ALLOC_INDEXED;
        System.out.println("El sistema de archivos usa la estrategia de asignación indexada.");

        // Solicitar tamaño del bloque
        System.out.print("Ingrese el tamaño del bloque en KB (ej. 4, 8, 16): ");
        int blockSizeKB = 4;
        try {
            String input = scanner.nextLine();
            blockSizeKB = Integer.parseInt(input);
            if (blockSizeKB <= 0) {
                System.out.println("Tamaño inválido, usando por defecto 4KB");
                blockSizeKB = 4;
            }
        } catch (NumberFormatException e) {
            System.out.println("Entrada inválida, usando por defecto 4KB");
            blockSizeKB = 4;
        }

        // Solicitar contraseña del usuario root
        System.out.print("\nEstablezca la contraseña para el usuario root: ");
        String password = scanner.nextLine();

        System.out.print("Confirme la contraseña: ");
        String confirmPassword = scanner.nextLine();

        if (!password.equals(confirmPassword)) {
            throw new IOException("Las contraseñas no coinciden");
        }

        if (password.trim().isEmpty()) {
            throw new IOException("La contraseña no puede estar vacía");
        }

        // Crear y formatear el sistema de archivos
        fs = new FileSystem(fsFilePath);
        fs.format(sizeMB, blockSizeKB, strategy, password);

        // Establecer usuario actual como root
        currentUser = fs.getUserByName().get("root");
        currentDirectory = "/user/root/home";

        System.out.println("\n¡Sistema de archivos formateado correctamente!");
        System.out.println("Usuario actual: root");
        System.out.println("Directorio actual: " + currentDirectory);
    }

    /**
     * Monta un sistema de archivos existente
     */
    public void mount() throws IOException {
        fs = new FileSystem(fsFilePath);
        fs.mount();

        // Por defecto, no hay usuario autenticado
        currentUser = null;
        currentDirectory = "/";
    }

    /**
     * Desmonta el sistema de archivos
     */
    public void unmount() throws IOException {
        if (fs != null) {
            fs.unmount();
            fs = null;
            currentUser = null;
            currentDirectory = "/";
        }
    }

    /**
     * Crea un nuevo usuario
     */
    public void addUser(String username) throws IOException {
        if (!isRoot()) {
            throw new IOException("Permiso denegado: solo root puede crear usuarios");
        }

        // Verificar que el usuario no exista
        if (fs.getUserByName().containsKey(username)) {
            throw new IOException("El usuario '" + username + "' ya existe");
        }

        Scanner scanner = new Scanner(System.in);

        // Solicitar nombre completo
        System.out.print("Nombre completo: ");
        String fullName = scanner.nextLine();

        // Solicitar contraseña
        System.out.print("Contraseña: ");
        String password = scanner.nextLine();

        System.out.print("Confirme contraseña: ");
        String confirmPassword = scanner.nextLine();

        if (!password.equals(confirmPassword)) {
            throw new IOException("Las contraseñas no coinciden");
        }

        if (password.trim().isEmpty()) {
            throw new IOException("La contraseña no puede estar vacía");
        }

        // Asignar nuevo ID de usuario
        int newUserId = fs.getUserTable().size();

        // Crear directorio home
        String homeDir = "/user/" + username + "/home";

        // Crear usuario
        User newUser = new User(newUserId, username, password, fullName, homeDir, 1);
        fs.getUserTable().put(newUserId, newUser);
        fs.getUserByName().put(username, newUser);

        // Crear directorio /user si no existe
        createUserStructure();

        // Crear directorio del usuario
        createUserHomeDirectory(username, newUserId);

        // Guardar cambios
        fs.unmount();
        fs.mount();

        System.out.println("Usuario '" + username + "' creado exitosamente");
        System.out.println("Directorio home: " + homeDir);
    }

    /**
     * Crea la estructura /user si no existe
     */
    private void createUserStructure() throws IOException {
        // Buscar si existe el directorio /user en la raíz
        Inode rootInode = fs.readInode(0);
        List<DirectoryEntry> rootEntries = fs.readDirectoryEntries(rootInode);

        boolean userExists = false;
        for (DirectoryEntry entry : rootEntries) {
            if (!entry.isFree() && entry.getName().equals("user")) {
                userExists = true;
                break;
            }
        }

        if (!userExists) {
            // Crear el directorio /user
            int userInodeNum = fs.allocateInode();
            int blockSize = fs.getSuperblock().getBlockSize();

            Inode userInode = new Inode(
                    userInodeNum,
                    FSConstants.TYPE_DIRECTORY,
                    FSConstants.DEFAULT_DIR_PERMS,
                    FSConstants.ROOT_UID,
                    FSConstants.ROOT_GID);
            userInode.setName("user");
            userInode.setFileSize(blockSize);
            userInode.setLinkCount(2);

            // Asignar bloque de datos
            int dataBlock = fs.allocateDataBlock();
            userInode.setDirectBlock(0, dataBlock);
            fs.writeInode(userInode);

            // Crear entradas del directorio /user
            List<DirectoryEntry> userEntries = new ArrayList<>();
            userEntries.add(new DirectoryEntry(userInodeNum, FSConstants.TYPE_DIRECTORY, "."));
            userEntries.add(new DirectoryEntry(0, FSConstants.TYPE_DIRECTORY, ".."));

            int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;
            for (int i = 2; i < entriesPerBlock; i++) {
                userEntries.add(new DirectoryEntry());
            }

            fs.writeDirectoryEntries(userInode, userEntries);

            // Agregar entrada en el directorio raíz
            boolean added = false;
            for (int i = 0; i < rootEntries.size(); i++) {
                if (rootEntries.get(i).isFree()) {
                    rootEntries.set(i, new DirectoryEntry(userInodeNum,
                            FSConstants.TYPE_DIRECTORY, "user"));
                    added = true;
                    break;
                }
            }

            if (!added) {
                throw new IOException("No hay espacio en el directorio raíz");
            }

            fs.writeDirectoryEntries(rootInode, rootEntries);
            rootInode.setLinkCount(rootInode.getLinkCount() + 1);
            fs.writeInode(rootInode);

            System.out.println("Directorio /user creado");
        }
    }

    /**
     * Crea el directorio del usuario: /user/{username}/home
     */
    private void createUserHomeDirectory(String username, int userId) throws IOException {
        // Leer el directorio /user
        Inode rootInode = fs.readInode(0);
        List<DirectoryEntry> rootEntries = fs.readDirectoryEntries(rootInode);

        int userDirInodeNum = -1;
        for (DirectoryEntry entry : rootEntries) {
            if (!entry.isFree() && entry.getName().equals("user")) {
                userDirInodeNum = entry.getInodeNumber();
                break;
            }
        }

        if (userDirInodeNum == -1) {
            throw new IOException("Directorio /user no encontrado");
        }

        Inode userDirInode = fs.readInode(userDirInodeNum);
        List<DirectoryEntry> userDirEntries = fs.readDirectoryEntries(userDirInode);

        // Paso 1: Crear el directorio /user/{username}
        int userNameDirInodeNum = fs.allocateInode();
        int blockSize = fs.getSuperblock().getBlockSize();

        Inode userNameDirInode = new Inode(
                userNameDirInodeNum,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                userId,
                1 // grupo users por defecto
        );
        userNameDirInode.setName(username);
        userNameDirInode.setFileSize(blockSize);
        userNameDirInode.setLinkCount(3); // ".", ".." y "home"

        // Asignar bloque de datos para /user/{username}
        int userNameDataBlock = fs.allocateDataBlock();
        userNameDirInode.setDirectBlock(0, userNameDataBlock);
        fs.writeInode(userNameDirInode);

        // Crear entradas del directorio /user/{username}
        List<DirectoryEntry> userNameDirEntries = new ArrayList<>();
        userNameDirEntries.add(new DirectoryEntry(userNameDirInodeNum,
                FSConstants.TYPE_DIRECTORY, "."));
        userNameDirEntries.add(new DirectoryEntry(userDirInodeNum,
                FSConstants.TYPE_DIRECTORY, ".."));

        int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;

        // Reservar espacio para "home" que crearemos después
        userNameDirEntries.add(new DirectoryEntry()); // Placeholder para "home"

        for (int i = 3; i < entriesPerBlock; i++) {
            userNameDirEntries.add(new DirectoryEntry());
        }

        // Paso 2: Crear el directorio /user/{username}/home
        int homeInodeNum = fs.allocateInode();

        Inode homeInode = new Inode(
                homeInodeNum,
                FSConstants.TYPE_DIRECTORY,
                FSConstants.DEFAULT_DIR_PERMS,
                userId,
                1 // grupo users por defecto
        );
        homeInode.setName("home");
        homeInode.setFileSize(blockSize);
        homeInode.setLinkCount(2); // "." y ".."

        // Asignar bloque de datos para /user/{username}/home
        int homeDataBlock = fs.allocateDataBlock();
        homeInode.setDirectBlock(0, homeDataBlock);
        fs.writeInode(homeInode);

        // Crear entradas del directorio /user/{username}/home
        List<DirectoryEntry> homeEntries = new ArrayList<>();
        homeEntries.add(new DirectoryEntry(homeInodeNum,
                FSConstants.TYPE_DIRECTORY, "."));
        homeEntries.add(new DirectoryEntry(userNameDirInodeNum,
                FSConstants.TYPE_DIRECTORY, ".."));

        for (int i = 2; i < entriesPerBlock; i++) {
            homeEntries.add(new DirectoryEntry());
        }

        fs.writeDirectoryEntries(homeInode, homeEntries);

        // Paso 3: Agregar entrada "home" en /user/{username}
        userNameDirEntries.set(2, new DirectoryEntry(homeInodeNum,
                FSConstants.TYPE_DIRECTORY, "home"));
        fs.writeDirectoryEntries(userNameDirInode, userNameDirEntries);

        // Paso 4: Agregar entrada del usuario en /user
        boolean added = false;
        for (int i = 0; i < userDirEntries.size(); i++) {
            if (userDirEntries.get(i).isFree()) {
                userDirEntries.set(i, new DirectoryEntry(userNameDirInodeNum,
                        FSConstants.TYPE_DIRECTORY, username));
                added = true;
                break;
            }
        }

        if (!added) {
            throw new IOException("No hay espacio en el directorio /user");
        }

        fs.writeDirectoryEntries(userDirInode, userDirEntries);
        userDirInode.setLinkCount(userDirInode.getLinkCount() + 1);
        fs.writeInode(userDirInode);
    }

    /**
     * Crea un nuevo grupo
     */
    public void addGroup(String groupName) throws IOException {
        if (!isRoot()) {
            throw new IOException("Permiso denegado: solo root puede crear grupos");
        }

        if (fs.getGroupByName().containsKey(groupName)) {
            throw new IOException("El grupo '" + groupName + "' ya existe");
        }

        int newGroupId = fs.getGroupTable().size();

        Group newGroup = new Group(newGroupId, groupName);
        fs.getGroupTable().put(newGroupId, newGroup);
        fs.getGroupByName().put(groupName, newGroup);

        // Guardar cambios
        fs.unmount();
        fs.mount();

        System.out.println("Grupo '" + groupName + "' creado exitosamente");
    }

    /**
     * Cambia la contraseña de un usuario
     */
    public void changePassword(String username) throws IOException {
        // Solo root puede cambiar contraseña de otros, o el mismo usuario su propia
        // contraseña
        if (!isRoot() && !currentUser.getUsername().equals(username)) {
            throw new IOException("Permiso denegado");
        }

        User user = fs.getUserByName().get(username);
        if (user == null) {
            throw new IOException("Usuario '" + username + "' no encontrado");
        }

        Scanner scanner = new Scanner(System.in);

        System.out.print("Nueva contraseña: ");
        String password = scanner.nextLine();

        System.out.print("Confirme contraseña: ");
        String confirmPassword = scanner.nextLine();

        if (!password.equals(confirmPassword)) {
            throw new IOException("Las contraseñas no coinciden");
        }

        if (password.trim().isEmpty()) {
            throw new IOException("La contraseña no puede estar vacía");
        }

        user.setPassword(password);

        // Guardar cambios
        fs.unmount();
        fs.mount();

        System.out.println("Contraseña cambiada exitosamente");
    }

    /**
     * Cambia de usuario (su - switch user)
     */
    public void switchUser(String username) throws IOException {
        User user = fs.getUserByName().get(username);
        if (user == null) {
            throw new IOException("Usuario '" + username + "' no encontrado");
        }

        Scanner scanner = new Scanner(System.in);
        System.out.print("Contraseña: ");
        String password = scanner.nextLine();

        if (!user.checkPassword(password)) {
            throw new IOException("Contraseña incorrecta");
        }

        currentUser = user;
        currentDirectory = user.getHomeDirectory();

        System.out.println("Sesión iniciada como: " + username);
        System.out.println("Directorio actual: " + currentDirectory);
    }

    /**
     * Muestra información del usuario actual
     */
    public void whoami() {
        if (currentUser == null) {
            System.out.println("No hay usuario autenticado");
            return;
        }

        System.out.println("Usuario: " + currentUser.getUsername());
        System.out.println("Nombre completo: " + currentUser.getFullName());
        System.out.println("UID: " + currentUser.getUserId());
        System.out.println("GID: " + currentUser.getGroupId());
        System.out.println("Directorio home: " + currentUser.getHomeDirectory());
    }

    /**
     * Muestra el directorio actual
     */
    public void pwd() {
        System.out.println(currentDirectory);
    }

    /**
     * Crea uno o más directorios en el directorio actual
     */
    public void mkdir(String[] dirNames) throws IOException {
        requireAuth();

        if (dirNames == null || dirNames.length == 0) {
            throw new IOException("Debe especificar al menos un nombre de directorio");
        }

        // Validar que estamos en un directorio válido
        Inode currentDirInode = resolveCurrentDirectory();

        List<DirectoryEntry> entries = fs.readDirectoryEntries(currentDirInode);

        for (String dirName : dirNames) {
            try {
                // Validar nombre del directorio
                validateFileName(dirName);

                // Verificar que no exista ya
                if (directoryEntryExists(entries, dirName)) {
                    System.err.println("mkdir: no se puede crear el directorio '" + dirName
                            + "': El archivo ya existe");
                    continue;
                }

                // Buscar entrada libre en el directorio actual
                int freeEntryIndex = findFreeDirectoryEntry(entries);
                if (freeEntryIndex == -1) {
                    System.err.println("mkdir: no se puede crear el directorio '" + dirName
                            + "': No hay espacio en el directorio padre");
                    continue;
                }

                // Asignar nuevo inode
                int newInodeNum = fs.allocateInode();
                int blockSize = fs.getSuperblock().getBlockSize();

                // Crear inode del nuevo directorio
                Inode newDirInode = new Inode(
                        newInodeNum,
                        FSConstants.TYPE_DIRECTORY,
                        FSConstants.DEFAULT_DIR_PERMS,
                        currentUser.getUserId(),
                        currentUser.getGroupId());
                newDirInode.setName(dirName);
                newDirInode.setFileSize(blockSize);
                newDirInode.setLinkCount(2); // "." y ".."

                // Asignar bloque de datos para el directorio
                int dataBlock = fs.allocateDataBlock();
                newDirInode.setDirectBlock(0, dataBlock);
                fs.writeInode(newDirInode);

                // Crear entradas del nuevo directorio
                List<DirectoryEntry> newDirEntries = new ArrayList<>();

                // Entrada "." (apunta a sí mismo)
                newDirEntries.add(new DirectoryEntry(newInodeNum,
                        FSConstants.TYPE_DIRECTORY, "."));

                // Entrada ".." (apunta al directorio padre)
                newDirEntries.add(new DirectoryEntry(currentDirInode.getInodeNumber(),
                        FSConstants.TYPE_DIRECTORY, ".."));

                // Rellenar con entradas vacías
                int entriesPerBlock = blockSize / FSConstants.DIR_ENTRY_SIZE;
                for (int i = 2; i < entriesPerBlock; i++) {
                    newDirEntries.add(new DirectoryEntry());
                }

                fs.writeDirectoryEntries(newDirInode, newDirEntries);

                // Agregar entrada en el directorio padre
                entries.set(freeEntryIndex, new DirectoryEntry(newInodeNum,
                        FSConstants.TYPE_DIRECTORY, dirName));
                fs.writeDirectoryEntries(currentDirInode, entries);

                // Incrementar link count del directorio padre (por la entrada ".." del hijo)
                currentDirInode.setLinkCount(currentDirInode.getLinkCount() + 1);
                fs.writeInode(currentDirInode);

                System.out.println("Directorio '" + dirName + "' creado exitosamente");

            } catch (IOException e) {
                System.err.println("mkdir: error al crear '" + dirName + "': " + e.getMessage());
            }
        }

        // Guardar cambios
        fs.unmount();
        fs.mount();
    }

    /**
     * Crea un archivo vacío en el directorio actual
     */
    public void createFile(String filename) throws IOException {
        requireAuth();

        // Validar nombre
        validateFileName(filename);

        // Obtener directorio actual
        Inode currentDirInode = resolveCurrentDirectory();
        List<DirectoryEntry> entries = fs.readDirectoryEntries(currentDirInode);

        // Verificar existencia
        if (directoryEntryExists(entries, filename)) {
            throw new IOException("El archivo '" + filename + "' ya existe");
        }

        // Buscar espacio libre en el directorio
        int freeEntryIndex = findFreeDirectoryEntry(entries);
        if (freeEntryIndex == -1) {
            throw new IOException("No hay espacio en el directorio actual para crear el archivo");
        }

        // Asignar nuevo inode
        int newInodeNum = fs.allocateInode();

        Inode newFileInode = new Inode(
                newInodeNum,
                FSConstants.TYPE_FILE,
                FSConstants.DEFAULT_FILE_PERMS,
                currentUser.getUserId(),
                currentUser.getGroupId());
        newFileInode.setName(filename);
        newFileInode.setFileSize(0);
        newFileInode.setLinkCount(1);

        // Escribir nuevo inode
        // Escribir nuevo inode
        fs.writeInode(newFileInode);

        // Agregar entrada en el directorio
        entries.set(freeEntryIndex, new DirectoryEntry(newInodeNum,
                FSConstants.TYPE_FILE, filename));
        fs.writeDirectoryEntries(currentDirInode, entries);

        // Guardar cambios
        fs.unmount();
        fs.mount();

        System.out.println("Archivo '" + filename + "' creado exitosamente");
    }

    /**
     * Elimina archivos o directorios
     */
    public void rm(String[] paths, boolean recursive) throws IOException {
        requireAuth();

        for (String path : paths) {
            try {
                // Resolver rutas
                Inode parentInode;
                String name;

                if (path.contains("/")) {
                    String parentPath = path.substring(0, path.lastIndexOf("/"));
                    if (parentPath.isEmpty())
                        parentPath = "/"; // Root case
                    name = path.substring(path.lastIndexOf("/") + 1);

                    int parentInodeNum = resolvePath(parentPath);
                    if (parentInodeNum == -1) {
                        System.err.println("rm: no se puede borrar '" + path + "': Directorio padre no encontrado");
                        continue;
                    }
                    parentInode = fs.readInode(parentInodeNum);
                } else {
                    parentInode = resolveCurrentDirectory();
                    name = path;
                }

                List<DirectoryEntry> entries = fs.readDirectoryEntries(parentInode);
                DirectoryEntry targetEntry = null;

                for (DirectoryEntry entry : entries) {
                    if (!entry.isFree() && entry.getName().equals(name)) {
                        targetEntry = entry;
                        break;
                    }
                }

                if (targetEntry == null) {
                    System.err.println("rm: no se puede borrar '" + path + "': No existe el archivo o directorio");
                    continue;
                }

                // Procesar eliminación
                Inode targetInode = fs.readInode(targetEntry.getInodeNumber());
                deleteRecursively(parentInode, targetInode, targetEntry.getName(), recursive);

            } catch (IOException e) {
                System.err.println("rm: error al borrar '" + path + "': " + e.getMessage());
            }
        }
    }

    private void deleteRecursively(Inode parentInode, Inode targetInode, String name, boolean recursive)
            throws IOException {
        // Verificar permisos (solo propietario o root)
        if (!isRoot() && targetInode.getOwnerUid() != currentUser.getUserId()) {
            throw new IOException("Permiso denegado");
        }

        if (targetInode.isDirectory()) {
            // Verificar si está vacío
            List<DirectoryEntry> entries = fs.readDirectoryEntries(targetInode);
            boolean isEmpty = true;
            for (DirectoryEntry entry : entries) {
                if (!entry.isFree() && !entry.getName().equals(".") && !entry.getName().equals("..")) {
                    isEmpty = false;
                    break;
                }
            }

            if (!isEmpty && !recursive) {
                throw new IOException("Es un directorio y no está vacío (use -R)");
            }

            if (!isEmpty) {
                // Borrado recursivo
                for (DirectoryEntry entry : entries) {
                    if (!entry.isFree() && !entry.getName().equals(".") && !entry.getName().equals("..")) {
                        Inode childInode = fs.readInode(entry.getInodeNumber());
                        deleteRecursively(targetInode, childInode, entry.getName(), true);
                    }
                }
            }

            // Actualizar link count del padre (por ".." del hijo)
            parentInode.setLinkCount(parentInode.getLinkCount() - 1);
            fs.writeInode(parentInode);
        }

        // Liberar bloques y el inode
        fs.releaseInodeBlocks(targetInode);
        fs.freeInode(targetInode.getInodeNumber());

        // Eliminar entrada del directorio padre
        removeEntryFromDirectory(parentInode, name);

        System.out.println("Eliminado: " + name);
    }

    private void removeEntryFromDirectory(Inode parentInode, String name) throws IOException {
        List<DirectoryEntry> entries = fs.readDirectoryEntries(parentInode);
        boolean found = false;

        for (int i = 0; i < entries.size(); i++) {
            DirectoryEntry entry = entries.get(i);
            if (!entry.isFree() && entry.getName().equals(name)) {
                // Marcar como libre
                entries.set(i, new DirectoryEntry());
                found = true;
                break;
            }
        }

        if (found) {
            fs.writeDirectoryEntries(parentInode, entries);
        }
    }

    /**
     * Resuelve una ruta (absoluta o relativa) y retorna el número de inode
     */
    private int resolvePath(String path) throws IOException {
        Inode currentInode;
        String[] parts;

        if (path.startsWith("/")) {
            currentInode = fs.readInode(FSConstants.ROOT_INODE);
            if (path.equals("/"))
                return FSConstants.ROOT_INODE;
            parts = path.substring(1).split("/");
        } else {
            currentInode = resolveCurrentDirectory();
            parts = path.split("/");
        }

        for (String part : parts) {
            if (part.isEmpty() || part.equals("."))
                continue;

            if (part.equals("..")) {
                // Handle parent directory
                // For simplicity, we can read the ".." entry from the current directory
                List<DirectoryEntry> entries = fs.readDirectoryEntries(currentInode);
                boolean found = false;
                for (DirectoryEntry entry : entries) {
                    if (!entry.isFree() && entry.getName().equals("..")) {
                        currentInode = fs.readInode(entry.getInodeNumber());
                        found = true;
                        break;
                    }
                }
                if (!found)
                    throw new IOException("Error resolviendo ruta: .. no encontrado");
                continue;
            }

            List<DirectoryEntry> entries = fs.readDirectoryEntries(currentInode);
            boolean found = false;
            for (DirectoryEntry entry : entries) {
                if (!entry.isFree() && entry.getName().equals(part)) {
                    currentInode = fs.readInode(entry.getInodeNumber());
                    found = true;
                    break;
                }
            }
            if (!found)
                return -1;
        }

        return currentInode.getInodeNumber();
    }

    /**
     * Mueve o renombra archivos y directorios
     */
    public void mv(String sourcePath, String destPath) throws IOException {
        requireAuth();

        // 1. Resolver Inode origen
        int sourceInodeNum = resolvePath(sourcePath);
        if (sourceInodeNum == -1) {
            System.err.println("mv: no se puede mover '" + sourcePath + "': No existe el archivo o directorio");
            return;
        }

        // Obtener nombre y padre del origen
        String sourceName;
        Inode sourceParentInode;
        if (sourcePath.contains("/")) {
            String parentPath = sourcePath.substring(0, sourcePath.lastIndexOf("/"));
            if (parentPath.isEmpty())
                parentPath = "/";
            sourceName = sourcePath.substring(sourcePath.lastIndexOf("/") + 1);
            sourceParentInode = fs.readInode(resolvePath(parentPath));
        } else {
            sourceName = sourcePath;
            sourceParentInode = resolveCurrentDirectory();
        }

        // 2. Resolver destino
        int destInodeNum = resolvePath(destPath);
        Inode destParentInode;
        String newName;

        if (destInodeNum != -1) {
            // El destino existe...
            Inode destInode = fs.readInode(destInodeNum);
            if (destInode.isDirectory()) {
                // Mover dentro del directorio existente
                destParentInode = destInode;
                newName = sourceName;

                // Verificar si ya existe en destino
                List<DirectoryEntry> destEntries = fs.readDirectoryEntries(destParentInode);
                if (directoryEntryExists(destEntries, newName)) {
                    System.err.println("mv: destino '" + newName + "' ya existe en '" + destPath + "'");
                    return;
                }
            } else {
                // Es un archivo existente... sobreescribir?
                // Implementación simple: Error
                System.err.println("mv: destino '" + destPath + "' ya existe y no es un directorio");
                return;
            }
        } else {
            // El destino no existe, asumimos renombrado/movido a nuevo nombre
            if (destPath.contains("/")) {
                String destParentPath = destPath.substring(0, destPath.lastIndexOf("/"));
                if (destParentPath.isEmpty())
                    destParentPath = "/";
                int destParentNum = resolvePath(destParentPath);
                if (destParentNum == -1) {
                    System.err.println("mv: directorio destino no existe");
                    return;
                }
                destParentInode = fs.readInode(destParentNum);
                newName = destPath.substring(destPath.lastIndexOf("/") + 1);
            } else {
                destParentInode = resolveCurrentDirectory();
                newName = destPath;
            }
        }

        // 3. Realizar movimiento

        // Remover entrada del padre original
        removeEntryFromDirectory(sourceParentInode, sourceName);

        // Agregar entrada al nuevo padre
        // Buscar espacio libre
        List<DirectoryEntry> destEntries = fs.readDirectoryEntries(destParentInode);
        int freeIndex = findFreeDirectoryEntry(destEntries);

        DirectoryEntry newEntry = new DirectoryEntry(sourceInodeNum,
                fs.readInode(sourceInodeNum).getFileType(), newName);

        if (freeIndex != -1) {
            destEntries.set(freeIndex, newEntry);
        } else {
            // Si el bloque está lleno, habría que asignar nuevo bloque, pero por ahora
            // asumimos espacio o error
            // Mejor implementación: expandir directorio si es necesario.
            // Para simplificar, si no cabe, error (aunque create file expande, aquí
            // deberíamos reusar lógica)
            // Reusando espacio libre o añadiendo al final si hay espacio en bloque
            if (destEntries.size() < (fs.getSuperblock().getBlockSize() / FSConstants.DIR_ENTRY_SIZE)) {
                destEntries.add(newEntry);
            } else {
                throw new IOException("Directorio destino lleno (no implementada expansión en mv)");
            }
        }

        fs.writeDirectoryEntries(destParentInode, destEntries);

        // Si es directorio, actualizar ".." ?? No, ".." apunta al padre por número de
        // inode.
        // Si movemos un directorio, su ".." debería apuntar al nuevo padre.
        Inode sourceInode = fs.readInode(sourceInodeNum);
        if (sourceInode.isDirectory()) {
            List<DirectoryEntry> sourceEntries = fs.readDirectoryEntries(sourceInode);
            for (int i = 0; i < sourceEntries.size(); i++) {
                if (!sourceEntries.get(i).isFree() && sourceEntries.get(i).getName().equals("..")) {
                    sourceEntries.set(i,
                            new DirectoryEntry(destParentInode.getInodeNumber(), FSConstants.TYPE_DIRECTORY, ".."));
                    fs.writeDirectoryEntries(sourceInode, sourceEntries);

                    // Actualizar link counts
                    sourceParentInode.setLinkCount(sourceParentInode.getLinkCount() - 1);
                    destParentInode.setLinkCount(destParentInode.getLinkCount() + 1);

                    fs.writeInode(sourceParentInode);
                    fs.writeInode(destParentInode);
                    break;
                }
            }
        }

        System.out.println("Movido '" + sourcePath + "' a '" + destPath + "/" + newName + "'");
    }

    /**
     * Resuelve el inode del directorio actual
     */
    private Inode resolveCurrentDirectory() throws IOException {
        if (currentDirectory.equals("/")) {
            return fs.readInode(FSConstants.ROOT_INODE);
        }

        // Para otros directorios, navegar desde la raíz
        String[] pathParts = currentDirectory.substring(1).split("/");
        Inode currentInode = fs.readInode(FSConstants.ROOT_INODE);

        for (String part : pathParts) {
            if (part.isEmpty()) {
                continue;
            }

            List<DirectoryEntry> entries = fs.readDirectoryEntries(currentInode);
            boolean found = false;

            for (DirectoryEntry entry : entries) {
                if (!entry.isFree() && entry.getName().equals(part)) {
                    currentInode = fs.readInode(entry.getInodeNumber());
                    found = true;
                    break;
                }
            }

            if (!found) {
                throw new IOException("Directorio no encontrado: " + currentDirectory);
            }
        }

        return currentInode;
    }

    /**
     * Lista el contenido del directorio
     */
    public void ls(String path, boolean recursive) throws IOException {
        Inode targetInode;
        String targetPath;

        if (path.equals(".")) {
            targetInode = resolveCurrentDirectory();
            targetPath = currentDirectory;
        } else {
            targetPath = resolvePathString(path);
            targetInode = resolvePathInode(targetPath);
        }

        if (!targetInode.isDirectory()) {
            System.out.println("No es un directorio: " + path);
            return;
        }

        System.out.println("Contenido de " + targetPath + ":");
        List<DirectoryEntry> entries = fs.readDirectoryEntries(targetInode);

        System.out.println("INODE\tTIPO\tTAMAÑO\tNOMBRE");
        System.out.println("-----\t----\t------\t------");

        for (DirectoryEntry entry : entries) {
            if (entry.isFree())
                continue;

            String typeStr = (entry.getEntryType() == FSConstants.TYPE_DIRECTORY) ? "DIR" : "FILE";
            Inode entryInode = fs.readInode(entry.getInodeNumber());

            System.out.printf("%d\t%s\t%d\t%s%n",
                    entry.getInodeNumber(),
                    typeStr,
                    entryInode.getFileSize(),
                    entry.getName());
        }

        if (recursive) {
            for (DirectoryEntry entry : entries) {
                if (entry.isFree())
                    continue;

                if (entry.getEntryType() == FSConstants.TYPE_DIRECTORY) {
                    String name = entry.getName();
                    if (!name.equals(".") && !name.equals("..")) {
                        String newPath;
                        if (targetPath.equals("/")) {
                            newPath = "/" + name;
                        } else {
                            newPath = targetPath + "/" + name;
                        }

                        System.out.println();
                        ls(newPath, true);
                    }
                }
            }
        }
    }

    /**
     * Cambia el directorio actual
     */
    public void cd(String path) throws IOException {
        requireAuth();

        String newPath = resolvePathString(path);
        Inode targetInode = resolvePathInode(newPath);

        if (!targetInode.isDirectory()) {
            throw new IOException("No es un directorio: " + path);
        }

        // Si llegamos aquí, el directorio existe y es válido
        currentDirectory = newPath;
        // Normalizar si es root para evitar "//"
        if (currentDirectory.length() > 1 && currentDirectory.endsWith("/")) {
            currentDirectory = currentDirectory.substring(0, currentDirectory.length() - 1);
        }
    }

    /**
     * Resuelve una ruta (absoluta o relativa) a un String de ruta absoluta
     * normalizada
     */
    private String resolvePathString(String path) {
        if (path.equals(".")) {
            return currentDirectory;
        }

        List<String> parts = new ArrayList<>();

        // Si es absoluta, empezar desde vacío (root)
        if (path.startsWith("/")) {
            // nada, parts está vacío
        } else {
            // Si es relativa, empezar con las partes del directorio actual
            String[] currParts = currentDirectory.split("/");
            for (String p : currParts) {
                if (!p.isEmpty())
                    parts.add(p);
            }
        }

        // Procesar la nueva ruta
        String[] newParts = path.split("/");
        for (String p : newParts) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            } else if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(p);
            }
        }

        // Reconstruir
        if (parts.isEmpty()) {
            return "/";
        }

        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append("/").append(p);
        }
        return sb.toString();
    }

    /**
     * Resuelve un String de ruta absoluta a su Inode correspondiente
     */
    private Inode resolvePathInode(String absolutePath) throws IOException {
        if (absolutePath.equals("/")) {
            return fs.readInode(FSConstants.ROOT_INODE);
        }

        String[] pathParts = absolutePath.substring(1).split("/");
        Inode currentInode = fs.readInode(FSConstants.ROOT_INODE);

        for (String part : pathParts) {
            if (part.isEmpty())
                continue;

            List<DirectoryEntry> entries = fs.readDirectoryEntries(currentInode);
            boolean found = false;

            for (DirectoryEntry entry : entries) {
                if (!entry.isFree() && entry.getName().equals(part)) {
                    currentInode = fs.readInode(entry.getInodeNumber());
                    found = true;
                    break;
                }
            }

            if (!found) {
                throw new IOException("No existe el archivo o directorio: " + absolutePath);
            }
        }
        return currentInode;
    }

    /**
     * Valida el nombre de un archivo o directorio
     */
    private void validateFileName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("El nombre no puede estar vacío");
        }

        if (name.length() > 244) {
            throw new IOException("El nombre es demasiado largo (máximo 244 caracteres)");
        }

        // Caracteres prohibidos
        if (name.contains("/") || name.contains("\0")) {
            throw new IOException("El nombre contiene caracteres inválidos");
        }

        // Nombres reservados
        if (name.equals(".") || name.equals("..")) {
            throw new IOException("Nombre reservado: " + name);
        }
    }

    /**
     * Verifica si existe una entrada con el nombre dado
     */
    private boolean directoryEntryExists(List<DirectoryEntry> entries, String name) {
        for (DirectoryEntry entry : entries) {
            if (!entry.isFree() && entry.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Encuentra el índice de la primera entrada libre en un directorio
     */
    private int findFreeDirectoryEntry(List<DirectoryEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).isFree()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Muestra información del sistema de archivos
     */
    public void infoFS() throws IOException {
        if (fs == null || !fs.isMounted()) {
            throw new IOException("Sistema de archivos no montado");
        }

        Superblock sb = fs.getSuperblock();

        System.out.println("\n=== INFORMACIÓN DEL SISTEMA DE ARCHIVOS ===");
        System.out.println("Nombre: " + sb.getFsName());
        System.out.println("Versión: " + sb.getFsVersion());
        System.out.println("Archivo: " + fsFilePath);

        long totalSizeMB = ((long) sb.getTotalBlocks() * sb.getBlockSize()) / (1024 * 1024);
        long usedBlocks = sb.getTotalBlocks() - sb.getFreeBlocks();
        long usedSizeMB = (usedBlocks * sb.getBlockSize()) / (1024 * 1024);
        long freeSizeMB = ((long) sb.getFreeBlocks() * sb.getBlockSize()) / (1024 * 1024);

        System.out.println("\nTamaño total: " + totalSizeMB + " MB");
        System.out.println("Espacio utilizado: " + usedSizeMB + " MB");
        System.out.println("Espacio disponible: " + freeSizeMB + " MB");

        System.out.println("\nBloques:");
        System.out.println("  Tamaño de bloque: " + sb.getBlockSize() + " bytes");
        System.out.println("  Total de bloques: " + sb.getTotalBlocks());
        System.out.println("  Bloques libres: " + sb.getFreeBlocks());
        System.out.println("  Bloques usados: " + usedBlocks);

        System.out.println("\nInodes:");
        System.out.println("  Total de inodes: " + sb.getTotalInodes());
        System.out.println("  Inodes libres: " + sb.getFreeInodes());
        System.out.println("  Inodes usados: " + (sb.getTotalInodes() - sb.getFreeInodes()));

        String strategy = "";
        switch (sb.getAllocationStrategy()) {
            case FSConstants.ALLOC_CONTIGUOUS:
                strategy = "Asignación Contigua";
                break;
            case FSConstants.ALLOC_LINKED:
                strategy = "Asignación Enlazada";
                break;
            case FSConstants.ALLOC_INDEXED:
                strategy = "Asignación Indexada";
                break;
        }
        System.out.println("\nEstrategia de asignación: " + strategy);

        System.out.println("\nUsuarios registrados: " + fs.getUserTable().size());
        System.out.println("Grupos registrados: " + fs.getGroupTable().size());
    }

    /**
     * Verifica si el usuario actual es root
     */
    private boolean isRoot() {
        return currentUser != null && currentUser.getUserId() == FSConstants.ROOT_UID;
    }

    /**
     * Verifica si hay un usuario autenticado
     */
    private void requireAuth() throws IOException {
        if (currentUser == null) {
            throw new IOException("Debe autenticarse primero. Use el comando 'su'");
        }
    }

    /**
     * Cierra el gestor del sistema de archivos
     */
    public void shutdown() throws IOException {
        if (fs != null && fs.isMounted()) {
            unmount();
        }
        running = false;
    }

    /**
     * Obtiene el prompt del shell
     */
    public String getPrompt() {
        if (currentUser == null) {
            return "guest@myFS$ ";
        }
        return currentUser.getUsername() + "@myFS:" + currentDirectory + "$ ";
    }

    // Getters
    public FileSystem getFileSystem() {
        return fs;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}
