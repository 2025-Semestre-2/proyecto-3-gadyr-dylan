package tests;
import commands.ShellCommands;
import picocli.CommandLine;
import java.util.Scanner;

public class ComandosShellTest {
    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            CommandLine cmd = new CommandLine(new ShellCommands());
            
            System.out.println("Shell Interactivo (escribe 'exit' para salir)");
            
            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                
                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }
                
                try {
                    cmd.execute(input.split("\\s+"));
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
    }
}