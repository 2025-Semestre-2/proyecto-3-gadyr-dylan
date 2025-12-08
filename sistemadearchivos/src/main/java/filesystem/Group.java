package filesystem;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author gadyr y dylan
 */
public class Group {

    private int groupId;
    private String groupName;
    private List<Integer> members; // Lista de user IDs

    public Group() {
        this.groupId = -1;
        this.groupName = "";
        this.members = new ArrayList<>();
    }

    public Group(int groupId, String groupName) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.members = new ArrayList<>();
    }

    // Getters y Setters
    public int getGroupId() {
        return groupId;
    }

    public void setGroupId(int groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public List<Integer> getMembers() {
        return members;
    }

    public void setMembers(List<Integer> members) {
        this.members = members;
    }

    /**
     * Agrega un miembro al grupo
     */
    public void addMember(int userId) {
        if (!members.contains(userId)) {
            members.add(userId);
        }
    }

    /**
     * Elimina un miembro del grupo
     */
    public void removeMember(int userId) {
        members.remove(Integer.valueOf(userId));
    }

    /**
     * Verifica si un usuario es miembro del grupo
     */
    public boolean isMember(int userId) {
        return members.contains(userId);
    }

    /**
     * Serializa el grupo a bytes (tamaño fijo: 512 bytes)
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        buffer.putInt(groupId);

        // groupName (64 bytes)
        byte[] nameBytes = new byte[64];
        if (groupName != null && !groupName.isEmpty()) {
            byte[] actual = groupName.getBytes();
            System.arraycopy(actual, 0, nameBytes, 0,
                    Math.min(actual.length, 64));
        }
        buffer.put(nameBytes);

        // Número de miembros
        buffer.putInt(members.size());

        // Miembros (hasta 100 miembros, 4 bytes cada uno = 400 bytes)
        for (int i = 0; i < 100; i++) {
            if (i < members.size()) {
                buffer.putInt(members.get(i));
            } else {
                buffer.putInt(-1); // -1 indica espacio vacío
            }
        }

        return buffer.array();
    }

    /**
     * Deserializa un grupo desde bytes
     */
    public static Group fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);

        Group group = new Group();
        group.groupId = buffer.getInt();

        // groupName
        byte[] nameBytes = new byte[64];
        buffer.get(nameBytes);
        group.groupName = new String(nameBytes).trim().replace("\0", "");

        // Número de miembros
        int memberCount = buffer.getInt();

        // Leer miembros
        group.members = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int memberId = buffer.getInt();
            if (i < memberCount && memberId != -1) {
                group.members.add(memberId);
            }
        }

        return group;
    }
}
