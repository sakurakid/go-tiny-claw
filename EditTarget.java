public class EditTarget {
    public static void main(String[] args) {
        Greeter greeter = new Greeter();
        System.out.println(greeter.message());
    }

    static final class Greeter {
        String message() {
            return "Hello from original edit target.";
        }
    }
}
