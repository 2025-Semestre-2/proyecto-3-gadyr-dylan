package filesystem;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 *
 * @author dylan
 */
public class Inode {

    private int inodeNumber;
    private int fileType;
    private int permissions;
    private int ownerUid;
    private int groupGid;
    private long fileSize;
    private int linkCount;
    private long creationTime;
    private long modificationTime;
    private long accessTime;
    private int isOpen;
    private String name;

    // asignación indexada
    private int[] directBlocks;
    private int singleIndirect;
    private int doubleIndirect;
    private int tripleIndirect;

    public Inode() {
        this.directBlocks = new int[FSConstants.DIRECT_POINTERS];
        Arrays.fill(directBlocks, -1);
        this.singleIndirect = -1;
        this.doubleIndirect = -1;
        this.tripleIndirect = -1;
        this.fileType = FSConstants.TYPE_FREE;
        this.linkCount = 0;
        this.isOpen = 0;
        this.name = "";
    }

    public Inode(int inodeNumber, int fileType, int permissions, int ownerUid, int groupGid) {
        this();

        this.inodeNumber = inodeNumber;
        this.fileType = fileType;
        this.permissions = permissions;
        this.ownerUid = ownerUid;
        this.groupGid = groupGid;
        this.creationTime = System.currentTimeMillis();
        this.modificationTime = creationTime;
        this.accessTime = creationTime;
    }

    // Getters y setters
    public int getInodeNumber() {
        return inodeNumber;
    }

    public void setInodeNumber(int inodeNumber) {
        this.inodeNumber = inodeNumber;
    }

    public int getFileType() {
        return fileType;
    }

    public void setFileType(int fileType) {
        this.fileType = fileType;
    }

    public int getPermissions() {
        return permissions;
    }

    public void setPermissions(int permissions) {
        this.permissions = permissions;
    }

    public int getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(int ownerUid) {
        this.ownerUid = ownerUid;
    }

    public int getGroupGid() {
        return groupGid;
    }

    public void setGroupGid(int groupGid) {
        this.groupGid = groupGid;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public int getLinkCount() {
        return linkCount;
    }

    public void setLinkCount(int linkCount) {
        this.linkCount = linkCount;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getModificationTime() {
        return modificationTime;
    }

    public void setModificationTime(long modificationTime) {
        this.modificationTime = modificationTime;
    }

    public long getAccessTime() {
        return accessTime;
    }

    public void setAccessTime(long accessTime) {
        this.accessTime = accessTime;
    }

    public int getIsOpen() {
        return isOpen;
    }

    public void setIsOpen(int isOpen) {
        this.isOpen = isOpen;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int[] getDirectBlocks() {
        return directBlocks;
    }

    public void setDirectBlock(int index, int blockNumber) {
        if (index >= 0 && index < FSConstants.DIRECT_POINTERS) {
            this.directBlocks[index] = blockNumber;
        }
    }

    public int getSingleIndirect() {
        return singleIndirect;
    }

    public void setSingleIndirect(int singleIndirect) {
        this.singleIndirect = singleIndirect;
    }

    public int getDoubleIndirect() {
        return doubleIndirect;
    }

    public void setDoubleIndirect(int doubleIndirect) {
        this.doubleIndirect = doubleIndirect;
    }

    public int getTripleIndirect() {
        return tripleIndirect;
    }

    public void setTripleIndirect(int tripleIndirect) {
        this.tripleIndirect = tripleIndirect;
    }

    /**
     * Verifica si el inode está libre
     * @return 
     */
    public boolean isFree() {
        return fileType == FSConstants.TYPE_FREE;
    }

    /**
     * Verifica si es un directorio
     * @return 
     */
    public boolean isDirectory() {
        return fileType == FSConstants.TYPE_DIRECTORY;
    }

    /**
     * Verifica si es un archivo regular
     * @return 
     */
    public boolean isFile() {
        return fileType == FSConstants.TYPE_FILE;
    }

    /**
     * Verifica si es un link
     * @return 
     */
    public boolean isLink() {
        return fileType == FSConstants.TYPE_LINK;
    }
    
    /**
     * Serializa el Inode a bytes
     * @return 
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(FSConstants.INODE_SIZE);
        
        buffer.putInt(inodeNumber);
        buffer.putInt(fileType);
        buffer.putInt(permissions);
        buffer.putInt(ownerUid);
        buffer.putInt(groupGid);
        buffer.putLong(fileSize);
        buffer.putInt(linkCount);
        buffer.putLong(creationTime);
        buffer.putLong(modificationTime);
        buffer.putLong(accessTime);
        buffer.putInt(isOpen);
        
        // Name 64 bytes
        byte[] nameBytes = new byte[64];
        if (name != null && !name.isEmpty()) {
            byte[] actualName = name.getBytes();
            System.arraycopy(actualName, 0, nameBytes, 0, Math.min(actualName.length, 64));            
        }
        buffer.put(nameBytes);
        
        // Direct blocks
        for (int i = 0; i < FSConstants.DIRECT_POINTERS; i++) {
            buffer.putInt(directBlocks[i]);
        }
        
        buffer.putInt(singleIndirect);
        buffer.putInt(doubleIndirect);
        buffer.putInt(tripleIndirect);
        
        return buffer.array();
    }
    
    /**
     * Deserializa un Inode desde bytes
     * @param data
     * @return 
     */
    public static Inode fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        Inode inode = new Inode();
        inode.inodeNumber = buffer.getInt();
        inode.fileType = buffer.getInt();
        inode.permissions = buffer.getInt();
        inode.ownerUid = buffer.getInt();
        inode.groupGid = buffer.getInt();
        inode.fileSize = buffer.getLong();
        inode.linkCount = buffer.getInt();
        inode.creationTime = buffer.getLong();
        inode.modificationTime = buffer.getLong();
        inode.accessTime = buffer.getLong();
        inode.isOpen = buffer.getInt();
        
        // name
        byte[] nameBytes = new byte[64];
        buffer.get(nameBytes);
        inode.name = new String(nameBytes).trim().replace("\0", "");
        
        // Direct blocks
        for (int i = 0; i < FSConstants.DIRECT_POINTERS; i++) {
            inode.directBlocks[i] = buffer.getInt();
        }
        
        inode.singleIndirect = buffer.getInt();
        inode.doubleIndirect = buffer.getInt();
        inode.tripleIndirect = buffer.getInt();
        
        return inode;
    }    
}
