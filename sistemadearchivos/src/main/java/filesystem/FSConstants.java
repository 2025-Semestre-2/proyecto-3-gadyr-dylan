package filesystem;

/**
 *
 * @author dylan
 */
public class FSConstants {
    // Tamaños y configuración básica
    public static final int BLOCK_SIZE = 4096;                                  // 4 KB por bloque
    public static final int INODE_SIZE = 256;                                   // Tamaño fijo de cada inode
    public static final int DIR_ENTRY_SIZE = 256;                               // Tamaño de cada entrada de directorio
    public static final int ENTRIES_PER_BLOCK = BLOCK_SIZE / DIR_ENTRY_SIZE;    // 16 entradas
    
    // Magic number para el FS
    public static final int MAGIC_NUMBER = 0x5346594D;                          // "MYFS" en hex
    
    // Tipos de archivo
    public static final int TYPE_FREE = 0;
    public static final int TYPE_FILE = 1;
    public static final int TYPE_DIRECTORY = 2;
    public static final int TYPE_LINK = 3;
    
    // Estrategias de asignación
    public static final int ALLOC_CONTIGUOUS = 1;
    public static final int ALLOC_LINKED = 2;
    public static final int ALLOC_INDEXED = 3;
    
    // Punteros en inode indexado
    public static final int DIRECT_POINTERS = 12;
    public static final int POINTERS_PER_BLOCK = BLOCK_SIZE / 4;                // 1024 punteros
    
    // IDs especiales
    public static final int ROOT_INODE = 0;
    public static final int ROOT_UID = 0;
    public static final int ROOT_GID = 0;
    
    // Permisos por defecto
    public static final int DEFAULT_DIR_PERMS = 0x0077;                         // rwxrwx---
    public static final int DEFAULT_FILE_PERMS = 0x0066;                        // rw-rw----
    
    // Nombre del archivo por defecto
    public static final String DEFAULT_FS_FILE = "miDiscoDuro.fs";
}
