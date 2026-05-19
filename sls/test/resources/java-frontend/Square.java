package fixture;

public class Square implements Shapes {
    private double side;
    protected int id;
    public String label;

    public Square(double side) {
        this.side = side;
    }

    @Override
    public double area() {
        return side * side;
    }
}
