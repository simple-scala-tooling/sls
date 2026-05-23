package fixture
sealed trait Shape
class Circle(radius: Double) extends Shape
class Rectangle(w: Double, h: Double) extends Shape
