package tests;

import filesystem.Group;
import java.util.Arrays;

/**
 *
 * @author gadyr
 */
public class GroupTest {

    public static void main(String[] args) {

        Group original = new Group(42, "Admins");
        original.addMember(1001);
        original.addMember(1002);
        original.addMember(1003);

        System.out.println("=== Group: Serialización / Deserialización ===");

        byte[] serialized = original.toBytes();

        System.out.println("\n--- BYTES DEL GRUPO SERIALIZADO (hexdump) ---");

        Group deserialized = Group.fromBytes(Arrays.copyOf(serialized, serialized.length));

        compare("groupId", original.getGroupId(), deserialized.getGroupId());
        compare("groupName", original.getGroupName(), deserialized.getGroupName());
        compare("members", original.getMembers(), deserialized.getMembers());

        System.out.println("\n=== FIN ===");
    }

    private static void compare(String field, Object expected, Object actual) {
        System.out.println("\n--- " + field + " ---");
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
