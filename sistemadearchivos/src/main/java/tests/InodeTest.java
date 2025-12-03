package tests;

import filesystem.Inode;
import filesystem.FSConstants;
import java.util.Arrays;

/**
 *
 * @author dylan
 */
public class InodeTest {

    public static void main(String[] args) {

        Inode original = new Inode();
        original.setInodeNumber(42);
        original.setFileType(FSConstants.TYPE_FILE);
        original.setPermissions(755);
        original.setOwnerUid(1000);
        original.setGroupGid(1000);
        original.setFileSize(987654321L);
        original.setLinkCount(3);
        original.setCreationTime(1111111111L);
        original.setModificationTime(2222222222L);
        original.setAccessTime(3333333333L);
        original.setIsOpen(1);
        original.setName("test_file.txt");

        // direct blocks
        for (int i = 0; i < FSConstants.DIRECT_POINTERS; i++) {
            original.setDirectBlock(i, 100 + i);
        }
        original.setSingleIndirect(500);
        original.setDoubleIndirect(600);
        original.setTripleIndirect(700);

        System.out.println("===== TEST INODE: SERIALIZACIÓN / DESERIALIZACIÓN =====");

        byte[] serialized = original.toBytes();

        Inode deserialized = Inode.fromBytes(Arrays.copyOf(serialized, serialized.length));

        compare("inodeNumber", original.getInodeNumber(), deserialized.getInodeNumber());
        compare("fileType", original.getFileType(), deserialized.getFileType());
        compare("permissions", original.getPermissions(), deserialized.getPermissions());
        compare("ownerUid", original.getOwnerUid(), deserialized.getOwnerUid());
        compare("groupGid", original.getGroupGid(), deserialized.getGroupGid());
        compare("fileSize", original.getFileSize(), deserialized.getFileSize());
        compare("linkCount", original.getLinkCount(), deserialized.getLinkCount());
        compare("creationTime", original.getCreationTime(), deserialized.getCreationTime());
        compare("modificationTime", original.getModificationTime(), deserialized.getModificationTime());
        compare("accessTime", original.getAccessTime(), deserialized.getAccessTime());
        compare("isOpen", original.getIsOpen(), deserialized.getIsOpen());
        compare("name", original.getName(), deserialized.getName());

        // direct blocks
        for (int i = 0; i < FSConstants.DIRECT_POINTERS; i++) {
            compare("directBlock[" + i + "]",
                    original.getDirectBlocks()[i],
                    deserialized.getDirectBlocks()[i]);
        }

        compare("singleIndirect", original.getSingleIndirect(), deserialized.getSingleIndirect());
        compare("doubleIndirect", original.getDoubleIndirect(), deserialized.getDoubleIndirect());
        compare("tripleIndirect", original.getTripleIndirect(), deserialized.getTripleIndirect());

        System.out.println("\n===== FIN TEST INODE =====");
    }

    private static void compare(String name, Object expected, Object actual) {
        System.out.println("\n--- " + name + " ---");
        System.out.println(" esperado : " + expected);
        System.out.println(" obtenido : " + actual);

        if ((expected == null && actual == null)
                || (expected != null && expected.equals(actual))) {
            System.out.println(" RESULTADO: OK");
        } else {
            System.out.println(" RESULTADO: ERROR");
        }
    }
}
