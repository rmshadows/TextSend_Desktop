package RandomNumber;

public class test {
    public static void main(String[] args) {
        int x = 0;
        for (int i = 0; i < 100; i++) {
            x = RandomNumber.randomInt(0, 1000);
            System.out.println("x1 = " + x);
            x = RandomNumber.mathRandomInt(0, 1000);
            System.out.println("x2 = " + x);
            x = RandomNumber.secureRandomInt(0, 1000);
            System.out.println("x3 = " + x);
        }
    }
}
